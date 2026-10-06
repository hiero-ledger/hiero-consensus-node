// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap;

import static com.swirlds.virtualmap.MerklePathUtils.ROOT_PATH;
import static com.swirlds.virtualmap.MerklePathUtils.getLeftChildPath;
import static com.swirlds.virtualmap.MerklePathUtils.getRightChildPath;
import static com.swirlds.virtualmap.MerklePathUtils.isLeft;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeoutException;
import java.util.function.LongFunction;
import org.hiero.base.concurrent.AbstractTask;
import org.hiero.base.crypto.Cryptography;
import org.hiero.base.crypto.Hash;

/// A class that hashes a whole virtual tree from scratch using [AbstractTask]s, one
/// task per node.
///
/// There are two task types:
///
/// - A traverse task walks down the left-most path of a sub-tree in the current thread. For
///   every internal node on the path, it creates a hash task and sends a new traverse task for
///   the node's right child. When the walk reaches a leaf, the leaf is read and hashed in the
///   same thread. Right children tasks are forked to the current worker queue, so the worker
///   executes them deepest first, and leaves are read roughly in ascending path order. Other
///   workers steal the oldest tasks, which are the largest sub-trees
/// - A hash task for an internal node depends on its two children. Once both children hashes
///   are set, the node is hashed, and the hash is passed to the parent hash task
///
/// The depth-first order requires the pool to be in LIFO (non-async) mode. In async (FIFO) mode,
/// workers would execute their own tasks in breadth-first order, and the number of pending tasks,
/// as well as the number of hash chunks in progress, would grow linearly with the tree size.
/// Pools in async mode are rejected.
///
/// Every node hash is reported to a [Listener] **before** it's passed to the parent
/// task. This is important, because the listener may store hash chunks, and the parent task
/// may be executed in a different thread, so the listener must see the hash before the parent task is executed.
///
/// This class is stateless, a single instance may be used to hash multiple trees, even in
/// parallel.
public final class TaskPerNodeFullRehasher {

    @NonNull
    private final ForkJoinPool pool;

    /// Creates a new rehasher.
    ///
    /// @param pool the fork-join pool to run hashing tasks in. Must be in LIFO (non-async) mode,
    ///     see class javadoc for details
    /// @throws IllegalArgumentException if the pool is shut down, or if it's in async mode
    public TaskPerNodeFullRehasher(@NonNull final ForkJoinPool pool) {
        this.pool = requireNonNull(pool, "pool must not be null");
        if (pool.isShutdown() || pool.isTerminated()) {
            throw new IllegalArgumentException("pool must not be shutdown or terminated");
        }
        if (pool.getAsyncMode()) {
            throw new IllegalArgumentException(
                    "pool must not be in async mode, since tasks are executed in a depth-first order");
        }
    }

    /// Hashes the whole virtual tree with the given leaf path range and returns the root hash.
    ///
    /// The listener, if not `null`, is notified about every node in the tree, from hashing
    /// threads, possibly in parallel. If hashing fails, a [RuntimeException] is thrown. Its
    /// cause is the original exception thrown by the leaf reader or the listener, or a
    /// [TimeoutException] in case of a timeout, or an [InterruptedException] if the calling
    /// thread is interrupted. In the latter case, the thread interrupted flag is restored.
    /// After a failure or a timeout, remaining hashing tasks stop as soon as possible, and the
    /// listener isn't notified about any new hashes. Tasks that are already running at that
    /// moment aren't interrupted though, it's the caller responsibility to wait for them, if
    /// needed, for example, by shutting down the pool and awaiting its termination.
    ///
    /// @param firstLeafPath the first leaf path, or a value less than `1`, if the tree is empty
    /// @param lastLeafPath the last leaf path, must not be less than `firstLeafPath`
    /// @param leafReader a function to read leaf records by path. Must not return nulls for
    ///     paths in `[firstLeafPath, lastLeafPath]` range. Called from hashing threads
    /// @param listener node hash listener, may be `null`
    /// @param timeoutMs the max number of milliseconds to wait for hashing to complete
    /// @return the root hash, or `null` if the tree is empty, i.e. `firstLeafPath` is less than `1`
    /// @throws IllegalArgumentException if `firstLeafPath` is greater than `lastLeafPath`
    /// @throws RuntimeException if hashing fails, times out, or is interrupted. If the leaf reader
    ///     returns `null`, the cause is an [IllegalStateException]
    @Nullable
    public Hash hash(
            final long firstLeafPath,
            final long lastLeafPath,
            @NonNull final LongFunction<VirtualLeafBytes<?>> leafReader,
            @Nullable final Listener listener,
            final long timeoutMs) {
        requireNonNull(leafReader, "leaf reader must not be null");

        if (firstLeafPath > lastLeafPath) {
            throw new IllegalArgumentException("Wrong leaf path range: " + firstLeafPath + " - " + lastLeafPath);
        }

        final Listener nonNullListener = listener != null ? listener : Listener.NO_OP;

        if (firstLeafPath < 1) {
            // Empty tree, nothing to hash
            return null;
        }

        final Run run = new Run(firstLeafPath, lastLeafPath, leafReader, nonNullListener);
        return run.execute(timeoutMs);
    }

