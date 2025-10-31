// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.hash;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.virtualmap.internal.Path.INVALID_PATH;
import static com.swirlds.virtualmap.internal.Path.ROOT_PATH;
import static java.util.Objects.requireNonNull;

import com.hedera.pbj.runtime.hashing.WritableMessageDigest;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.config.VirtualMapConfig;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import com.swirlds.virtualmap.internal.Path;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Objects;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongFunction;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.StackTrace;
import org.hiero.base.concurrent.AbstractTask;
import org.hiero.base.crypto.Cryptography;
import org.hiero.base.crypto.Hash;

/**
 * Responsible for hashing virtual merkle trees. This class is designed to work both for normal
 * hashing use cases, and also for hashing during reconnect.
 *
 * <p>There should be one {@link VirtualHasher} shared across all copies of a {@link VirtualMap}
 * "family".
 */
public final class VirtualHasher {

    // When virtual tree is of size 1 (only the root node and a single leaf), root hash should
    // include the hash for path 1 (leaf), but not for path 2. This marker Hash object is used
    // as path 2 input hash for the root hashing task. Null hash cannot be used here as it would
    // trigger loading path 2 hash from disk
    private static final Hash NO_PATH2_HASH = new Hash();

    /**
     * Use this for all logging, as controlled by the optional data/log4j2.xml file
     */
    private static final Logger logger = LogManager.getLogger(VirtualHasher.class);

    private static volatile ForkJoinPool hashingPool = null;

    /**
     * This method is invoked from a non-static method, passing the provided configuration.
     * Consequently, the hashing pool will be initialized using the configuration provided
     * with the first call of the hash method. Subsequent calls will reuse the same pool.
     */
    private static ForkJoinPool getHashingPool(final @NonNull VirtualMapConfig virtualMapConfig) {
        requireNonNull(virtualMapConfig);

        ForkJoinPool pool = hashingPool;
        if (pool == null) {
            synchronized (VirtualHasher.class) {
                pool = hashingPool;
                if (pool == null) {
                    pool = new ForkJoinPool(
                            virtualMapConfig.getNumHashThreads(),
                            ForkJoinPool.defaultForkJoinWorkerThreadFactory,
                            (t, e) -> logger.error(
                                    EXCEPTION.getMarker(),
                                    "Virtual hasher thread terminated with exception: {}",
                                    StackTrace.getStackTrace(e)),
                            true);
                    hashingPool = pool;
                }
            }
        }
        return pool;
    }

    /**
     * This thread-local gets a message digest that can be used for hashing on a per-thread basis.
     */
    private static final ThreadLocal<WritableMessageDigest> MESSAGE_DIGEST_THREAD_LOCAL =
            ThreadLocal.withInitial(() -> new WritableMessageDigest(Cryptography.DEFAULT_DIGEST_TYPE.buildDigest()));

    /**
     * A function to look up clean hashes by path during hashing. This function is stored in
     * a class field to avoid passing it as an arg to every hashing task.
     */
    private LongFunction<Hash> hashReader;

    /**
     * A listener to notify about hashing events. This listener is stored in a class field to
     * avoid passing it as an arg to every hashing task.
     */
    private VirtualHashListener listener;

    /**
     * Tracks if this virtual hasher has been shut down. If true (indicating that the hasher
     * has been intentionally shut down), then don't log/throw if the rug is pulled from
     * underneath the hashing threads.
     */
    private final AtomicBoolean shutdown = new AtomicBoolean(false);

    /**
     * Indicate to the virtual hasher that it has been shut down. This method does not interrupt threads, but
     * it indicates to threads that an interrupt may happen, and that the interrupt should not be treated as
     * an error.
     */
    public void shutdown() {
        shutdown.set(true);
    }

    // A task that can supply hashes to other tasks. There are two hash producer task
    // types: leaf tasks and chunk tasks
    class HashProducingTask extends AbstractTask {

        protected ChunkHashTask out;

        HashProducingTask(final ForkJoinPool pool, final int dependencyCount) {
            super(pool, dependencyCount);
        }

        boolean hasOut() {
            return out != null;
        }

        void setOut(final ChunkHashTask out) {
            this.out = out;
            send();
        }

        void complete() {
            out.send();
        }

        @Override
        protected boolean onExecute() {
            return true;
        }

