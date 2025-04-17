// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.reconnect;

import static com.swirlds.logging.legacy.LogMarker.RECONNECT;
import static com.swirlds.virtualmap.internal.Path.ROOT_PATH;
import static com.swirlds.virtualmap.internal.Path.getChildPath;
import static com.swirlds.virtualmap.internal.Path.getParentPath;
import static com.swirlds.virtualmap.internal.Path.isLeft;

import com.swirlds.common.io.streams.MerkleDataInputStream;
import com.swirlds.common.io.streams.MerkleDataOutputStream;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.synchronization.LearningSynchronizer;
import com.swirlds.common.merkle.synchronization.config.ReconnectConfig;
import com.swirlds.common.merkle.synchronization.stats.ReconnectMapStats;
import com.swirlds.common.merkle.synchronization.streams.AsyncInputStream;
import com.swirlds.common.merkle.synchronization.streams.AsyncOutputStream;
import com.swirlds.common.merkle.synchronization.task.ExpectedLesson;
import com.swirlds.common.merkle.synchronization.task.LearnerPushTask;
import com.swirlds.common.merkle.synchronization.task.Lesson;
import com.swirlds.common.merkle.synchronization.task.QueryResponse;
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
import java.util.concurrent.atomic.AtomicReference;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.Cryptography;
import org.hiero.base.crypto.Hash;
import org.hiero.base.io.streams.SerializableDataInputStream;

/**
 * An implementation of {@link LearnerTreeView} for the virtual merkle. The learner during reconnect
 * needs access both to the original state and records, and the current reconnect state and records.
 * This implementation uses {@link Long} as the representation of a node and corresponds directly
 * to the path of the node.
 *
 * @param <K>
 * 		The key
 * @param <V>
 * 		The value
 */