    /// A single hashing run. Holds all state that hashing tasks need.
    private final class Run {

        private final long firstLeafPath;
        private final long lastLeafPath;
        private final LongFunction<VirtualLeafBytes<?>> leafReader;

        private final Listener listener;

        // Completed with the root hash bytes, or exceptionally with the first exception thrown
        // by any task
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();

        // Set when hashing fails or times out. Tasks check it to avoid doing any more work
        private volatile boolean cancelled = false;

        Run(
                final long firstLeafPath,
                final long lastLeafPath,
                final LongFunction<VirtualLeafBytes<?>> leafReader,
                final Listener listener) {
            this.firstLeafPath = firstLeafPath;
            this.lastLeafPath = lastLeafPath;
            this.leafReader = leafReader;
            this.listener = listener;
        }

        Hash execute(final long timeoutMs) {
            new TraverseTask(ROOT_PATH, null).send();
            try {
                final byte[] rootHash = result.get(timeoutMs, MILLISECONDS);
                return new Hash(rootHash, Cryptography.DEFAULT_DIGEST_TYPE);
            } catch (final ExecutionException e) {
                cancelled = true;
                // Always wrap, so the exception has the caller thread stack trace, not just the
                // hashing thread stack trace of the cause
                throw new RuntimeException(
                        "Failed to get hash during full rehashing", e.getCause() != null ? e.getCause() : e);
            } catch (final InterruptedException e) {
                cancelled = true;
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while full rehashing", e);
            } catch (final TimeoutException e) {
                cancelled = true;
                throw new RuntimeException("Wasn't able to finish full rehashing in time", e);
            }
        }

        void fail(final Throwable t) {
            cancelled = true;
            result.completeExceptionally(t);
        }

        /// Walks down the left-most path of a sub-tree.
        private final class TraverseTask extends AbstractTask {

            private final long path;
            private final HashTask parent;

            TraverseTask(final long path, @Nullable final HashTask parent) {
                super(pool, 0);
                this.path = path;
                this.parent = parent;
            }

            @Override
            protected boolean onExecute() {
                if (cancelled) {
                    return true;
                }
                long nodePath = path;
                HashTask nodeParent = parent;
                while (nodePath < firstLeafPath) {
                    final HashTask hashTask = new HashTask(nodePath, nodeParent);
                    final long rightPath = getRightChildPath(nodePath);
                    if (rightPath <= lastLeafPath) {
                        new TraverseTask(rightPath, hashTask).send();
                    } else {
                        // The right child may only be missing in a single leaf tree, where path 2
                        // doesn't exist
                        hashTask.setHash(false, null);
                    }
                    nodeParent = hashTask;
                    nodePath = getLeftChildPath(nodePath);
                }
                // A leaf, its parent is never null, since the root is always an internal node
                final VirtualLeafBytes<?> leaf = leafReader.apply(nodePath);
                if (leaf == null) {
                    throw new IllegalStateException("Leaf record not found, path = " + nodePath);
                }
                final byte[] hash = MerkleHasher.threadSafeDefault().leafNodeHashBytes(leaf);
                if (cancelled) {
                    return true;
                }
                // Must be called before the hash is passed to the parent node, see class javadoc
                listener.onHashed(nodePath, hash);
                nodeParent.setHash(isLeft(nodePath), hash);
                return true;
            }

            @Override
            protected void onException(final Throwable t) {
                fail(t);
            }
        }

        /// Hashes an internal node, once both its children are hashed.
        private final class HashTask extends AbstractTask {

            private final long path;
            private final HashTask parent;

            private byte[] leftHash;
            private byte[] rightHash;

            HashTask(final long path, @Nullable final HashTask parent) {
                super(pool, 2);
                this.path = path;
                this.parent = parent;
            }

            void setHash(final boolean left, @Nullable final byte[] hash) {
                if (left) {
                    leftHash = hash;
                } else {
                    rightHash = hash;
                }
                send();
            }

            @Override
            protected boolean onExecute() {
                if (cancelled) {
                    return true;
                }
                final byte[] hash = MerkleHasher.threadSafeDefault().internalNodeHashBytes(leftHash, rightHash);
                // Must be called before the hash is passed to the parent node, see class javadoc
                listener.onHashed(path, hash);
                if (parent != null) {
                    parent.setHash(isLeft(path), hash);
                } else {
                    result.complete(hash);
                }
                return true;
            }

            @Override
            protected void onException(final Throwable t) {
                fail(t);
            }
        }
    }

    /// Listens to node level events that occur during hashing.
    @FunctionalInterface
    public interface Listener {

        /// A listener that ignores all events.
        Listener NO_OP = (_, _) -> {};

        /// Called after every node, either a leaf or an internal node, is hashed. Called from
        /// hashing threads, possibly in parallel. For every internal node, this method is called
        /// for both its children before it's called for the node itself, and the children calls
        /// happen-before the node call.
        ///
        /// If this method throws an exception, the whole hashing run fails with this exception
        /// as the cause. This method may block, for example, to flush collected data. Blocking
        /// slows down hashing, since the node's parent isn't hashed until this method returns.
        ///
        /// @param path the node path
        /// @param hash the node hash bytes. Must not be modified
        void onHashed(long path, @NonNull byte[] hash);
    }
}
