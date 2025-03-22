// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.synchronization.views;

import com.swirlds.base.time.Time;
import com.swirlds.common.crypto.Cryptography;
import com.swirlds.common.io.streams.MerkleDataInputStream;
import com.swirlds.common.io.streams.MerkleDataOutputStream;
import com.swirlds.common.merkle.MerkleInternal;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.synchronization.TeachingSynchronizer;
import com.swirlds.common.merkle.synchronization.config.ReconnectConfig;
import com.swirlds.common.merkle.synchronization.streams.AsyncInputStream;
import com.swirlds.common.merkle.synchronization.streams.AsyncOutputStream;
import com.swirlds.common.merkle.synchronization.task.Lesson;
import com.swirlds.common.merkle.synchronization.task.NodeToSend;
import com.swirlds.common.merkle.synchronization.task.QueryResponse;
import com.swirlds.common.merkle.synchronization.task.TeacherPushReceiveTask;
import com.swirlds.common.merkle.synchronization.task.TeacherPushSendTask;
import com.swirlds.common.merkle.synchronization.task.TeacherSubtree;
import com.swirlds.common.merkle.synchronization.utility.MerkleSynchronizationException;
import com.swirlds.common.threading.pool.StandardWorkGroup;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import org.hiero.consensus.model.crypto.Hash;
import org.hiero.consensus.model.io.streams.SerializableDataOutputStream;

/**
 * A teaching tree view for a standard in memory merkle tree.
 */
public class TeacherPushMerkleTreeView implements TeacherTreeView<NodeToSend> {
    private final ReconnectConfig reconnectConfig;

    private final Queue<NodeToSend> nodesToHandle;
    private final BlockingQueue<NodeToSend> expectedResponses;

    private final NodeToSend root;

    private final int maxAckDelayMilliseconds;

    /**
     * Create a view for a standard merkle tree.
     *
     * @param configuration the configuration
     * @param root          the root of the tree
     */
    public TeacherPushMerkleTreeView(@NonNull final Configuration configuration, final MerkleNode root) {
        this.reconnectConfig = configuration.getConfigData(ReconnectConfig.class);
        maxAckDelayMilliseconds = (int) reconnectConfig.maxAckDelay().toMillis();

        this.root = new NodeToSend(root, maxAckDelayMilliseconds);

        nodesToHandle = new LinkedList<>();
        expectedResponses = new LinkedBlockingDeque<>();
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
        final AsyncOutputStream<Lesson<NodeToSend>> out =
                teachingSynchronizer.buildOutputStream(workGroup, outputStream);

        in.start();
        out.start();

        final AtomicBoolean senderIsFinished = new AtomicBoolean(false);

        final TeacherPushSendTask<NodeToSend> teacherPushSendTask =
                new TeacherPushSendTask<>(time, reconnectConfig, workGroup, in, out, subtrees, this, senderIsFinished);
        teacherPushSendTask.start();
        final TeacherPushReceiveTask<NodeToSend> teacherPushReceiveTask =
                new TeacherPushReceiveTask<>(workGroup, in, this, senderIsFinished);
        teacherPushReceiveTask.start();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public NodeToSend getRoot() {
        return root;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addToHandleQueue(final NodeToSend node) {
        nodesToHandle.add(node);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public NodeToSend getNextNodeToHandle() {
        return nodesToHandle.remove();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean areThereNodesToHandle() {
        return !nodesToHandle.isEmpty();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public NodeToSend getChildAndPrepareForQueryResponse(final NodeToSend parent, final int childIndex) {
        final NodeToSend child =
                new NodeToSend(parent.getNode().asInternal().getChild(childIndex), maxAckDelayMilliseconds);
        parent.registerChild(child);

        if (!expectedResponses.add(child)) {
            throw new MerkleSynchronizationException("unable to add expected response to queue");
        }

        return child;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public NodeToSend getNodeForNextResponse() {
        return expectedResponses.remove();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isResponseExpected() {
        return !expectedResponses.isEmpty();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void registerResponseForNode(final NodeToSend node, final boolean learnerHasNode) {
        node.registerResponse(learnerHasNode);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean hasLearnerConfirmedFor(final NodeToSend node) {
        node.waitForResponse();
        return node.getResponseStatus();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isInternal(final NodeToSend node, final boolean isOriginal) {
        // This implementation can safely ignore "isOriginal"
        final MerkleNode merkleNode = node.getNode();
        return merkleNode != null && !merkleNode.isLeaf();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getClassId(final NodeToSend node) {
        final MerkleNode merkleNode = node.getNode();

        if (merkleNode == null) {
            throw new MerkleSynchronizationException("null has no class ID");
        }

        return merkleNode.getClassId();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MerkleNode getMerkleRoot(final NodeToSend node) {
        return node.getNode();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getNumberOfChildren(final NodeToSend node) {
        final MerkleNode merkleNode = node.getNode();

        if (merkleNode == null || merkleNode.isLeaf()) {
            throw new MerkleSynchronizationException("can not get number of children from node that is not internal");
        }

        return merkleNode.asInternal().getNumberOfChildren();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isCustomReconnectRoot(final NodeToSend node) {
        final MerkleNode merkleNode = node.getNode();
        return merkleNode != null && merkleNode.hasCustomReconnectView();
    }

    private List<Hash> getChildHashes(final NodeToSend parent) {

        final MerkleNode node = parent.getNode();

        if (node == null || node.isLeaf()) {
            throw new MerkleSynchronizationException("can not get child hashes of null value");
        }
        if (node.isLeaf()) {
            throw new MerkleSynchronizationException("can not get child hashes of leaf");
        }

        final MerkleInternal internal = node.asInternal();

        final int childCount = internal.getNumberOfChildren();
        final List<Hash> hashes = new ArrayList<>(childCount);

        for (int childIndex = 0; childIndex < childCount; childIndex++) {
            final MerkleNode child = internal.getChild(childIndex);

            if (child == null) {
                hashes.add(Cryptography.NULL_HASH);
            } else {
                final Hash hash = child.getHash();
                if (hash == null) {
                    throw new MerkleSynchronizationException(
                            child.getClass().getName() + " at position " + child.getRoute() + " is unhashed");
                }

                hashes.add(child.getHash());
            }
        }

        return hashes;
    }

    @Override
    public void writeChildHashes(final NodeToSend parent, final SerializableDataOutputStream out) throws IOException {
        out.writeSerializableList(getChildHashes(parent), false, true);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void serializeLeaf(final SerializableDataOutputStream out, final NodeToSend leaf) throws IOException {
        final MerkleNode merkleNode = leaf.getNode();

        if (merkleNode == null) {
            out.writeSerializable(null, true);
            return;
        }

        if (!merkleNode.isLeaf()) {
            throw new MerkleSynchronizationException("this method can not serialize an internal node");
        }

        out.writeSerializable(merkleNode.asLeaf(), true);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void serializeInternal(final SerializableDataOutputStream out, final NodeToSend internal)
            throws IOException {
        final MerkleNode merkleNode = internal.getNode();

        if (merkleNode == null || merkleNode.isLeaf()) {
            throw new MerkleSynchronizationException("this method can not serialize a leaf node");
        }

        out.writeLong(merkleNode.getClassId());
    }
}
