// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.hash;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.virtualmap.internal.Path.INVALID_PATH;
import static com.swirlds.virtualmap.internal.Path.ROOT_PATH;
import static java.util.Objects.requireNonNull;

import com.hedera.pbj.runtime.hashing.WritableMessageDigest;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.config.VirtualMapConfig;
import com.swirlds.virtualmap.datasource.VirtualHashChunk;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import com.swirlds.virtualmap.internal.Path;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.security.MessageDigest;
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
    public static final Hash NO_PATH2_HASH = new Hash();

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
     * Pre-loads virtual hash chunks by chunk paths.
     */
    private LongFunction<VirtualHashChunk> hashChunkPreloader;

    private long firstLeafPath;
    private long lastLeafPath;

    private int defaultChunkHeight;

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

    public static Hash hashInternal(final Hash left, final Hash right) {
        return hashInternal(left, right, MESSAGE_DIGEST_THREAD_LOCAL.get());
    }

    private static Hash hashInternal(final Hash left, final Hash right, final WritableMessageDigest wmd) {
        // Unique value to make sure internal node hashes are different from leaf hashes. This
        // value indicates the number of child nodes. All internal virtual nodes have 2 children
        // except a root node in a tree with just one element / leaf. In this and only this case,
        // the right hash will be set to a marker NO_PATH2_HASH hash object
        wmd.writeByte(right == NO_PATH2_HASH ? (byte) 0x01 : (byte) 0x02);
        left.getBytes().writeTo(wmd);
        if (right != NO_PATH2_HASH) { // use identity check rather than equals
            right.getBytes().writeTo(wmd);
        }
        // Note that the digest is reset after the call to digest()
        return new Hash(wmd.digest(), Cryptography.DEFAULT_DIGEST_TYPE);
    }

    // A task that can supply hashes to other tasks. There are two hash producer task
    // types: leaf tasks and chunk tasks
    abstract class HashProducingTask extends AbstractTask {

        protected ChunkHashTask out;

        HashProducingTask(final ForkJoinPool pool, final int dependencyCount) {
            super(pool, dependencyCount);
        }

        void setOut(final ChunkHashTask out) {
            this.out = out;
            if (out != null) {
                out.dynamicHashInput();
            }
            send();
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

        // Number of input dependencies set for this task, either other tasks, or
        // nulls, which indicate the hash will be loaded from disk. No synchronization,
        // since this field is only used on the task submission thread
        private int inputsInitialized = 0;

        // Indicates if this task will have all input hashes set, i.e. all inputs are
        // other tasks, not nulls. This flag is to avoid scanning through all ins
        // during execution time
        private boolean hasNullInputs = false;

        ChunkHashTask(final ForkJoinPool pool, final long path, final int height) {
            super(pool, 1 + (1 << height));
            this.path = path;
            this.height = height;
            this.ins = new Hash[1 << height];
        }

        // Notifies this task that one of its input hashes will be provided by
        // another task. This method may be called on the task submission thread only
        public void dynamicHashInput() {
            inputsInitialized++;
        }

        // Notifies this task that one of its input hashes is null, which means it
        // will be loaded from disk during execution. This method may be called on
        // the task submission thread only
        public void staticNullInput() {
            hasNullInputs = true;
            inputsInitialized++;
            send();
        }

        // Notifies this task it won't get any more inputs, that is no more calls to
        // dynamicHashInput() or staticNullInput(). If some task inputs aren't
        // initialized by this time, they are assumed to be null
        public void noMoreInputs() {
            final int numInputs = 1 << height;
            assert inputsInitialized <= numInputs;
            for (int i = inputsInitialized; i < numInputs; i++) {
                hasNullInputs = true;
                inputsInitialized++;
                send();
            }
        }

        public boolean allInputsInitialized() {
            return inputsInitialized == (1 << height);
        }

        void setHash(final long path, @NonNull final Hash hash) {
            assert Path.getRank(this.path) + height == Path.getRank(path)
                    : this.path + " " + Path.getRank(this.path) + " " + height + " " + path + " " + Path.getRank(path);
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
            final long chunkPath;
            final int taskRank = Path.getRank(path);
            if (taskRank % defaultChunkHeight == 0) {
                chunkPath = path;
            } else {
                final int chunkPathRank = taskRank / defaultChunkHeight * defaultChunkHeight;
                final int rankDiff = taskRank - chunkPathRank;
                chunkPath = Path.getGrandParentPath(path, rankDiff);
            }
            final int chunkRank = Path.getRank(chunkPath);
            VirtualHashChunk hashChunk = null;
            if ((height == defaultChunkHeight) || (Path.getLeftGrandChildPath(path, height) >= firstLeafPath)) {
                if (chunkPath == path) {
                    if (!hasNullInputs) {
                        // All inputs provided, no need to load the chunk from disk using hashChunkPreloader
                        hashChunk = new VirtualHashChunk(chunkPath, defaultChunkHeight);
                    }
                }
            }
            if (hashChunk == null) {
                // Important: chunk preloader MUST return the same VirtualHashChunk object if
                // called multiple times for a single chunk (any path in the chunk)
                hashChunk = hashChunkPreloader.apply(chunkPath);
                if (hashChunk == null) {
                    throw new RuntimeException("Failed to load hash chunk for path = " + chunkPath);
                }
                assert hashChunk.path() == chunkPath;
            }
            final int chunkLastRank = chunkRank + hashChunk.height();
            long rankPath = Path.getLeftGrandChildPath(path, height);
            int currentRank = taskRank + height;
            final WritableMessageDigest wmd = MESSAGE_DIGEST_THREAD_LOCAL.get();
            while (len > 1) {
                for (int i = 0; i < len / 2; i++) {
                    Hash left = ins[i * 2];
                    final long leftPath = rankPath + i * 2;
                    assert (leftPath < lastLeafPath) || (lastLeafPath == 1);
                    final boolean leftIsLeaf = leftPath >= firstLeafPath;
                    if (left == null) {
                        assert currentRank == taskRank + height;
                        // Need to load the hash from hashChunk
                        if ((height == defaultChunkHeight) || leftIsLeaf) {
                            left = hashChunk.getHashAtPath(leftPath);
                        } else {
                            // Get left's left and right child hashes and hashInternal() them
                            left = hashChunk.calcHash(leftPath, firstLeafPath, lastLeafPath);
                        }
                    } else {
                        // Hash is provided / computed, need to update it in hashChunk
                        if ((currentRank == chunkLastRank) || leftIsLeaf) {
                            hashChunk.setHashAtPath(leftPath, left);
                        }
                    }

                    Hash right = ins[i * 2 + 1];
                    final long rightPath = rankPath + i * 2 + 1;
                    assert (rightPath <= lastLeafPath) || (lastLeafPath == 1);
                    final boolean rightIsLeaf = rightPath >= firstLeafPath;
                    if (rightPath > lastLeafPath) {
                        assert rightPath == 2;
                        assert right == null;
                        right = NO_PATH2_HASH;
                    } else if (right == null) {
                        assert currentRank == taskRank + height;
                        // Need to load the hash from hashChunk
                        if ((height == defaultChunkHeight) || rightIsLeaf) {
                            right = hashChunk.getHashAtPath(rightPath);
                        } else {
                            // Get right's left and right child hashes and hashInternal() them
                            right = hashChunk.calcHash(rightPath, firstLeafPath, lastLeafPath);
                        }
                    } else {
                        // Hash is provided / computed, need to update it in hashChunk
                        if ((currentRank == chunkLastRank) || rightIsLeaf) {
                            hashChunk.setHashAtPath(rightPath, right);
                        }
                    }

                    ins[i] = hashInternal(left, right, wmd);
                }
                rankPath = Path.getParentPath(rankPath);
                currentRank--;
                len = len >> 1;
            }
            // Avoid multiple notifications for a single chunk
            if (path == chunkPath) {
                listener.onHashChunkHashed(hashChunk);
            }
            if (out != null) {
                out.setHash(path, ins[0]);
            }
            return true;
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
        private final VirtualLeafBytes<?> leaf;

        LeafHashTask(final ForkJoinPool pool, final long path, @NonNull final VirtualLeafBytes<?> leaf) {
            super(pool, 1); // dependency: output task
            this.path = path;
            assert leaf != null;
            assert path == leaf.path();
            this.leaf = leaf;
        }

        @Override
        protected boolean onExecute() {
            final WritableMessageDigest wmd = MESSAGE_DIGEST_THREAD_LOCAL.get();
            leaf.writeToForHashing(wmd);
            final Hash hash = new Hash(wmd.digest(), Cryptography.DEFAULT_DIGEST_TYPE);
            listener.onLeafHashed(leaf);
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
    private int getChunkHeightForInputRank(
            final long path,
            final int rank,
            final int firstLeafRank,
            final int lastLeafRank,
            final int defaultChunkHeight) {
        if ((rank == lastLeafRank) && (firstLeafRank != lastLeafRank)) {
            final int height = ((rank - 1) % defaultChunkHeight) + 1;
            final long chunkPath = Path.getGrandParentPath(path, height);
            final long lastPathInChunk = Path.getRightGrandChildPath(chunkPath, height);
            return (lastPathInChunk <= lastLeafPath) ? height : 1;
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
     * Hash the given dirty leaves and the minimal subset of the tree necessary to produce a
     * single root hash. The root hash is returned.
     *
     * <p>If leaf path range is empty, that is when {@code firstLeafPath} and/or {@code lastLeafPath}
     * are zero or less, and dirty leaves stream is not empty, throws an {@link
     * IllegalArgumentException}.
     *
     * <p>If the provided list of dirty leaves is empty, this method returns {@code null}.
     *
     * @param hashChunkPreloader
     *      A mechanism to load hash chunks by path. If a chunk is partially clean, the hasher
     *      will load the chunk first, then update all dirty hashes in it, and finally notify
     *      the listener using {@link VirtualHashListener#onHashChunkHashed(VirtualHashChunk)}
     * @param sortedDirtyLeaves
     * 		A list of dirty leaves sorted in <strong>ASCENDING PATH ORDER</strong>, such that path
     * 		1234 comes before 1235. If null or empty, a null hash result is returned.
     * @param firstLeafPath
     * 		The firstLeafPath of the tree that is being hashed. If &lt; 1, then a null hash result is returned.
     * 		No leaf in {@code sortedDirtyLeaves} may have a path less than {@code firstLeafPath}.
     * @param lastLeafPath
     * 		The lastLeafPath of the tree that is being hashed. If &lt; 1, then a null hash result is returned.
     * 		No leaf in {@code sortedDirtyLeaves} may have a path greater than {@code lastLeafPath}.
     * @param listener
     *      Hash listener. May be {@code null}
     * @param virtualMapConfig
     *      Platform configuration for VirtualMap
     * @return
     *      The hash of the root of the tree, or {@code null} if the list of dirty leaves is empty
     */
    @SuppressWarnings("rawtypes")
    public Hash hash(
            final int hashChunkHeight,
            final @NonNull LongFunction<VirtualHashChunk> hashChunkPreloader,
            final @NonNull Iterator<VirtualLeafBytes> sortedDirtyLeaves,
            final long firstLeafPath,
            final long lastLeafPath,
            final @Nullable VirtualHashListener listener,
            final @NonNull VirtualMapConfig virtualMapConfig) {
        requireNonNull(hashChunkPreloader);
        requireNonNull(virtualMapConfig);

        this.defaultChunkHeight = hashChunkHeight;

        // We don't want to include null checks everywhere, so let the listener be NoopListener if null
        this.listener = listener == null
                ? new VirtualHashListener() {
                    /* noop */
                }
                : listener;
        // Let the listener know we have started hashing.
        this.listener.onHashingStarted(firstLeafPath, lastLeafPath);

        final ForkJoinPool pool = Thread.currentThread() instanceof ForkJoinWorkerThread thread
                ? thread.getPool()
                : getHashingPool(virtualMapConfig);

        final ChunkHashTask rootTask = pool.invoke(ForkJoinTask.adapt(
                () -> hashImpl(hashChunkPreloader, sortedDirtyLeaves, firstLeafPath, lastLeafPath, pool)));
        if (rootTask != null) {
            try {
                rootTask.join();
            } catch (final Exception e) {
                if (!shutdown.get()) {
                    logger.error(EXCEPTION.getMarker(), "Failed to wait for all hashing tasks", e);
                    throw e;
                }
            }
        }

        this.listener.onHashingCompleted();
        this.listener = null;

        return rootTask != null ? rootTask.getResult() : null;
    }

    /**
     * Internal method calculating the hash of the tree in a given fork-join pool. This method
     * returns a root hashing task, which can be used to wait till hashing process is complete.
     *
     * @param sortedDirtyLeaves
     * 		A stream of dirty leaves sorted in <strong>ASCENDING PATH ORDER</strong>, such that path
     * 		1234 comes before 1235. If null or empty, a null hash result is returned.
     * @param firstLeafPath
     * 		The firstLeafPath of the tree that is being hashed. If &lt; 1, then a null hash result is returned.
     * 		No leaf in {@code sortedDirtyLeaves} may have a path less than {@code firstLeafPath}.
     * @param lastLeafPath
     * 		The lastLeafPath of the tree that is being hashed. If &lt; 1, then a null hash result is returned.
     * 		No leaf in {@code sortedDirtyLeaves} may have a path greater than {@code lastLeafPath}.
     * @param pool the pool to use for hashing tasks.
     * @return the root hashing task, or null if there are no dirty leaves to hash.
     */
    @SuppressWarnings("rawtypes")
    private ChunkHashTask hashImpl(
            final @NonNull LongFunction<VirtualHashChunk> hashChunkPreloader,
            final @NonNull Iterator<VirtualLeafBytes> sortedDirtyLeaves,
            final long firstLeafPath,
            final long lastLeafPath,
            final @NonNull ForkJoinPool pool) {
        if (!sortedDirtyLeaves.hasNext()) {
            // Nothing to hash.
            return null;
        } else {
            if ((firstLeafPath < 1) || (lastLeafPath < 1)) {
                throw new IllegalArgumentException("Dirty leaves stream is not empty, but leaf path range is empty");
            }
        }

        this.hashChunkPreloader = Objects.requireNonNull(hashChunkPreloader);
        this.firstLeafPath = firstLeafPath;
        this.lastLeafPath = lastLeafPath;

        // Algo v6. This version is task based, where every task is responsible for hashing a small
        // chunk of the tree. Tasks are running in a fork-join pool, which is shared across all
        // virtual maps.

        // A chunk is a small sub-tree, which is identified by a path and a height. Chunks of
        // height 1 contain two nodes: the path's two children. Chunks of height 2 contain
        // six nodes: two path's children, and four grand children. Chunk path is the path
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

        int firstLeafRank = Path.getRank(firstLeafPath);
        int lastLeafRank = Path.getRank(lastLeafPath);

        // This map contains all tasks created, but not scheduled for execution yet
        final HashMap<Long, ChunkHashTask> chunkTasks = new HashMap<>(128);

        final int rootTaskHeight = Math.min(firstLeafRank, defaultChunkHeight);
        final ChunkHashTask rootTask = new ChunkHashTask(pool, ROOT_PATH, rootTaskHeight);
        // The root task doesn't have an output. Still need to call setOut() to set the dependency
        rootTask.setOut(null);
        chunkTasks.put(ROOT_PATH, rootTask);

        final long[] stack = new long[lastLeafRank + 1];
        Arrays.fill(stack, INVALID_PATH);
        stack[0] = ROOT_PATH;

        // Iterate over all dirty leaves one by one. For every leaf, create a new task, if not
        // created. Then look up for a parent task. If it's created, it must not be executed yet,
        // as one of the inputs is this dirty leaf task. If the parent task is not created,
        // create it here.

        while (sortedDirtyLeaves.hasNext()) {
            VirtualLeafBytes<?> leaf = sortedDirtyLeaves.next();
            long curPath = leaf.path();
            // For the created leaf task, set the leaf as an input. Together with the parent task
            // below, it completes all task dependencies, so the task is executed
            final LeafHashTask leafTask = new LeafHashTask(pool, curPath, leaf);

            // The next step is to iterate over parent tasks, until an already created task
            // is met (e.g. the root task). For every parent task, check all nodes at the same
            // (parent) rank using "stack". This array contains the left  most path to the right
            // of the last task processed at the rank. All nodes at the rank between "stack"
            // and the current parent are guaranteed to be clear, since dirty leaves are sorted
            // in path order. For all these clear nodes, their parent tasks, if exist, are
            // notified to have null input hashes at the corresponding paths. This reduces the
            // number of dependencies in the parent tasks
            HashProducingTask curTask = leafTask;
            while (true) {
                final int curRank = Path.getRank(curPath);
                assert curRank > 0; // there must be a parent task

                final long lastPathAtRank = stack[curRank];
                // Stack path may be null (-1), if this is the very first dirty leaf, or
                // the current leaf is the first dirty leaf at the last leaf rank
                if (lastPathAtRank != INVALID_PATH) {
                    // Identify the parent task for the last path at stack
                    final int lastTaskAtRankParentChunkHeight = getChunkHeightForInputRank(
                            lastPathAtRank, curRank, firstLeafRank, lastLeafRank, defaultChunkHeight);
                    final long lastTaskAtRankParentPath =
                            Path.getGrandParentPath(lastPathAtRank, lastTaskAtRankParentChunkHeight);
                    final ChunkHashTask lastTaskAtRankParentTask = chunkTasks.get(lastTaskAtRankParentPath);
                    // The parent tank must exist, since it was created at the previous iteration
                    assert lastTaskAtRankParentTask != null;
                    final long lastTaskAtRankParentLastInputPath =
                            Path.getRightGrandChildPath(lastTaskAtRankParentPath, lastTaskAtRankParentChunkHeight);
                    if (curPath > lastTaskAtRankParentLastInputPath) {
                        // Mark all paths in range (last path at stack, the last input path
                        // in the parent task] as clean. The corresponding dependencies in the
                        // parent task will be set to null
                        for (long l = lastPathAtRank + 1; l <= lastTaskAtRankParentLastInputPath; l++) {
                            lastTaskAtRankParentTask.staticNullInput();
                        }
                        // If the parent task has all inputs (static nulls or dynamic task inputs),
                        // it can be removed from the map
                        if (lastTaskAtRankParentTask.allInputsInitialized()) {
                            chunkTasks.remove(lastTaskAtRankParentPath);
                        }
                    } else {
                        // curPath is in the same parent chunk as the stack path, all paths between
                        // the stack path and curPath will be handled below
                    }
                }
                stack[curRank] = curPath;

                // Now find this task's parent task
                final int parentChunkHeight =
                        getChunkHeightForInputRank(curPath, curRank, firstLeafRank, lastLeafRank, defaultChunkHeight);
                final long parentPath = Path.getGrandParentPath(curPath, parentChunkHeight);
                ChunkHashTask parentTask = chunkTasks.get(parentPath);
                final boolean parentTaskExists = parentTask != null;
                if (parentTask == null) {
                    parentTask = new ChunkHashTask(pool, parentPath, parentChunkHeight);
                    chunkTasks.put(parentPath, parentTask);
                }
                curTask.setOut(parentTask);

                // Mark all paths in range [first path in the parent task, cur path) as clean. Note that
                // the last path in stack may be in the same parent task, in this case only paths
                // greater than the last path in stack are marked
                if (lastPathAtRank != INVALID_PATH) {
                    final long parentTaskFirstInputPath = Path.getLeftGrandChildPath(parentPath, parentChunkHeight);
                    for (long l = Math.max(parentTaskFirstInputPath, lastPathAtRank + 1); l < curPath; l++) {
                        parentTask.staticNullInput();
                    }
                }

                if (parentTaskExists) {
                    break;
                }
                curPath = parentPath;
                curTask = parentTask;
            }
        }

        // After all dirty nodes are processed along with routes to the root, there may still be
        // tasks in the map. These tasks were created, but not scheduled as their input dependencies
        // aren't set yet. Examples are: tasks to the right of the sibling in "stack" created as a
        // result of walking from the last leaf on the first leaf rank to the root; similar tasks
        // created during walking from the last leaf on the last leaf rank to the root; sibling
        // tasks to the left of the very first route to the root. There are no more dirty leaves,
        // all these tasks may be marked as clean now
        chunkTasks.forEach((path, task) -> task.noMoreInputs());
        chunkTasks.clear();

        return rootTask;
    }

    public Hash emptyRootHash() {
        final MessageDigest md = Cryptography.DEFAULT_DIGEST_TYPE.buildDigest();
        md.update((byte) 0x00);
        return new Hash(md.digest(), Cryptography.DEFAULT_DIGEST_TYPE);
    }
}
