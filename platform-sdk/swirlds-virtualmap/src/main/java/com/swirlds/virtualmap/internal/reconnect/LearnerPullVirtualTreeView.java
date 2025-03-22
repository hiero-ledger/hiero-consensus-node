// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.reconnect;

import static com.swirlds.virtualmap.internal.Path.ROOT_PATH;

import com.swirlds.common.crypto.Cryptography;
import com.swirlds.common.io.streams.MerkleDataInputStream;
import com.swirlds.common.io.streams.MerkleDataOutputStream;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.synchronization.LearningSynchronizer;
import com.swirlds.common.merkle.synchronization.config.ReconnectConfig;
import com.swirlds.common.merkle.synchronization.stats.ReconnectMapStats;
import com.swirlds.common.merkle.synchronization.streams.AsyncOutputStream;
import com.swirlds.common.merkle.synchronization.task.ExpectedLesson;
import com.swirlds.common.merkle.synchronization.task.ReconnectNodeCount;
import com.swirlds.common.merkle.synchronization.utility.MerkleSynchronizationException;
import com.swirlds.common.merkle.synchronization.views.LearnerTreeView;
import com.swirlds.common.threading.pool.StandardWorkGroup;
import com.swirlds.virtualmap.VirtualKey;
import com.swirlds.virtualmap.VirtualValue;
import com.swirlds.virtualmap.datasource.VirtualLeafRecord;
import com.swirlds.virtualmap.internal.Path;
import com.swirlds.virtualmap.internal.RecordAccessor;
import com.swirlds.virtualmap.internal.VirtualStateAccessor;
import com.swirlds.virtualmap.internal.merkle.VirtualRootNode;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.hiero.consensus.model.crypto.Hash;
import org.hiero.consensus.model.io.streams.SerializableDataInputStream;

/**
 * An implementation of {@link LearnerTreeView} for the virtual merkle. The learner during reconnect
 * needs access both to the original state and records, and the current reconnect state and records.
 * This implementation uses {@link Long} as the representation of a node and corresponds directly
 * to the path of the node.
 *
 * <p>This implementation is supposed to work with {@link TeacherPullVirtualTreeView} on the
 * teacher side.
 *
 * @param <K>
 * 		The key
 * @param <V>
 * 		The value
 */