        @Override
        protected void onException(final Throwable t) {
            if (out != null) {
                out.completeExceptionally(t);
            }
        }
    }

    // Chunk hash task. Has 2^height inputs, which are set either by other chunk tasks,
    // or by leaf tasks. The path does not belong to this chunk, it's used to set the
    // hashing result to the output task
    class ChunkHashTask extends HashProducingTask {

        // Output path
        private final long path;

        // Height. Must be greater than zero
        private final int height;

        // Hash inputs, at least two
        private final Hash[] ins;

        ChunkHashTask(final ForkJoinPool pool, final long path, final int height) {
            super(pool, 1 + (1 << height));
            this.path = path;
            this.height = height;
            this.ins = new Hash[1 << height];
        }

        @Override
        public void complete() {
            assert Arrays.stream(ins).allMatch(Objects::isNull);
            super.complete();
        }

        void setHash(final long path, final Hash hash) {
            assert Path.getRank(this.path) + height == Path.getRank(path);
            assert hash != null;
            final long firstPathInPathRank = Path.getLeftGrandChildPath(this.path, height);
            final int index = Math.toIntExact(path - firstPathInPathRank);
            assert (index >= 0) && (index < ins.length);
            ins[index] = hash;
            send();
        }

        Hash getResult() {
            assert isDone();
            return ins[0];
        }

        @Override
        protected boolean onExecute() {
            int len = 1 << height;
            long rankPath = Path.getLeftGrandChildPath(path, height);
            while (len > 1) {
                for (int i = 0; i < len / 2; i++) {
                    final long hashedPath = Path.getParentPath(rankPath + i * 2);
                    Hash left = ins[i * 2];
                    Hash right = ins[i * 2 + 1];
                    if ((left == null) && (right == null)) {
                        ins[i] = null;
                    } else {
                        if (left == null) {
                            final long leftPath = rankPath + i * 2;
                            left = hashReader.apply(leftPath);
                            if (left == null) {
                                throw new RuntimeException("Failed to load hash for path = " + leftPath);
                            }
                        }
                        if (right == null) {
                            final long rightPath = rankPath + i * 2 + 1;
                            right = hashReader.apply(rightPath);
                            if ((right == null) && (rightPath != 2)) {
                                throw new RuntimeException("Failed to load hash for path = " + rightPath);
                            }
                        }
                        ins[i] = hash(left, right);
                        listener.onNodeHashed(hashedPath, ins[i]);
                    }
                }
                rankPath = Path.getParentPath(rankPath);
                len = len >> 1;
            }
            if (out != null) {
                out.setHash(path, ins[0]);
            }
            return true;
        }

        static Hash hash(final Hash left, final Hash right) {
            final WritableMessageDigest wmd = MESSAGE_DIGEST_THREAD_LOCAL.get();
            wmd.reset();
            // Unique value to make sure internal node hashes are different from leaf hashes
            wmd.writeByte((byte) 0x02);
            left.getBytes().writeTo(wmd);
            if (right != NO_PATH2_HASH) { // use identity check rather than equals
                right.getBytes().writeTo(wmd);
            }
            return new Hash(wmd.digest(), Cryptography.DEFAULT_DIGEST_TYPE);
        }
    }

    // Leaf hash task. Hashes a given leaf record and supplies the result to the output
    // task. In some cases, leaf tasks are created for clean leaves. Such tasks are not
    // given leaf data, but executed using #complete() method, and their output is a
    // null hash
    class LeafHashTask extends HashProducingTask {

        // Leaf path
        private final long path;

        // Leaf data. May be null
        private VirtualLeafBytes<?> leaf;

        LeafHashTask(final ForkJoinPool pool, final long path) {
            super(pool, 2);
            this.path = path;
        }

        @Override
        public void complete() {
            assert leaf == null;
            super.complete();
        }

        void setLeaf(final VirtualLeafBytes<?> leaf) {
            assert leaf != null;
            assert path == leaf.path();
            this.leaf = leaf;
            send();
        }

        @Override
        protected boolean onExecute() {
            Hash hash = null;
            if (leaf != null) {
                final WritableMessageDigest wmd = MESSAGE_DIGEST_THREAD_LOCAL.get();
                leaf.writeToForHashing(wmd);
                hash = new Hash(wmd.digest(), Cryptography.DEFAULT_DIGEST_TYPE);
                listener.onLeafHashed(leaf);
                listener.onNodeHashed(path, hash);
            }
            out.setHash(path, hash);
            return true;
        }
    }

