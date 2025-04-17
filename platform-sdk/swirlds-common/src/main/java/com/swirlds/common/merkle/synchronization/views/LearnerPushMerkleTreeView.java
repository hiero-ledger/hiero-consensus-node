// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.synchronization.views;

import static org.hiero.base.constructable.ClassIdFormatter.classIdString;

import com.swirlds.common.io.streams.MerkleDataInputStream;
import com.swirlds.common.io.streams.MerkleDataOutputStream;
import com.swirlds.common.merkle.MerkleInternal;
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
import com.swirlds.common.threading.pool.StandardWorkGroup;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicReference;
import org.hiero.base.constructable.ConstructableRegistry;
import org.hiero.base.crypto.Cryptography;
import org.hiero.base.crypto.Hash;
import org.hiero.base.io.streams.SerializableDataInputStream;

/**
 * Implementation for a view of a standard in memory merkle tree.
 */
public class LearnerPushMerkleTreeView implements LearnerTreeView<MerkleNode> {
    private final ReconnectConfig reconnectConfig;

    private final MerkleNode originalRoot;

    private AsyncInputStream<Lesson<MerkleNode>> in;
    private AsyncOutputStream<QueryResponse> out;

    private final Queue<ExpectedLesson<MerkleNode>> expectedLessons;
    private final LinkedList<MerkleInternal> nodesToInitialize;

    private final ReconnectMapStats mapStats;

    /**
     * Create a new standard tree view out of an in-memory merkle tree (or subtree).
     *
     * @param root
     * 		the root of the tree (or subtree)
     * @param mapStats
     *      a ReconnectMapStats object to collect reconnect metrics
     */
    public LearnerPushMerkleTreeView(
            final ReconnectConfig reconnectConfig, final MerkleNode root, @NonNull final ReconnectMapStats mapStats) {
        this.reconnectConfig = reconnectConfig;
        this.originalRoot = root;
        this.mapStats = mapStats;
        expectedLessons = new LinkedList<>();
        nodesToInitialize = new LinkedList<>();
    }

    @Override
    public void startLearnerTasks(
            final LearningSynchronizer learningSynchronizer,
            final StandardWorkGroup workGroup,
            final MerkleDataInputStream inputStream,
            final MerkleDataOutputStream outputStream,
            final Queue<MerkleNode> rootsToReceive,
            final AtomicReference<MerkleNode> reconstructedRoot) {
        in = new AsyncInputStream<>(inputStream, workGroup, () -> new Lesson<>(this), reconnectConfig);
        out = learningSynchronizer.buildOutputStream(workGroup, outputStream);

        in.start();
        out.start();

        final LearnerPushTask<MerkleNode> learnerThread = new LearnerPushTask<>(
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
        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MerkleNode getOriginalRoot() {
        return originalRoot;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isInternal(final MerkleNode node, final boolean isOriginal) {
        // This implementation can safely ignore "isOriginal"
        return node != null && !node.isLeaf();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getNumberOfChildren(final MerkleNode node) {
        if (node == null || node.isLeaf()) {
            throw new MerkleSynchronizationException("only internal nodes have a child count");
        }

        return node.asInternal().getNumberOfChildren();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Hash getNodeHash(final MerkleNode node) {
        if (node == null) {
            return Cryptography.NULL_HASH;
        } else {
            return node.getHash();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getClassId(final MerkleNode node) {
        if (node == null) {
            throw new MerkleSynchronizationException("null does not have a class ID");
        }

        return node.getClassId();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MerkleNode convertMerkleRootToViewType(final MerkleNode node) {
        return node;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MerkleNode getMerkleRoot(final MerkleNode node) {
        return node;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MerkleNode getChild(final MerkleNode parent, final int childIndex) {
        if (parent == null || parent.isLeaf()) {
            throw new MerkleSynchronizationException("can not get child of leaf node");
        }
        return parent.asInternal().getChild(childIndex);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setChild(final MerkleNode parent, final int childIndex, final MerkleNode child) {
        parent.asInternal().setChild(childIndex, child);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void expectLessonFor(
            final MerkleNode parent,
            final int childIndex,
            final MerkleNode original,
            final boolean nodeAlreadyPresent) {
        expectedLessons.add(new ExpectedLesson<>(parent, childIndex, original, nodeAlreadyPresent));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ExpectedLesson<MerkleNode> getNextExpectedLesson() {
        return expectedLessons.remove();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean hasNextExpectedLesson() {
        return !expectedLessons.isEmpty();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MerkleNode deserializeLeaf(final SerializableDataInputStream in) throws IOException {
        return in.readSerializable();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MerkleNode deserializeInternal(final SerializableDataInputStream in) throws IOException {

        final long classId = in.readLong();

        final MerkleInternal internal = ConstructableRegistry.getInstance().createObject(classId);
        if (internal == null) {
            throw new MerkleSynchronizationException(
                    "unable to construct object with class ID " + classIdString(classId));
        }

        return internal;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void markForInitialization(final MerkleNode node) {
        nodesToInitialize.add(node.asInternal());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void initialize() {
        // Internal nodes are encountered top down, but need to be initialized bottom up.
        final Iterator<MerkleInternal> iterator = nodesToInitialize.descendingIterator();

        while (iterator.hasNext()) {
            iterator.next().rebuild();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void releaseNode(final MerkleNode node) {
        if (node != null) {
            node.release();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void recordHashStats(
            @NonNull final ReconnectMapStats mapStats,
            @NonNull final MerkleNode parent,
            final int childIndex,
            final boolean nodeAlreadyPresent) {
        if (!parent.isInternal()) {
            throw new IllegalArgumentException("parent is not an internal node: " + parent);
        }
        final MerkleNode child = parent.asInternal().getChild(childIndex);
        // The child may be missing per the getChild() specification,
        // and this method cannot reason about a `null`, so just bail.
        if (child == null) return;

        if (child.isLeaf()) {
            mapStats.incrementLeafHashes(1, nodeAlreadyPresent ? 1 : 0);
        } else {
            mapStats.incrementInternalHashes(1, nodeAlreadyPresent ? 1 : 0);
        }
    }
}
