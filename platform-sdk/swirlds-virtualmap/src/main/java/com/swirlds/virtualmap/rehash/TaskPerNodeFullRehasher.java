// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.rehash;

import static com.swirlds.logging.legacy.LogMarker.STARTUP;
import static com.swirlds.virtualmap.MerklePathUtils.ROOT_PATH;
import static com.swirlds.virtualmap.MerklePathUtils.getLeftChildPath;
import static com.swirlds.virtualmap.MerklePathUtils.getRightChildPath;
import static com.swirlds.virtualmap.MerklePathUtils.isLeft;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import com.swirlds.virtualmap.MerkleHasher;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.config.VirtualMapConfig;
import com.swirlds.virtualmap.datasource.VirtualDataSource;
import com.swirlds.virtualmap.datasource.VirtualHashChunk;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import com.swirlds.virtualmap.internal.VirtualMapStatistics;
import com.swirlds.virtualmap.internal.hash.FullLeafRehashHashListener;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeoutException;
import java.util.function.LongFunction;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.concurrent.AbstractTask;
import org.hiero.base.crypto.Cryptography;
import org.hiero.base.crypto.Hash;

/// A [FullRehasher] that hashes a whole virtual tree from scratch using [AbstractTask]s, one
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
/// Every node hash is reported to a [PathHashListener] **before** it's passed to the parent
/// task, which is the contract [HashChunkCollector] relies on. In [#rehash(VirtualMap)], node
/// hashes are collected to hash chunks, and the chunks are saved to the data source using
/// [FullLeafRehashHashListener].
///
/// This class is stateless, a single instance may be used to hash multiple trees, even in
/// parallel.
public final class TaskPerNodeFullRehasher implements FullRehasher {

    private static final Logger logger = LogManager.getLogger(TaskPerNodeFullRehasher.class);

    // Desired number of hash chunk flushes per full rehash. Every flush has a fixed cost (a new
    // data file, metadata update, etc.), and every flush creates a new data file to compact later,
    // so for small and mid-size states the number of flushes shouldn't depend on the state size
    private static final long TARGET_FLUSHES = 128;

    // Min flush interval, in hash slots (~24MB of heap). Small states are flushed in batches of
    // at least this size.
    private static final long MIN_FLUSH_INTERVAL = 500_000;

    // Max flush interval, in hash slots (~192MB of heap). Large states are flushed in batches of
    // at most this size. Up to two batches may be in memory at the same time: one is being
    // flushed, and another one is collected by hashing threads in parallel
    private static final long MAX_FLUSH_INTERVAL = 4_000_000;

    private final ForkJoinPool pool;

    /// Creates a new rehasher.
    ///
    /// @param pool the fork-join pool to run hashing tasks in
    public TaskPerNodeFullRehasher(@NonNull final ForkJoinPool pool) {
        this.pool = requireNonNull(pool);
    }

