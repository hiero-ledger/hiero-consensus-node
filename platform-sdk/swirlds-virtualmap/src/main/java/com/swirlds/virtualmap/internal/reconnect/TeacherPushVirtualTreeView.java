// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.reconnect;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.virtualmap.internal.Path.INVALID_PATH;
import static com.swirlds.virtualmap.internal.Path.ROOT_PATH;
import static com.swirlds.virtualmap.internal.Path.getLeftChildPath;
import static com.swirlds.virtualmap.internal.Path.getRightChildPath;

import com.swirlds.base.time.Time;
import com.swirlds.common.io.streams.MerkleDataInputStream;
import com.swirlds.common.io.streams.MerkleDataOutputStream;
import com.swirlds.common.merkle.synchronization.TeachingSynchronizer;
import com.swirlds.common.merkle.synchronization.config.ReconnectConfig;
import com.swirlds.common.merkle.synchronization.streams.AsyncInputStream;
import com.swirlds.common.merkle.synchronization.streams.AsyncOutputStream;
import com.swirlds.common.merkle.synchronization.task.Lesson;
import com.swirlds.common.merkle.synchronization.task.QueryResponse;
import com.swirlds.common.merkle.synchronization.task.TeacherPushReceiveTask;
import com.swirlds.common.merkle.synchronization.task.TeacherPushSendTask;
import com.swirlds.common.merkle.synchronization.task.TeacherSubtree;
import com.swirlds.common.merkle.synchronization.utility.MerkleSynchronizationException;
import com.swirlds.common.merkle.synchronization.views.TeacherTreeView;
import com.swirlds.common.threading.framework.config.ThreadConfiguration;
import com.swirlds.common.threading.manager.ThreadManager;
import com.swirlds.common.threading.pool.StandardWorkGroup;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import com.swirlds.virtualmap.internal.ConcurrentNodeStatusTracker;
import com.swirlds.virtualmap.internal.RecordAccessor;
import com.swirlds.virtualmap.internal.merkle.VirtualMapMetadata;
import com.swirlds.virtualmap.internal.pipeline.VirtualPipeline;
import java.io.IOException;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.Hash;
import org.hiero.base.io.streams.SerializableDataOutputStream;

/**
 * An implementation of {@link TeacherTreeView} designed for virtual merkle trees.
 */
public final class TeacherPushVirtualTreeView extends VirtualTreeViewBase implements TeacherTreeView<Long> {

    private static final Logger logger = LogManager.getLogger(TeacherPushVirtualTreeView.class);

    private final ReconnectConfig reconnectConfig;

    // A queue of the nodes (by path) that we are about to handle. Note that ConcurrentBitSetQueue
    // cleans up after itself in "chunks", such that we don't end up consuming a ton of memory.
    // We use two separate accumulating and processing queues to implement a level-by-level traversal:

    /** A queue to which we add items for the next level currently. */
    private ConcurrentBitSetQueue accumulatingHandleQueue = new ConcurrentBitSetQueue();

    /** A queue which we're processing currently (aka sending nodes to the learner). */
    private ConcurrentBitSetQueue processingHandleQueue = new ConcurrentBitSetQueue();

    /** Flip the queues by exchanging the accumulating and processing queues. */
    private synchronized void flipQueues() {
        // We should only flip the queues once the current processing queue becomes empty,
        // so that after the flip we start processing entries accumulated in the prior
        // accumulating queue.
        assert processingHandleQueue.isEmpty();

        final ConcurrentBitSetQueue temp = accumulatingHandleQueue;
        accumulatingHandleQueue = processingHandleQueue;
        processingHandleQueue = temp;
    }

    /**
     * A node which status has to be reported by the learner before the teacher resumes sending
     * the next level of the tree.
     * The node can end up being either KNOWN or NOT_KNOWN, either explicitly or implicitly
     * by inferring the status from the previously reported ancestors' statuses (except
     * the root status because it's always implicitly NOT_KNOWN, so we ignore it.)
     * <p>
     * It's volatile to avoid extra locks when accessing the value. It's totally
     * okay if the value is updated after it's read. The worst that could happen
     * is that we'll start sending the next level of nodes before receiving
     * a response for the last node at the current level, but this is unlikely to happen
     * because the code processing the queue is single-threaded,
     * and it doesn't hurt from the correctness perspective even if it happens.
     */
    private volatile Long lastNodeAwaitingReporting = null;

    /**
     * A node status may become implicitly reported by a response about its ancestors,
     * in which case we can retest the condition and go ahead and send the next level
     * without waiting for this particular node to report its status since it's already known/inferred.
     * So we use a timeout to recheck the condition periodically.
     */
    private static final long AWAIT_FOR_REPORT_TIMEOUT_MILLIS = 1;