public final class LearnerPullVirtualTreeView<K extends VirtualKey, V extends VirtualValue>
        extends VirtualTreeViewBase<K, V> implements LearnerTreeView<Long> {

    /**
     * Reconnect configuration.
     */
    private final ReconnectConfig reconnectConfig;

    /**
     * Handles removal of old nodes.
     */
    private final ReconnectNodeRemover<K, V> nodeRemover;

    /**
     * Received nodes statistics.
     */
    private ReconnectNodeCount nodeCount;

    /**
     * A {@link RecordAccessor} for getting access to the original records.
     */
    private final RecordAccessor<K, V> originalRecords;

    /**
     * Node traversal order. Defines the order in which node requests will be sent to the teacher.
     */
    private final NodeTraversalOrder traversalOrder;

    private final ReconnectMapStats mapStats;

    /**
     * Indicates if no responses from the teacher have been received yet. The very first response
     * must be for path 0 (root virtual node)
     */
    private boolean firstNodeResponse = true;

    /**
     * Create a new {@link LearnerPullVirtualTreeView}.
     *
     * @param root
     * 		The root node of the <strong>reconnect</strong> tree. Cannot be null.
     * @param originalRecords
     * 		A {@link RecordAccessor} for accessing records from the unmodified <strong>original</strong> tree.
     * 		Cannot be null.
     * @param originalState
     * 		A {@link VirtualStateAccessor} for accessing state (first and last paths) from the
     * 		unmodified <strong>original</strong> tree. Cannot be null.
     * @param reconnectState
     * 		A {@link VirtualStateAccessor} for accessing state (first and last paths) from the
     * 		modified <strong>reconnect</strong> tree. We only use first and last leaf path from this state.
     * 		Cannot be null.
     * @param mapStats
     *      A ReconnectMapStats object to collect reconnect metrics
     */
    public LearnerPullVirtualTreeView(
            final ReconnectConfig reconnectConfig,
            final VirtualRootNode<K, V> root,
            final RecordAccessor<K, V> originalRecords,
            final VirtualStateAccessor originalState,
            final VirtualStateAccessor reconnectState,
            final ReconnectNodeRemover<K, V> nodeRemover,
            final NodeTraversalOrder traversalOrder,
            @NonNull final ReconnectMapStats mapStats) {
        super(root, originalState, reconnectState);
        this.reconnectConfig = reconnectConfig;
        this.originalRecords = Objects.requireNonNull(originalRecords);
        this.nodeRemover = nodeRemover;
        this.traversalOrder = traversalOrder;
        this.mapStats = mapStats;
    }

    @Override
    public void startLearnerTasks(
            final LearningSynchronizer learningSynchronizer,
            final StandardWorkGroup workGroup,
            final MerkleDataInputStream inputStream,
            final MerkleDataOutputStream outputStream,
            final Queue<MerkleNode> rootsToReceive,
            final AtomicReference<Long> reconstructedRoot) {
        this.nodeCount = learningSynchronizer;

        final AsyncOutputStream<PullVirtualTreeRequest> out =
                learningSynchronizer.buildOutputStream(workGroup, outputStream);
        out.start();

        final AtomicBoolean senderIsFinished = new AtomicBoolean();
        final CountDownLatch rootResponseReceived = new CountDownLatch(1);
        final AtomicLong expectedResponses = new AtomicLong(0);

        final LearnerPullVirtualTreeReceiveTask learnerReceiveTask = new LearnerPullVirtualTreeReceiveTask(
                workGroup, inputStream, this, senderIsFinished, expectedResponses, rootResponseReceived);
        learnerReceiveTask.exec();
        reconstructedRoot.set(0L);
        assert traversalOrder != null;
        final LearnerPullVirtualTreeSendTask learnerSendTask = new LearnerPullVirtualTreeSendTask(
                reconnectConfig,
                workGroup,
                out,
                this,
                traversalOrder,
                senderIsFinished,
                rootResponseReceived,
                expectedResponses);
        learnerSendTask.exec();
    }

    /**
     * Determines if a given path refers to a leaf of the tree.
     * @param path a path
     * @return true if leaf, false if internal
     */
    public boolean isLeaf(long path) {
        assert path <= reconnectState.getLastLeafPath();
        return path >= reconnectState.getFirstLeafPath();
    }

    /**
     * Reads a virtual node identified by a given path from the output stream. The node was previously
     * written by reconnect teacher. This method should match {@link
     * TeacherPullVirtualTreeView#writeNode(org.hiero.consensus.model.io.streams.SerializableDataOutputStream, long, boolean)}.
     *
     * <p>For a root node, reconnect state information is read: the first and the last leaf paths. Nothing
     * is read for other internal nodes.
     *
     * <p>For dirty leaf nodes, leaf records are read. Nothing is read for clean leaf nodes.
     *
     * @param in the input stream to read from
     * @param path the virtual path
     * @param isClean indicates that the node with the given path is the same on the learner and teacher
     * @throws IOException if an I/O error occurs
     */
    public void readNode(final SerializableDataInputStream in, final long path, final boolean isClean)
            throws IOException {
        if (path == Path.ROOT_PATH) {
            final long firstLeafPath = in.readLong();
            final long lastLeafPath = in.readLong();
            if (firstNodeResponse) {
                reconnectState.setFirstLeafPath(firstLeafPath);
                reconnectState.setLastLeafPath(lastLeafPath);
                root.prepareReconnectHashing(firstLeafPath, lastLeafPath);
                nodeRemover.setPathInformation(firstLeafPath, lastLeafPath);
                traversalOrder.start(firstLeafPath, lastLeafPath, nodeCount);
                firstNodeResponse = false;
                if (lastLeafPath <= 0) {
                    return;
                }
            }
        }
        assert !firstNodeResponse : "Root node must be the first node received from the teacher";
        final boolean isLeaf = isLeaf(path);
        traversalOrder.nodeReceived(path, isClean);

        if (isLeaf) {
            if (!isClean) {
                final VirtualLeafRecord<K, V> leaf = in.readSerializable(false, VirtualLeafRecord::new);
                mapStats.incrementLeafData(1, 0);
                assert path == leaf.getPath();
                nodeRemover.newLeafNode(path, leaf.getKey());
                root.handleReconnectLeaf(leaf); // may block if hashing is slower than ingest
            }
        }
    }

    /**
     * Returns the ReconnectMapStats object.
     * @return the ReconnectMapStats object.
     */
    @NonNull
    public ReconnectMapStats getMapStats() {
        return mapStats;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isRootOfState() {
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Long getOriginalRoot() {
        return ROOT_PATH;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Hash getNodeHash(final Long originalChild) {
        // The path given is the _ORIGINAL_ child. Each call to this
        // method will be made only for the original state from the original tree.

        // Make sure the path is valid for the original state
        if (originalChild > originalState.getLastLeafPath()) {
            return Cryptography.NULL_HASH;
        }

        final Hash hash = originalRecords.findHash(originalChild);
        // The hash must have been specified by this point. The original tree was hashed before
        // we started running on the learner, so either the hash is in cache or on disk, but it
        // definitely exists at this point. If it is null, something bad happened elsewhere.
        if (hash == null) {
            throw new MerkleSynchronizationException("Node found, but hash was null. path=" + originalChild);
        }
        return hash;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void expectLessonFor(
            final Long parent, final int childIndex, final Long original, final boolean nodeAlreadyPresent) {
        throw new UnsupportedOperationException("LearnerPullVirtualTreeView.expectLessonFor()");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ExpectedLesson<Long> getNextExpectedLesson() {
        throw new UnsupportedOperationException("LearnerPullVirtualTreeView.getNextExpectedLesson()");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean hasNextExpectedLesson() {
        throw new UnsupportedOperationException("LearnerPullVirtualTreeView.hasNextExpectedLesson()");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Long deserializeLeaf(final SerializableDataInputStream in) throws IOException {
        throw new UnsupportedOperationException("LearnerPullVirtualTreeView.deserializeLeaf()");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Long deserializeInternal(final SerializableDataInputStream in) throws IOException {
        throw new UnsupportedOperationException("LearnerPullVirtualTreeView.deserializeInternal()");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void initialize() {
        // no-op
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() {
        nodeRemover.allNodesReceived();
        root.endLearnerReconnect();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void markForInitialization(final Long node) {
        // no-op
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void releaseNode(final Long node) {
        // no-op
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setChild(final Long parent, final int childIndex, final Long child) {
        // No-op
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Long convertMerkleRootToViewType(final MerkleNode node) {
        throw new UnsupportedOperationException("Nested virtual maps not supported");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void recordHashStats(
            @NonNull final ReconnectMapStats mapStats,
            @NonNull final Long parent,
            final int childIndex,
            final boolean nodeAlreadyPresent) {
        throw new UnsupportedOperationException("The Reconnect Pull Model records the hash stats elsewhere");
    }
}