    @Override
    @Nullable
    public Hash rehash(@NonNull final VirtualMap map) {
        final VirtualDataSource dataSource = map.getDataSource();
        final VirtualMapConfig virtualMapConfig = map.getVirtualMapConfig();

        final long firstLeafPath = dataSource.getFirstLeafPath();
        final long lastLeafPath = dataSource.getLastLeafPath();

        if (firstLeafPath < 0 || lastLeafPath < 0) {
            logger.info(STARTUP.getMarker(), "VirtualMap is empty, skipping full rehash.");
            return null;
        }

        final int flushInterval = flushInterval(lastLeafPath, dataSource.getHashChunkHeight());
        logger.info(
                STARTUP.getMarker(),
                "Doing full rehash for the path range: {} - {}, flush interval: {}",
                firstLeafPath,
                lastLeafPath,
                flushInterval);
        final FullLeafRehashHashListener chunkListener = new FullLeafRehashHashListener(
                firstLeafPath, lastLeafPath, dataSource, new VirtualMapStatistics(VirtualMap.LABEL), flushInterval);
        final HashChunkCollector listener = new HashChunkCollector(dataSource.getHashChunkHeight(), chunkListener);

        final LongFunction<VirtualLeafBytes<?>> leafReader = path -> {
            try {
                return dataSource.loadLeafRecord(path);
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
        };

        final long start = System.currentTimeMillis();
        final Hash rootHash =
                hash(firstLeafPath, lastLeafPath, leafReader, listener, virtualMapConfig.fullRehashTimeoutMs());
        logger.info(STARTUP.getMarker(), "Full rehash took {} seconds", (System.currentTimeMillis() - start) / 1000);
        return rootHash;
    }

    /// Calculates the flush interval for [FullLeafRehashHashListener] based on the leaf path range.
    ///
    /// The listener flushes collected chunks once `number of chunks * 2 ^ chunkHeight` reaches
    /// the flush interval. Every [VirtualHashChunk] allocates space for `2 ^ chunkHeight` hashes
    /// in memory, even if only some of them are set (chunks at the last ranks are often not
    /// full), so the flush interval is effectively the number of hash slots in memory per flush,
    /// and a batch takes about `flushInterval * digestLength` bytes of heap.
    ///
    /// The total number of chunks in the tree is known from the last leaf path, so is the total
    /// number of hash slots to flush. The interval is chosen to have [#TARGET_FLUSHES] flushes,
    /// clamped to [[#MIN_FLUSH_INTERVAL], [#MAX_FLUSH_INTERVAL]]. Small states are then
    /// flushed fewer times, and large states more times, but every batch takes at most ~192MB
    /// of heap regardless of the state size.
    ///
    /// For example:
    ///
    /// - a state with ~1M leaves has ~0.27M chunks of height 6, or ~17M hash slots. 17M / 128
    ///   is below the min, so the interval is 500K, which results in ~34 flushes
    /// - a state with ~40M leaves has ~17M chunks, or ~1.09B hash slots. 1.09B / 128 = 8.5M is
    ///   above the max, so the interval is 4M, which results in ~273 flushes
    ///
    /// @param lastLeafPath the last leaf path, must be positive
    /// @param chunkHeight hash chunk height
    /// @return the flush interval, in hash slots
    static int flushInterval(final long lastLeafPath, final int chunkHeight) {
        final long totalChunks = VirtualHashChunk.lastChunkIdForPaths(lastLeafPath, chunkHeight) + 1;
        final long totalHashSlots = totalChunks * VirtualHashChunk.getChunkSize(chunkHeight);
        return (int) Math.clamp(totalHashSlots / TARGET_FLUSHES, MIN_FLUSH_INTERVAL, MAX_FLUSH_INTERVAL);
    }

    /// Hashes the whole virtual tree with the given leaf path range and returns the root hash.
    ///
    /// The listener, if not `null`, is notified about hashing start, then about every node in
    /// the tree (from hashing threads, possibly in parallel), and finally about hashing
    /// completion. If hashing fails or times out, [PathHashListener#onHashingCompleted()] is not
    /// called, and a [RuntimeException] is thrown. In case of a timeout, its cause is a
    /// [TimeoutException]. After a failure or a timeout, remaining hashing tasks stop as soon as
    /// possible, and the listener isn't notified about any new hashes.
    ///
    /// @param firstLeafPath the first leaf path
    /// @param lastLeafPath the last leaf path
    /// @param leafReader a function to read leaf records by path. Must not return nulls for
    ///     paths in `[firstLeafPath, lastLeafPath]` range. Called from hashing threads
    /// @param listener node hash listener, may be `null`
    /// @param timeoutMs the max number of milliseconds to wait for hashing to complete
    /// @return the root hash, or `null` if the leaf path range is empty
    @Nullable
    public Hash hash(
            final long firstLeafPath,
            final long lastLeafPath,
            @NonNull final LongFunction<VirtualLeafBytes<?>> leafReader,
            @Nullable final PathHashListener listener,
            final long timeoutMs) {
        requireNonNull(leafReader, "leaf reader must not be null");

        if (firstLeafPath > lastLeafPath) {
            throw new IllegalArgumentException("Wrong leaf path range: " + firstLeafPath + " - " + lastLeafPath);
        }

        final PathHashListener nonNullListener = listener != null ? listener : PathHashListener.NO_OP;
        nonNullListener.onHashingStarted(firstLeafPath, lastLeafPath);

        if (firstLeafPath < 1) {
            // Empty tree, nothing to hash
            nonNullListener.onHashingCompleted();
            return null;
        }

        final Run run = new Run(firstLeafPath, lastLeafPath, leafReader, nonNullListener);
        final Hash rootHash = run.execute(timeoutMs);

        nonNullListener.onHashingCompleted();
        return rootHash;
    }

    /// A single hashing run. Holds all state that hashing tasks need.
    private final class Run {

        private final long firstLeafPath;
        private final long lastLeafPath;
        private final LongFunction<VirtualLeafBytes<?>> leafReader;

        private final PathHashListener listener;

        // Completed with the root hash bytes, or exceptionally with the first exception thrown
        // by any task
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();

        // Set when hashing fails or times out. Tasks check it to avoid doing any more work
        private volatile boolean cancelled = false;

        Run(
                final long firstLeafPath,
                final long lastLeafPath,
                final LongFunction<VirtualLeafBytes<?>> leafReader,
                final PathHashListener listener) {
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
                final Throwable cause = e.getCause() != null ? e.getCause() : e;
                if (cause instanceof RuntimeException re) {
                    throw re;
                }
                throw new RuntimeException("Failed to get hash during full rehashing", cause);
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

        /// Walks down the left-most path of a sub-tree, see the class javadoc.
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
}