    /**
     * The maximum total time for the `while (!hasReported) { wait() }` loop.
     * This is used to protect the teacher from a dying learner.
     */
    private static final long MAX_TOTAL_AWAIT_FOR_REPORT_TIMEOUT_MILLIS = 1000;

    /**
     * A queue of the nodes (by path) that we expect responses for.
     */
    private final ConcurrentBitSetQueue expectedResponseQueue = new ConcurrentBitSetQueue();

    /**
     * Keeps track of responses from learner about known, unknown, and not known nodes.
     */
    private final ConcurrentNodeStatusTracker nodeStatusTracker = new ConcurrentNodeStatusTracker(Long.MAX_VALUE);

    /**
     * The {@link RecordAccessor} used for accessing the original map state.
     */
    private RecordAccessor records;

    /**
     * This latch counts down when the view is fully initialized and ready for use.
     */
    private final CountDownLatch readyLatch = new CountDownLatch(1);

    /**
     * Indicates whether this teacher view is ready after {@link #readyLatch} is released.
     */
    private final AtomicBoolean ready = new AtomicBoolean(false);

    /**
     * Create a new {@link TeacherPushVirtualTreeView}.
     *
     * @param threadManager
     * 		responsible for creating and managing threads
     * @param map
     * 		The map node on the teacher side of the saved state that we are going to reconnect.
     * @param state
     * 		The state of the virtual tree that we are synchronizing.
     * @param pipeline
     * 		The pipeline managing the virtual map.
     */
    public TeacherPushVirtualTreeView(
            final ThreadManager threadManager,
            final ReconnectConfig reconnectConfig,
            final VirtualMap map,
            final VirtualMapMetadata state,
            final VirtualPipeline pipeline) {
        // There is no distinction between originalState and reconnectState in this implementation
        super(map, state, state);
        this.reconnectConfig = reconnectConfig;
        new ThreadConfiguration(threadManager)
                .setRunnable(() -> {
                    try {
                        records = pipeline.pausePipelineAndRun("copy", map::detach);
                        ready.set(true);
                    } finally {
                        readyLatch.countDown();
                    }
                })
                .setComponent("virtualmap")
                .setThreadName("detacher")
                .build()
                .start();
    }