    // Chunk ranks. Every chunk has an output rank and an input rank. The output rank is the rank
    // of the top-most path in the chunk. For example, the root chunk has output rank 0. The input
    // rank is the rank of all chunk inputs (hashes). For example, the root chunk has input rank
    // defaultChunkHeight (if the virtual tree is large enough). There may be no chunks with
    // output ranks, which are not multipliers of defaultChunkHeight, except chunks of height 1
    // with inputs at the last leaf rank and outputs at the first leaf rank.

    // Chunk heights. Chunks with output ranks 0, defaultChunkHeight, defaultChunkHeight * 2, and so on
    // have height == defaultChunkHeight. There may be no chunks of heights less than defaultChunkHeight,
    // except chunks that are close to the leaf ranks, their heights are aligned with the first leaf rank.
    // For example, if the first leaf rank is 15, the last leaf rank is 16, and defaultChunkHeight is 6, then
    // the root chunk is of height 6 (ranks 1 to 6, rank 0 is the output), chunks below it are of height 6,
    // too (ranks 7 to 12, rank 6 is the output). Chunks with output rank 13 are of height 3 (ranks 13 to 15),
    // their input rank is 15, the same as the first leaf rank. Also, there are some chunks of height 1,
    // each with two leaves at the last leaf rank as inputs, and rank 15 as the output rank.

    /**
     * Given a rank, returns chunk height, where the rank is the chunk input rank.
     *
     * @param rank the input rank
     * @param firstLeafRank the rank of the first leaf path
     * @param lastLeafRank the rank of the last leaf path
     * @param defaultChunkHeight default chunk height from configuration
     */
    private static int getChunkHeightForInputRank(
            final int rank, final int firstLeafRank, final int lastLeafRank, final int defaultChunkHeight) {
        if ((rank == lastLeafRank) && (firstLeafRank != lastLeafRank)) {
            // Small chunks of height 1 starting at the first leaf rank
            return 1;
        } else if (rank == firstLeafRank) {
            // If a chunk ends at the first leaf rank, its height is aligned with the first leaf rank
            return ((rank - 1) % defaultChunkHeight) + 1;
        } else {
            // All other chunks are of the default height
            assert (rank % defaultChunkHeight == 0);
            return defaultChunkHeight;
        }
    }

    /**
     * Given a rank, returns chunk height, where the rank is the chunk output rank.
     *
     * @param rank the output rank
     * @param firstLeafRank the rank of the first leaf path
     * @param lastLeafRank the rank of the last leaf path
     * @param defaultChunkHeight default chunk height from configuration
     */
    private static int getChunkHeightForOutputRank(
            final int rank, final int firstLeafRank, final int lastLeafRank, final int defaultChunkHeight) {
        if ((rank == firstLeafRank) && (firstLeafRank != lastLeafRank)) {
            // Small chunks of height 1 starting at the first leaf rank
            return 1;
        } else {
            // Either default height, or height to the first leaf rank, whichever is smaller
            assert rank % defaultChunkHeight == 0;
            assert rank < firstLeafRank;
            return Math.min(defaultChunkHeight, firstLeafRank - rank);
        }
    }

