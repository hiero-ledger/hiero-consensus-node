// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.hash;

import static org.hiero.base.crypto.Cryptography.DEFAULT_SET_HASH;

import com.swirlds.common.merkle.MerkleInternal;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.crypto.MerkleCryptography;
import java.util.Iterator;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import org.hiero.base.concurrent.AbstractTask;
import org.hiero.base.concurrent.futures.StandardFuture;
import org.hiero.base.crypto.Cryptography;
import org.hiero.base.crypto.Hash;

/**
 * This class is responsible for hashing a merkle tree.
 */
public class MerkleHashBuilder {

    private final ForkJoinPool threadPool;

    private final MerkleCryptography merkleCryptography;

    /**
     * Construct an object which calculates the hash of a merkle tree.
     *
     * @param cpuThreadCount the number of threads to be used for computing hash
     */
    public MerkleHashBuilder(final MerkleCryptography merkleCryptography, final int cpuThreadCount) {
        this.merkleCryptography = merkleCryptography;
        this.threadPool = new ForkJoinPool(cpuThreadCount);
    }

    /**
     * Only return nodes that require a hash.
     */
    private static boolean filter(MerkleNode node) {
        if (node == null) {
            return false;
        }

        if (node.isSelfHashing()) {
            return true;
        }

        return node.getHash() == null;
    }

    /**
     * Don't bother with subtrees that have already been hashed.
     */
    private static boolean descendantFilter(final MerkleNode child) {
        if (child.isSelfHashing()) {
            return false;
        }

        return child.getHash() == null;
    }

    /**
     * Compute the hash of the merkle tree synchronously on the caller's thread.
     *
     * @param root
     * 		the root of the tree to hash
     * @return The hash of the tree.
     */
    public Hash digestTreeSync(MerkleNode root) {
        if (root == null) {
            return Cryptography.NULL_HASH;
        }

        final Iterator<MerkleNode> iterator = root.treeIterator()
                .setFilter(MerkleHashBuilder::filter)
                .setDescendantFilter(MerkleHashBuilder::descendantFilter);
        hashSubtree(iterator);
        return root.getHash();
    }

    /**
     * Compute the hash of the merkle tree on multiple worker threads.
     *
     * @param root
     * 		the root of the tree to hash
     * @return a Future which encapsulates the hash of the merkle tree
     */
    public Future<Hash> digestTreeAsync(MerkleNode root) {
        if (root == null) {
            return new StandardFuture<>(Cryptography.NULL_HASH);
        } else if (root.getHash() != null) {
            return new StandardFuture<>(root.getHash());
        } else {
            FutureMerkleHash resultFuture = new FutureMerkleHash();
            ResultTask resultTask = new ResultTask(root, resultFuture);
            new TraverseTask(root, resultTask).send();
            return resultFuture;
        }
    }

    /**
     * The root of a merkle tree.
     *
     * @param it
     * 		An iterator that walks through the tree.
     */
    private void hashSubtree(final Iterator<MerkleNode> it) {
        while (it.hasNext()) {
            final MerkleNode node = it.next();

            if (node.getHash() != null) {
                continue;
            }

            merkleCryptography.digestSync(node, Cryptography.DEFAULT_DIGEST_TYPE);
        }
    }

    /**
     * TraverseTask processes the current node in the tree and either hashes it or adds
     * to a parallel structure of ComputeTasks and creates TraverseTasks for its children.
     */
    class TraverseTask extends AbstractTask {
        final MerkleNode node;
        final AbstractTask out;

        TraverseTask(final MerkleNode node, AbstractTask out) {
            super(threadPool, 1);
            this.node = node;
            this.out = out;
        }

        @Override
        protected boolean onExecute() {
            if (node == null || node.getHash() != null) {
                out.send();
            } else if (node.isLeaf()) {
                merkleCryptography.digestSync(node.asLeaf());
                out.send();
            } else {
                MerkleInternal internal = node.asInternal();
                int nChildren = internal.getNumberOfChildren();
                if (nChildren == 0) {
                    merkleCryptography.digestSync(internal, DEFAULT_SET_HASH);
                    out.send();
                } else {
                    ComputeTask compute = new ComputeTask(internal, nChildren, out);
                    for (int childIndex = 0; childIndex < nChildren; childIndex++) {
                        MerkleNode child = internal.getChild(childIndex);
                        new TraverseTask(child, compute).send();
                    }
                }
            }
            return true;
        }

        @Override
        protected void onException(Throwable t) {
            if (!out.isCompletedAbnormally()) {
                out.completeExceptionally(t);
            }
        }
    }

    /**
     * ComputeTask computes internal node's hash once all its children have their hashes computed.
     */
    class ComputeTask extends AbstractTask {
        final MerkleInternal internal;
        final AbstractTask out;

        ComputeTask(final MerkleInternal internal, final int n, final AbstractTask out) {
            super(threadPool, n);
            this.internal = internal;
            this.out = out;
        }

        @Override
        protected boolean onExecute() {
            merkleCryptography.digestSync(internal, DEFAULT_SET_HASH);
            out.send();
            return true;
        }

        @Override
        public void onException(Throwable ex) {
            // Try to reduce exception propagation; OK if multiple exceptions reported to out
            if (!out.isCompletedAbnormally()) {
                out.completeExceptionally(ex);
            }
        }
    }

    /**
     * ResultTask connects asynchronous parallel execution to FutureMerkleHash.
     */
    class ResultTask extends AbstractTask {
        final FutureMerkleHash future;
        final MerkleNode root;

        ResultTask(final MerkleNode root, final FutureMerkleHash future) {
            super(threadPool, 1);
            this.root = root;
            this.future = future;
        }

        @Override
        protected boolean onExecute() {
            future.set(root.getHash());
            return true;
        }

        @Override
        public void onException(Throwable ex) {
            if (!future.isCancelled()) {
                future.cancelWithException(ex);
            }
        }
    }
}
