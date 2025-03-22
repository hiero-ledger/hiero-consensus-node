// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtual.merkle.reconnect;

import com.swirlds.base.time.Time;
import com.swirlds.common.io.streams.MerkleDataInputStream;
import com.swirlds.common.io.streams.MerkleDataOutputStream;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.synchronization.TeachingSynchronizer;
import com.swirlds.common.merkle.synchronization.task.TeacherSubtree;
import com.swirlds.common.merkle.synchronization.views.TeacherTreeView;
import com.swirlds.common.threading.pool.StandardWorkGroup;
import java.io.IOException;
import java.util.Queue;
import org.hiero.consensus.model.io.streams.SerializableDataOutputStream;

/**
 * An intentionally broken teacher tree view. Throws an IO exception after a certain number of nodes have been
 * serialized.
 */
public class BrokenVirtualMapTeacherView implements TeacherTreeView<Long> {

    private final TeacherTreeView<Long> baseView;
    private final int permittedInternals;
    private final int permittedLeaves;
    private int leafCount;
    private int internalCount;

    /**
     * Create a view that is intentionally broken.
     *
     * @param baseView
     * 		a functional view for a virtual map
     * @param permittedInternals
     * 		the number of internal nodes to allow to be serialized, if to many are encountered an
     * 		IO exception is thrown.
     * @param permittedLeaves
     * 		the number of leaf nodes to allow to be serialized, if to many are encountered an
     * 		IO exception is thrown
     */
    public BrokenVirtualMapTeacherView(
            final TeacherTreeView<Long> baseView, final int permittedInternals, final int permittedLeaves) {
        this.baseView = baseView;
        this.permittedInternals = permittedInternals;
        this.permittedLeaves = permittedLeaves;
    }

    @Override
    public void startTeacherTasks(
            final TeachingSynchronizer teachingSynchronizer,
            final Time time,
            final StandardWorkGroup workGroup,
            final MerkleDataInputStream inputStream,
            final MerkleDataOutputStream outputStream,
            final Queue<TeacherSubtree> subtrees) {
        baseView.startTeacherTasks(teachingSynchronizer, time, workGroup, inputStream, outputStream, subtrees);
    }

    @Override
    public void addToHandleQueue(final Long node) {
        baseView.addToHandleQueue(node);
    }

    @Override
    public Long getNextNodeToHandle() {
        return baseView.getNextNodeToHandle();
    }

    @Override
    public boolean areThereNodesToHandle() {
        return baseView.areThereNodesToHandle();
    }

    @Override
    public Long getChildAndPrepareForQueryResponse(final Long parent, final int childIndex) {
        return baseView.getChildAndPrepareForQueryResponse(parent, childIndex);
    }

    @Override
    public Long getNodeForNextResponse() {
        return baseView.getNodeForNextResponse();
    }

    @Override
    public boolean isResponseExpected() {
        return baseView.isResponseExpected();
    }

    @Override
    public void registerResponseForNode(final Long node, final boolean learnerHasNode) {
        baseView.registerResponseForNode(node, learnerHasNode);
    }

    @Override
    public boolean hasLearnerConfirmedFor(final Long node) {
        return baseView.hasLearnerConfirmedFor(node);
    }

    @Override
    public Long getRoot() {
        return baseView.getRoot();
    }

    @Override
    public void serializeLeaf(final SerializableDataOutputStream out, final Long leaf) throws IOException {
        leafCount++;
        if (leafCount > permittedLeaves) {
            throw new IOException("intentionally throwing during leaf serialization");
        }

        baseView.serializeLeaf(out, leaf);
    }

    @Override
    public void serializeInternal(final SerializableDataOutputStream out, final Long internal) throws IOException {
        internalCount++;
        if (internalCount > permittedInternals) {
            throw new IOException("intentionally throwing during internal serialization");
        }

        baseView.serializeInternal(out, internal);
    }

    @Override
    public void writeChildHashes(final Long parent, final SerializableDataOutputStream out) throws IOException {
        baseView.writeChildHashes(parent, out);
    }

    @Override
    public boolean isCustomReconnectRoot(final Long node) {
        return baseView.isCustomReconnectRoot(node);
    }

    @Override
    public boolean isInternal(final Long node, final boolean isOriginal) {
        return baseView.isInternal(node, isOriginal);
    }

    @Override
    public int getNumberOfChildren(final Long node) {
        return baseView.getNumberOfChildren(node);
    }

    @Override
    public long getClassId(final Long node) {
        return baseView.getClassId(node);
    }

    @Override
    public MerkleNode getMerkleRoot(final Long node) {
        return baseView.getMerkleRoot(node);
    }
}