    /**
     * Hash the given dirty leaves and the minimal subset of the tree necessary to produce a
     * single root hash. The root hash is returned.
     *
     * <p>If leaf path is empty, that is when {@code firstLeafPath} and/or {@code lastLeafPath}
     * are zero or less, and dirty leaves stream is not empty, throws an {@link
     * IllegalArgumentException}.
     *
     * @param hashReader
     * 		Return a {@link Hash} by path. Used when this method needs to look up clean nodes.
     * @param sortedDirtyLeaves
     * 		A stream of dirty leaves sorted in <strong>ASCENDING PATH ORDER</strong>, such that path
     * 		1234 comes before 1235. If null or empty, a null hash result is returned.
     * @param firstLeafPath
     * 		The firstLeafPath of the tree that is being hashed. If &lt; 1, then a null hash result is returned.
     * 		No leaf in {@code sortedDirtyLeaves} may have a path less than {@code firstLeafPath}.
     * @param lastLeafPath
     * 		The lastLeafPath of the tree that is being hashed. If &lt; 1, then a null hash result is returned.
     * 		No leaf in {@code sortedDirtyLeaves} may have a path greater than {@code lastLeafPath}.
     * @param listener
     *      Hash listener. May be {@code null}
     * @param virtualMapConfig platform configuration for VirtualMap
     * @return The hash of the root of the tree
     */
    @SuppressWarnings("rawtypes")
    public Hash hash(
            final @NonNull LongFunction<Hash> hashReader,
            final @NonNull Iterator<VirtualLeafBytes> sortedDirtyLeaves,
            final long firstLeafPath,
            final long lastLeafPath,
            final @Nullable VirtualHashListener listener,
            final @NonNull VirtualMapConfig virtualMapConfig) {
        requireNonNull(virtualMapConfig);

        // We don't want to include null checks everywhere, so let the listener be NoopListener if null
        final VirtualHashListener normalizedListener = listener == null
                ? new VirtualHashListener() {
                    /* noop */
                }
                : listener;

        final ForkJoinPool pool = Thread.currentThread() instanceof ForkJoinWorkerThread thread
                ? thread.getPool()
                : getHashingPool(virtualMapConfig);

        return pool.invoke(ForkJoinTask.adapt(() -> hashInternal(
                hashReader,
                sortedDirtyLeaves,
                firstLeafPath,
                lastLeafPath,
                normalizedListener,
                virtualMapConfig,
                pool)));
    }

