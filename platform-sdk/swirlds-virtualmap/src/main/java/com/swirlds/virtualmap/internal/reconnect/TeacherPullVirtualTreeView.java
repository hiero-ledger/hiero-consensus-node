// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.reconnect;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.virtualmap.internal.Path.ROOT_PATH;

import com.swirlds.base.time.Time;
import com.swirlds.common.io.streams.MerkleDataInputStream;
import com.swirlds.common.io.streams.MerkleDataOutputStream;
import com.swirlds.common.merkle.synchronization.TeachingSynchronizer;
import com.swirlds.common.merkle.synchronization.config.ReconnectConfig;
import com.swirlds.common.merkle.synchronization.streams.AsyncOutputStream;
import com.swirlds.common.merkle.synchronization.task.TeacherSubtree;
import com.swirlds.common.merkle.synchronization.views.TeacherTreeView;
import com.swirlds.common.threading.framework.config.ThreadConfiguration;
import com.swirlds.common.threading.manager.ThreadManager;
import com.swirlds.common.threading.pool.StandardWorkGroup;
import com.swirlds.virtualmap.VirtualKey;
import com.swirlds.virtualmap.VirtualValue;
import com.swirlds.virtualmap.internal.RecordAccessor;
import com.swirlds.virtualmap.internal.VirtualStateAccessor;
import com.swirlds.virtualmap.internal.merkle.VirtualRootNode;
import com.swirlds.virtualmap.internal.pipeline.VirtualPipeline;
import java.io.IOException;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.crypto.Hash;
import org.hiero.consensus.model.io.streams.SerializableDataOutputStream;

/**
 * An implementation of {@link TeacherTreeView} designed for virtual merkle trees.
 *
 * <p>This learner tree view creates two tasks running in the provided work group. One task
 * is responsible for sending requests to the teacher, the other one receives responses. Once
 * both tasks are completed, the corresponding virtual map is fully synchronized with the
 * teacher.
 *
 * <p>This implementation is supposed to work with {@link LearnerPullVirtualTreeView} on the
 * learner side.
 *
 * @param <K>
 * 		The key
 * @param <V>
 * 		The value
 */