    @Override
    public void startTeacherTasks(
            final TeachingSynchronizer teachingSynchronizer,
            final Time time,
            final StandardWorkGroup workGroup,
            final MerkleDataInputStream inputStream,
            final MerkleDataOutputStream outputStream,
            final Queue<TeacherSubtree> subtrees) {
        final AsyncInputStream<QueryResponse> in =
                new AsyncInputStream<>(inputStream, workGroup, QueryResponse::new, reconnectConfig);
        in.start();
        final AsyncOutputStream<Lesson<Long>> out = teachingSynchronizer.buildOutputStream(workGroup, outputStream);
        out.start();

        final AtomicBoolean senderIsFinished = new AtomicBoolean(false);

        final TeacherPushSendTask<Long> teacherSendTask =
                new TeacherPushSendTask<>(time, reconnectConfig, workGroup, in, out, subtrees, this, senderIsFinished);
        teacherSendTask.start();
        final TeacherPushReceiveTask<Long> teacherReceiveTask =
                new TeacherPushReceiveTask<>(workGroup, in, this, senderIsFinished);
        teacherReceiveTask.start();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void waitUntilReady() throws InterruptedException {
        readyLatch.await();
        if (!ready.get()) {
            throw new RuntimeException("Failed to wait until teacher view is ready");
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Long getRoot() {
        return ROOT_PATH;
    }

    private final AtomicLong processed = new AtomicLong(0);

    /**
     * {@inheritDoc}
     */
    @Override
    public void addToHandleQueue(final Long node) {
        processed.incrementAndGet();
        checkValidNode(node, reconnectState);
        accumulatingHandleQueue.add(node);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Long getNextNodeToHandle() {
        if (processingHandleQueue.isEmpty()) {
            // We've just sent an entire level of the tree, and before we resume sending the next level
            // which has been accumulated in the current accumulatingHandleQueue, we'll wait
            // until the learner has reported the status of the lastNodeAwaitingReporting.
            // Note that in case we're just starting, there hasn't been any nodes sent yet,
            // so we have to flip w/o waiting in that case (when it's null.)
            if (lastNodeAwaitingReporting != null) {
                try {
                    synchronized (lastNodeAwaitingReporting) {
                        final long waitStartMillis = System.currentTimeMillis();
                        while (!hasLearnerReportedFor(lastNodeAwaitingReporting)
                                && System.currentTimeMillis() - waitStartMillis
                                        < MAX_TOTAL_AWAIT_FOR_REPORT_TIMEOUT_MILLIS) {
                            lastNodeAwaitingReporting.wait(AWAIT_FOR_REPORT_TIMEOUT_MILLIS);
                        }
                    }
                } catch (InterruptedException ignore) {
                    // We can ignore this. In the worst case, we'll just go ahead
                    // and send the next level w/o awaiting a report from the learner.
                }
            }
            // Exchange the accumulating queue with the processing queue:
            flipQueues();
            // Note that we know the other queue isn't empty because this method has been called after
            // the caller checked areThereNodesToHandle() which tests both the queues.
        }
        final long node = processingHandleQueue.remove();
        // Avoid waiting on a node which status has already been reported/inferred:
        if (!hasLearnerReportedFor(node)) {
            lastNodeAwaitingReporting = node;
        }
        return node;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean areThereNodesToHandle() {
        return !processingHandleQueue.isEmpty() || !accumulatingHandleQueue.isEmpty();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Long getChildAndPrepareForQueryResponse(final Long parent, final int childIndex) {
        final long child = getChild(parent, childIndex);
        expectedResponseQueue.add(child);
        return child;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Long getNodeForNextResponse() {
        return expectedResponseQueue.remove();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isResponseExpected() {
        return !expectedResponseQueue.isEmpty();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void registerResponseForNode(final Long node, final boolean learnerHasNode) {
        final ConcurrentNodeStatusTracker.Status status = learnerHasNode
                ? ConcurrentNodeStatusTracker.Status.KNOWN
                : ConcurrentNodeStatusTracker.Status.NOT_KNOWN;
        nodeStatusTracker.set(node, status);
        if (node == lastNodeAwaitingReporting) {
            synchronized (node) {
                node.notifyAll();
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean hasLearnerConfirmedFor(final Long node) {
        return nodeStatusTracker.getStatus(node) == ConcurrentNodeStatusTracker.Status.KNOWN;
    }

    /**
     * Determines if the status of the given node has been reported either directly,
     * or indirectly by reporting the status of an ancestor of the node.
     *
     * @param node a node
     * @return true if the status has been reported by the learner
     */
    private boolean hasLearnerReportedFor(final Long node) {
        return nodeStatusTracker.getReportedStatus(node) != ConcurrentNodeStatusTracker.Status.UNKNOWN;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void serializeLeaf(final SerializableDataOutputStream out, final Long leaf) throws IOException {
        checkValidLeaf(leaf, reconnectState);
        final VirtualLeafBytes leafRecord = records.findLeafRecord(leaf);
        assert leafRecord != null : "Unexpected null leaf record at path=" + leaf;
        VirtualReconnectUtils.writeLeafRecord(out, leafRecord);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void serializeInternal(final SerializableDataOutputStream out, final Long internal) throws IOException {
        checkValidInternal(internal, reconnectState);
        // We don't need to really serialize anything here, except the learner needs a long. So we'll send a long.
        out.writeLong(internal); // <--- works for VIRTUAL_MAP or any virtual internal node
        if (internal == ROOT_PATH) {
            out.writeLong(reconnectState.getFirstLeafPath());
            out.writeLong(reconnectState.getLastLeafPath());
        }
    }

    @Override
    public void writeChildHashes(final Long parent, final SerializableDataOutputStream out) throws IOException {
        checkValidInternal(parent, reconnectState);
        if (parent == ROOT_PATH && reconnectState.getLastLeafPath() == INVALID_PATH) {
            // out.writeSerializableList() writes just a single int if the list is empty
            out.writeInt(0);
            return;
        }
        final int size;
        if (parent > ROOT_PATH || (parent == ROOT_PATH && reconnectState.getLastLeafPath() > 1)) {
            size = 2;
        } else if (parent == ROOT_PATH && reconnectState.getLastLeafPath() == 1) {
            size = 1;
        } else {
            throw new MerkleSynchronizationException("Unexpected parent " + parent);
        }
        out.writeInt(size);
        // All same class? true
        out.writeBoolean(true);

        final long leftPath = getLeftChildPath(parent);
        // Is null? false
        out.writeBoolean(false);
        // Class version is written for the first entry only
        out.writeInt(Hash.CLASS_VERSION);
        // Write hash in SelfSerializable format
        if (!records.findAndWriteHash(leftPath, out)) {
            throw new MerkleSynchronizationException("Null hash for path = " + leftPath);
        }

        if (size == 2) {
            final long rightPath = getRightChildPath(parent);
            // Is null? false
            out.writeBoolean(false);
            // Class version is not written
            // Write hash in SelfSerializable format
            if (!records.findAndWriteHash(rightPath, out)) {
                throw new MerkleSynchronizationException("Null hash for path = " + rightPath);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isCustomReconnectRoot(final Long node) {
        return node == ROOT_PATH;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() {
        try {
            waitUntilReady();
            records.close();
        } catch (final IOException e) {
            logger.error(EXCEPTION.getMarker(), "interrupted while attempting to close data source");
        } catch (final InterruptedException e) {
            logger.error(EXCEPTION.getMarker(), "Failed to close data source properly", e);
            Thread.currentThread().interrupt();
        }
    }
}