    /**
     * Internal method calculating the hash of the tree in a given fork-join pool.
     *
     * @param hashReader
     * 		Return a {@link Hash} by path. Used when this method needs to look up clean nodes.
     * @param sortedDirtyLeaves
     * 		A stream of dirty leaves sorted in <strong>ASCENDING PATH ORDER</strong>, such that path
     * 		1234 comes before 1235. If null or empty, a null hash result is returned.
     * @param firstLeafPath
     * 		The firstLeafPath of the tree that is being hashed. If &lt; 1, then a null hash result is returned.
     * 		No leaf in {@code sortedDirtyLeaves} may have a path less than {@code firstLeafPath}.
     * @param lastLeafPath
     * 		The lastLeafPath of the tree that is being hashed. If &lt; 1, then a null hash result is returned.
     * 		No leaf in {@code sortedDirtyLeaves} may have a path greater than {@code lastLeafPath}.
     * @param listener
     *      Hash listener. May be {@code null}
     * @param virtualMapConfig platform configuration for VirtualMap
     * @param pool the pool to use for hashing tasks.
     * @return calculated root hash, or null if there are no dirty leaves to hash.
     */
    private Hash hashInternal(
            final @NonNull LongFunction<Hash> hashReader,
            final @NonNull Iterator<VirtualLeafBytes> sortedDirtyLeaves,
            final long firstLeafPath,
            final long lastLeafPath,
            final @NonNull VirtualHashListener listener,
            final @NonNull VirtualMapConfig virtualMapConfig,
            final @NonNull ForkJoinPool pool) {
        // Let the listener know we have started hashing.
        listener.onHashingStarted(firstLeafPath, lastLeafPath);

        if (!sortedDirtyLeaves.hasNext()) {
            // Nothing to hash.
            listener.onHashingCompleted();
            return null;
        } else {
            if ((firstLeafPath < 1) || (lastLeafPath < 1)) {
                throw new IllegalArgumentException("Dirty leaves stream is not empty, but leaf path range is empty");
            }
        }

        this.hashReader = hashReader;
        this.listener = listener;

        // Algo v6. This version is task based, where every task is responsible for hashing a small
        // chunk of the tree. Tasks are running in a fork-join pool, which is shared across all
        // virtual maps.

        // A chunk is a small sub-tree, which is identified by a path and a height. Chunks of
        // height 1 contain three nodes: one node and two its children. Chunks of height 2 contain
        // seven nodes: a node, two its children, and four grand children. Chunk path is the path
        // of the top-level node in the chunk.

        // Each chunk is processed in a separate task. Tasks have dependencies. Once all task
        // dependencies are met, the task is scheduled for execution in the pool. Each task
        // has N input dependencies, where N is the number of nodes at the lowest chunk rank,
        // i.e. 2^height. Every input dependency is either set to a hash from another task,
        // or a null value, which indicates that the input hash needs not to be recalculated,
        // but loaded from disk. A special case of a task is leaf tasks, they are all of
        // height 1, both input dependencies are null, but they are given a leaf instead. For
        // these tasks, the hash is calculated based on leaf content rather than based on input
        // hashes.

        // All tasks also have an output dependency, also a task. When a hash for the task's chunk
        // is calculated, it is set as an input dependency of that task. Output dependency value
        // may not be null.

        // Default chunk height, from config
        final int chunkHeight = virtualMapConfig.virtualHasherChunkHeight();
        int firstLeafRank = Path.getRank(firstLeafPath);
        int lastLeafRank = Path.getRank(lastLeafPath);

        // This map contains all tasks created, but not scheduled for execution yet
        final HashMap<Long, HashProducingTask> allTasks = new HashMap<>();

        final int rootTaskHeight = Math.min(firstLeafRank, chunkHeight);
        final ChunkHashTask rootTask = new ChunkHashTask(pool, ROOT_PATH, rootTaskHeight);
        // The root task doesn't have an output. Still need to call setOut() to set the dependency
        rootTask.setOut(null);
        allTasks.put(ROOT_PATH, rootTask);

        boolean firstLeaf = true;
        final long[] stack = new long[lastLeafRank + 1];
        Arrays.fill(stack, INVALID_PATH);

        // Iterate over all dirty leaves one by one. For every leaf, create a new task, if not
        // created. Then look up for a parent task. If it's created, it must not be executed yet,
        // as one of the inputs is this dirty leaf task. If the parent task is not created,
        // create it here.

        // For the created leaf task, set the leaf as an input. Together with the parent task
        // it completes all task dependencies, so the task is executed.

        while (sortedDirtyLeaves.hasNext()) {
            VirtualLeafBytes<?> leaf = sortedDirtyLeaves.next();
            long curPath = leaf.path();
            LeafHashTask leafTask = (LeafHashTask) allTasks.remove(curPath);
            if (leafTask == null) {
                leafTask = new LeafHashTask(pool, curPath);
            }
            leafTask.setLeaf(leaf);

            // The next step is to iterate over parent tasks, until an already created task
            // is met (e.g. the root task). For every parent task, check all already created
            // tasks at the same (parent) rank using "stack". This array contains the left
            // most path to the right of the last task processed at the rank. All tasks at
            // the rank between "stack" and the current parent are guaranteed to be clear,
            // since dirty leaves are sorted in path order. All such tasks are set "null"
            // input dependency, which is propagated to their parent (output) tasks.
            HashProducingTask curTask = leafTask;
            while (true) {
                final int curRank = Path.getRank(curPath);
                final int parentChunkHeight =
                        getChunkHeightForInputRank(curRank, firstLeafRank, lastLeafRank, chunkHeight);
                final int chunkWidth = 1 << parentChunkHeight;
                // If some tasks have been created at this rank, they can now be marked as
                // clean. No dirty leaves in the remaining stream may affect these tasks
                long curStackPath = stack[curRank];
                if (curStackPath != INVALID_PATH) {
                    stack[curRank] = INVALID_PATH;
                    final long firstPathInRank = Path.getPathForRankAndIndex(curRank, 0);
                    final long curStackChunkNoInRank = (curStackPath - firstPathInRank) / chunkWidth;
                    final long firstPathInNextChunk = firstPathInRank + (curStackChunkNoInRank + 1) * chunkWidth;
                    // Process all tasks starting from "stack" path to the end of the chunk
                    for (; curStackPath < firstPathInNextChunk; ++curStackPath) {
                        // It may happen that curPath is actually in the same chunk as stack[curRank].
                        // In this case, stack[curRank] should be set to curPath + 1 to prevent a situation in which
                        // all existing tasks between curPath and the end of the chunk hang in the tasks map and
                        // are processed only after the last leaf (in the loop to set null data for all tasks
                        // remaining in the map), despite these tasks being known to be clear.
                        if (curStackPath == curPath) {
                            if (curPath + 1 < firstPathInNextChunk) {
                                stack[curRank] = curPath + 1;
                            }
                            break;
                        }
                        final HashProducingTask t = allTasks.remove(curStackPath);
                        assert t != null;
                        t.complete();
                    }
                }

                // If the out is already set at this rank, all parent tasks and siblings are already
                // processed, so break the loop
                if (curTask.hasOut() || curTask == rootTask) {
                    break;
                }

                final long parentPath = Path.getGrandParentPath(curPath, parentChunkHeight);
                // Parent task is always a chunk task
                ChunkHashTask parentTask = (ChunkHashTask) allTasks.remove(parentPath);
                if (parentTask == null) {
                    parentTask = new ChunkHashTask(pool, parentPath, parentChunkHeight);
                }
                curTask.setOut(parentTask);

                // For every task on the route to the root, check its siblings within the same
                // chunk. If a sibling is to the right, create a task for it, but not schedule yet
                // (there may be a dirty leaf for it later in the stream). If a sibling is to the
                // left, it may be marked as clean unless this is the very first dirty leaf. For
                // this very first dirty leaf siblings to the left may not be marked clean, there
                // may be dirty leaves on the last leaf rank that would contribute to these
                // siblings. In this case, just create the tasks and store them to the map

                final long firstPathInRank = Path.getPathForRankAndIndex(curRank, 0);
                final long chunkNoInRank = (curPath - firstPathInRank) / chunkWidth;
                final long firstSiblingPath = firstPathInRank + chunkNoInRank * chunkWidth;
                final long lastSiblingPath = firstSiblingPath + chunkWidth - 1;
                for (long siblingPath = firstSiblingPath; siblingPath <= lastSiblingPath; siblingPath++) {
                    if (siblingPath == curPath) {
                        continue;
                    }
                    if (siblingPath > lastLeafPath) {
                        // Special case for a tree with one leaf at path 1
                        assert siblingPath == 2;
                        parentTask.setHash(siblingPath, NO_PATH2_HASH);
                    } else if ((siblingPath < curPath) && !firstLeaf) {
                        assert !allTasks.containsKey(siblingPath);
                        // Mark the sibling as clean, reducing the number of parent task dependencies
                        parentTask.send();
                    } else {
                        // Get or create a sibling task
                        final HashProducingTask siblingTask;
                        if (siblingPath >= firstLeafPath) {
                            // Leaf sibling
                            assert !allTasks.containsKey(siblingPath);
                            siblingTask = allTasks.computeIfAbsent(siblingPath, p -> new LeafHashTask(pool, p));
                        } else {
                            // Chunk sibling
                            final int taskChunkHeight =
                                    getChunkHeightForOutputRank(curRank, firstLeafRank, lastLeafRank, chunkHeight);
                            siblingTask = allTasks.computeIfAbsent(
                                    siblingPath, path -> new ChunkHashTask(pool, path, taskChunkHeight));
                        }
                        // Set sibling task output to the same parent
                        siblingTask.setOut(parentTask);
                    }
                }
                // Now update the stack to the first sibling to the right. When the next node
                // at the same rank is processed, all tasks starting from this sibling are
                // guaranteed to be clean
                if ((curPath != lastSiblingPath) && !firstLeaf) {
                    stack[curRank] = curPath + 1;
                }

                curPath = parentPath;
                curTask = parentTask;
            }
            firstLeaf = false;
        }
        // After all dirty nodes are processed along with routes to the root, there may still be
        // tasks in the map. These tasks were created, but not scheduled as their input dependencies
        // aren't set yet. Examples are: tasks to the right of the sibling in "stack" created as a
        // result of walking from the last leaf on the first leaf rank to the root; similar tasks
        // created during walking from the last leaf on the last leaf rank to the root; sibling
        // tasks to the left of the very first route to the root. There are no more dirty leaves,
        // all these tasks may be marked as clean now
        allTasks.forEach((path, task) -> task.complete());
        allTasks.clear();

        try {
            rootTask.join();
        } catch (final Exception e) {
            if (shutdown.get()) {
                return null;
            }
            logger.error(EXCEPTION.getMarker(), "Failed to wait for all hashing tasks", e);
            throw e;
        }

        listener.onHashingCompleted();

        this.hashReader = null;
        this.listener = null;

        return rootTask.getResult();
    }

    public Hash emptyRootHash() {
        return ChunkHashTask.hash(Cryptography.NULL_HASH, Cryptography.NULL_HASH);
    }
}