public final class TeacherPullVirtualTreeView<K extends VirtualKey, V extends VirtualValue>
        extends VirtualTreeViewBase<K, V> implements TeacherTreeView<Long> {

    private static final Logger logger = LogManager.getLogger(TeacherPullVirtualTreeView.class);

    private final ReconnectConfig reconnectConfig;

    /**
     * The {@link RecordAccessor} used for accessing the original map state.
     */
    private RecordAccessor<K, V> records;

    /**
     * This latch counts down when the view is fully initialized and ready for use.
     */
    private final CountDownLatch ready = new CountDownLatch(1);

    /**
     * Create a new {@link TeacherPullVirtualTreeView}.
     *
     * @param threadManager
     * 		responsible for creating and managing threads
     * @param root
     * 		The root node on the teacher side of the saved state that we are going to reconnect.
     * @param state
     * 		The state of the virtual tree that we are synchronizing.
     * @param pipeline
     * 		The pipeline managing the virtual map.
     */
    public TeacherPullVirtualTreeView(
            final ThreadManager threadManager,
            final ReconnectConfig reconnectConfig,
            final VirtualRootNode<K, V> root,
            final VirtualStateAccessor state,
            final VirtualPipeline<K, V> pipeline) {
        // There is no distinction between originalState and reconnectState in this implementation
        super(root, state, state);
        this.reconnectConfig = reconnectConfig;
        new ThreadConfiguration(threadManager)
                .setRunnable(() -> {
                    records = pipeline.pausePipelineAndRun("copy", root::detach);
                    ready.countDown();
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
        final AsyncOutputStream<PullVirtualTreeResponse> out =
                teachingSynchronizer.buildOutputStream(workGroup, outputStream);
        out.start();

        final TeacherPullVirtualTreeReceiveTask teacherReceiveTask =
                new TeacherPullVirtualTreeReceiveTask(time, reconnectConfig, workGroup, inputStream, out, this);
        teacherReceiveTask.exec();
    }

    private boolean isLeaf(final long path) {
        return (path >= reconnectState.getFirstLeafPath()) && (path <= reconnectState.getLastLeafPath());
    }

    /**
     * Writes the virtual node identified by a given path to the output stream.
     *
     * <p>For the root node (path 0), reconnect state information is written: the first leaf path (long)
     * and the last leaf path (long). Other internal nodes are not written at all.
     *
     * <p>For dirty leaf nodes, the corresponding leaf records are written. Clean leaf nodes aren't
     * written at all.
     *
     * @param out the output stream
     * @param path the virtual path
     * @param isClean indicates if the virtual node on the learner side matches what's on the teacher
     * @throws IOException if an I/O error occurs
     */
    public void writeNode(final SerializableDataOutputStream out, final long path, final boolean isClean)
            throws IOException {
        checkValidNode(path, reconnectState);
        if (path == 0) {
            out.writeLong(reconnectState.getFirstLeafPath());
            out.writeLong(reconnectState.getLastLeafPath());
        }
        if (!isClean && isLeaf(path) && (reconnectState.getFirstLeafPath() > 0)) {
            out.writeSerializable(records.findLeafRecord(path, false), false);
        }
    }

    /**
     * Read the virtual node hash identified by a given path.
     *
     * @param path the virtual path
     * @return the virtual node hash
     */
    public Hash loadHash(final long path) {
        return records.findHash(path);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void waitUntilReady() throws InterruptedException {
        ready.await();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Long getRoot() {
        return ROOT_PATH;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addToHandleQueue(final Long node) {
        throw new UnsupportedOperationException("TeacherPullVirtualTreeView.addToHandleQueue()");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Long getNextNodeToHandle() {
        throw new UnsupportedOperationException("TeacherPullVirtualTreeView.getNextNodeToHandle()");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean areThereNodesToHandle() {
        throw new UnsupportedOperationException("TeacherPullVirtualTreeView.areThereNodesToHandle()");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Long getChildAndPrepareForQueryResponse(final Long parent, final int childIndex) {
        throw new UnsupportedOperationException("TeacherPullVirtualTreeView.getChildAndPrepareForQueryResponse()");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Long getNodeForNextResponse() {
        throw new UnsupportedOperationException("TeacherPullVirtualTreeView.getNodeForNextResponse()");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isResponseExpected() {
        throw new UnsupportedOperationException("TeacherPullVirtualTreeView.isResponseExpected()");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void registerResponseForNode(final Long node, final boolean learnerHasNode) {
        throw new UnsupportedOperationException("TeacherPullVirtualTreeView.registerResponseForNode()");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean hasLearnerConfirmedFor(final Long node) {
        throw new UnsupportedOperationException("TeacherPullVirtualTreeView.hasLearnerConfirmedFor()");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void serializeLeaf(final SerializableDataOutputStream out, final Long leaf) throws IOException {
        throw new UnsupportedOperationException("TeacherPullVirtualTreeView.serializeLeaf()");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void serializeInternal(final SerializableDataOutputStream out, final Long internal) throws IOException {
        throw new UnsupportedOperationException("TeacherPullVirtualTreeView.serializeInternal()");
    }

    @Override
    public void writeChildHashes(final Long parent, final SerializableDataOutputStream out) throws IOException {
        throw new UnsupportedOperationException("TeacherPullVirtualTreeView.writeChildHashes()");
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
            records.getDataSource().close();
        } catch (final IOException e) {
            logger.error(EXCEPTION.getMarker(), "interrupted while attempting to close data source");
        } catch (final InterruptedException e) {
            logger.error(EXCEPTION.getMarker(), "Failed to close data source properly", e);
            Thread.currentThread().interrupt();
        }
    }
}
