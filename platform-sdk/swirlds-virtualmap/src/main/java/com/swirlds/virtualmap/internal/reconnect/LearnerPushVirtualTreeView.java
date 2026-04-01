// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.reconnect;

import static com.swirlds.virtualmap.internal.Path.ROOT_PATH;
import static com.swirlds.virtualmap.internal.Path.getChildPath;
import static com.swirlds.virtualmap.internal.Path.getParentPath;
import static com.swirlds.virtualmap.internal.Path.isLeft;
import static java.util.Objects.requireNonNull;

import com.swirlds.common.merkle.synchronization.stats.ReconnectMapStats;
import com.swirlds.common.merkle.synchronization.streams.AsyncInputStream;
import com.swirlds.common.merkle.synchronization.streams.AsyncOutputStream;
import com.swirlds.common.merkle.synchronization.task.ExpectedLesson;
import com.swirlds.common.merkle.synchronization.task.LearnerPushTask;
import com.swirlds.common.merkle.synchronization.utility.MerkleSynchronizationException;
import com.swirlds.common.merkle.synchronization.views.LearnerTreeView;
import com.swirlds.virtualmap.VirtualMapLearner;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import com.swirlds.virtualmap.internal.Path;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import org.hiero.base.crypto.Cryptography;
import org.hiero.base.crypto.Hash;
import org.hiero.base.io.streams.SerializableDataInputStream;
import org.hiero.consensus.concurrent.pool.StandardWorkGroup;

/**
 * An implementation of {@link LearnerTreeView} for the virtual merkle. The learner during reconnect
 * needs access both to the original state and records, and the current reconnect state and records.
 * This implementation uses {@link Long} as the representation of a node and corresponds directly
 * to the path of the node.
 */
public final class LearnerPushVirtualTreeView extends VirtualTreeViewBase implements LearnerTreeView {

    /**
     * Some reasonable default initial capacity for the {@link BooleanBitSetQueue}s used for
     * storing {@link ExpectedLesson} data. If the value is too large, we use some more memory
     * than needed, if it is too small, we put pressure on the GC.
     */
    private static final int EXPECTED_BIT_SET_INITIAL_CAPACITY = 1024 * 1024;

    /**
     * The reconnect helper that manages hashing and lifecycle for this learner reconnect operation.
     */
    private final VirtualMapLearner vmapLearner;

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

    private final ReconnectMapStats mapStats;

    /**
     * Create a new {@link LearnerPushVirtualTreeView}.
     *
     * @param vmapLearner
     * 		The reconnect helper managing this learner reconnect operation. Cannot be null.
     * @param mapStats
     *      A ReconnectMapStats object to collect reconnect metrics
     */
    public LearnerPushVirtualTreeView(
            @NonNull final VirtualMapLearner vmapLearner, @NonNull final ReconnectMapStats mapStats) {
        super(vmapLearner.getOriginalState(), vmapLearner.getReconnectState());
        this.vmapLearner = requireNonNull(vmapLearner);
        this.mapStats = requireNonNull(mapStats);
    }

    @Override
    public void startLearnerTasks(
            final StandardWorkGroup workGroup, final AsyncInputStream in, final AsyncOutputStream out) {
        final LearnerPushTask learnerThread = new LearnerPushTask(workGroup, in, out, this, mapStats);
        learnerThread.start();
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
        final Hash hash = vmapLearner.findHash(originalChild);

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
            final Long parentPath, final int childIndex, final Long originalPath, final boolean nodeAlreadyPresent) {
        expectedChildren.add(parentPath == null ? 0 : getChildPath(parentPath, childIndex));
        expectedNodeAlreadyPresent.add(nodeAlreadyPresent);
        expectedOriginalExists.add(originalPath != null);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ExpectedLesson getNextExpectedLesson() {
        final long child = expectedChildren.remove();
        final long parent = getParentPath(child);
        final int index = isLeft(child) ? 0 : 1;
        final Long original = expectedOriginalExists.remove() ? child : null;
        final boolean nodeAlreadyPresent = expectedNodeAlreadyPresent.remove();
        return new ExpectedLesson(parent < 0 ? null : parent, index, original, nodeAlreadyPresent);
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
        final VirtualLeafBytes<?> leaf = VirtualReconnectUtils.readLeafRecord(in);
        vmapLearner.onLeaf(leaf); // may block if hashing is slower than ingest
        return leaf.path();
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
            // the info has already been sent and resides in the ExternalVirtualMapMetadata that is a sibling
            // of the VirtualMap. This doesn't affect correctness or hashing.
            final long firstLeafPath = in.readLong();
            final long lastLeafPath = in.readLong();
            vmapLearner.init(firstLeafPath, lastLeafPath, null);
        }
        return node;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() {
        vmapLearner.onEnd();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setChild(final Long parent, final int childIndex, final Long child) {
        // No-op
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