public final class LearnerPushVirtualTreeView<K extends VirtualKey, V extends VirtualValue>
        extends VirtualTreeViewBase<K, V> implements LearnerTreeView<Long> {

    private static final Logger logger = LogManager.getLogger(LearnerPushVirtualTreeView.class);

    /**
     * Some reasonable default initial capacity for the {@link BooleanBitSetQueue}s used for
     * storing {@link ExpectedLesson} data. If the value is too large, we use some more memory
     * than needed, if it is too small, we put pressure on the GC.
     */
    private static final int EXPECTED_BIT_SET_INITIAL_CAPACITY = 1024 * 1024;

    private final ReconnectConfig reconnectConfig;

    private AsyncInputStream<Lesson<Long>> in;

    /**
     * Handles removal of old nodes.
     */
    private final ReconnectNodeRemover<K, V> nodeRemover;

    /**
     * As part of tracking {@link ExpectedLesson}s, this keeps track of the "nodeAlreadyPresent" boolean.
     */
    private final BooleanBitSetQueue expectedNodeAlreadyPresent =
            new BooleanBitSetQueue(EXPECTED_BIT_SET_INITIAL_CAPACITY);

    /**
     * As part of tracking {@link ExpectedLesson}s, this keeps track of the combination of the
     * parent and index of the lesson.
     */
    private final ConcurrentBitSetQueue expectedChildren = new ConcurrentBitSetQueue();

    /**
     * As part of tracking {@link ExpectedLesson}s, this keeps track of the "original" long.
     */
    private final BooleanBitSetQueue expectedOriginalExists = new BooleanBitSetQueue(EXPECTED_BIT_SET_INITIAL_CAPACITY);

    /**
     * A {@link RecordAccessor} for getting access to the original records.
     */
    private final RecordAccessor<K, V> originalRecords;

    private final ReconnectMapStats mapStats;

    /**
     * Create a new {@link LearnerPushVirtualTreeView}.
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
    public LearnerPushVirtualTreeView(
            final ReconnectConfig reconnectConfig,
            final VirtualRootNode<K, V> root,
            final RecordAccessor<K, V> originalRecords,
            final VirtualStateAccessor originalState,
            final VirtualStateAccessor reconnectState,
            final ReconnectNodeRemover<K, V> nodeRemover,
            @NonNull final ReconnectMapStats mapStats) {
        super(root, originalState, reconnectState);
        this.reconnectConfig = reconnectConfig;
        this.originalRecords = Objects.requireNonNull(originalRecords);
        this.nodeRemover = nodeRemover;
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
        in = new AsyncInputStream<>(inputStream, workGroup, () -> new Lesson<>(this), reconnectConfig);
        in.start();
        final AsyncOutputStream<QueryResponse> out = learningSynchronizer.buildOutputStream(workGroup, outputStream);
        out.start();

        final LearnerPushTask<Long> learnerThread = new LearnerPushTask<>(
                workGroup, in, out, rootsToReceive, reconstructedRoot, this, learningSynchronizer, mapStats);
        learnerThread.start();
    }

    @Override
    public void abort() {
        in.abort();
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
        // This method is only called on the Learner. The path given is the _ORIGINAL_ child. Each call to this
        // method will be made only for the original state from the original tree.

        // If the originalChild is null, then it means we're outside the range of valid nodes, and we will
        // return a NULL_HASH.
        if (originalChild == null) {
            return Cryptography.NULL_HASH;
        }

        // Make sure the path is valid for the original state
        checkValidNode(originalChild, originalState);
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
        expectedChildren.add(parent == null ? 0 : getChildPath(parent, childIndex));
        expectedNodeAlreadyPresent.add(nodeAlreadyPresent);
        expectedOriginalExists.add(original != null);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ExpectedLesson<Long> getNextExpectedLesson() {
        final long child = expectedChildren.remove();
        final long parent = getParentPath(child);
        final int index = isLeft(child) ? 0 : 1;
        final Long original = expectedOriginalExists.remove() ? child : null;
        final boolean nodeAlreadyPresent = expectedNodeAlreadyPresent.remove();
        return new ExpectedLesson<>(parent, index, original, nodeAlreadyPresent);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean hasNextExpectedLesson() {
        assert expectedOriginalExists.isEmpty() == expectedChildren.isEmpty()
                        && expectedChildren.isEmpty() == expectedNodeAlreadyPresent.isEmpty()
                : "All three should match";

        return !expectedOriginalExists.isEmpty();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Long deserializeLeaf(final SerializableDataInputStream in) throws IOException {
        final VirtualLeafRecord<K, V> leaf = in.readSerializable(false, VirtualLeafRecord::new);
        nodeRemover.newLeafNode(leaf.getPath(), leaf.getKey());
        root.handleReconnectLeaf(leaf); // may block if hashing is slower than ingest
        return leaf.getPath();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Long deserializeInternal(final SerializableDataInputStream in) throws IOException {
        // We don't actually do anything useful with this deserialized long, other than return it.
        // Note: We may be able to omit this, but it requires some rework. See #4136
        final long node = in.readLong();
        if (node == ROOT_PATH) {
            // We send the first and last leaf path when reconnecting because we don't have access
            // to this information in the virtual root node at this point in the flow, even though
            // the info has already been sent and resides in the VirtualMapState that is a sibling
            // of the VirtualRootNode. This doesn't affect correctness or hashing.
            final long firstLeafPath = in.readLong();
            final long lastLeafPath = in.readLong();
            reconnectState.setFirstLeafPath(firstLeafPath);
            reconnectState.setLastLeafPath(lastLeafPath);
            root.prepareReconnectHashing(firstLeafPath, lastLeafPath);
            nodeRemover.setPathInformation(firstLeafPath, lastLeafPath);
        }
        return node;
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
        logger.info(RECONNECT.getMarker(), "call nodeRemover.allNodesReceived()");
        nodeRemover.allNodesReceived();
        logger.info(RECONNECT.getMarker(), "call root.endLearnerReconnect()");
        root.endLearnerReconnect();
        logger.info(RECONNECT.getMarker(), "close() complete");
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

    private boolean isLeaf(final long path) {
        return path >= reconnectState.getFirstLeafPath() && path <= reconnectState.getLastLeafPath();
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
        final long childPath = Path.getChildPath(parent, childIndex);
        if (isLeaf(childPath)) {
            mapStats.incrementLeafHashes(1, nodeAlreadyPresent ? 1 : 0);
        } else {
            mapStats.incrementInternalHashes(1, nodeAlreadyPresent ? 1 : 0);
        }
    }
}
