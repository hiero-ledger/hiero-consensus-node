// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.hash;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.virtualmap.internal.Path.INVALID_PATH;
import static com.swirlds.virtualmap.internal.Path.ROOT_PATH;
import static java.util.Objects.requireNonNull;

import com.swirlds.virtualmap.VirtualKey;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.VirtualValue;
import com.swirlds.virtualmap.config.VirtualMapConfig;
import com.swirlds.virtualmap.datasource.VirtualLeafRecord;
import com.swirlds.virtualmap.internal.Path;
import com.swirlds.virtualmap.internal.merkle.VirtualInternalNode;
import com.swirlds.virtualmap.internal.merkle.VirtualRootNode;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Objects;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongFunction;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.concurrent.AbstractTask;
import org.hiero.base.crypto.Cryptography;
import org.hiero.base.crypto.CryptographyProvider;
import org.hiero.base.crypto.Hash;
import org.hiero.base.crypto.HashBuilder;

/**
 * Responsible for hashing virtual merkle trees. This class is designed to work both for normal
 * hashing use cases, and also for hashing during reconnect.
 *
 * <p>There should be one {@link VirtualHasher} shared across all copies of a {@link VirtualMap}
 * "family".
 *
 * @param <K>
 * 		The {@link VirtualKey} type
 * @param <V>
 * 		The {@link VirtualValue} type
 */
public final class VirtualHasher<K extends VirtualKey, V extends VirtualValue> {
    /**
     * Use this for all logging, as controlled by the optional data/log4j2.xml file
     */
    private static final Logger logger = LogManager.getLogger(VirtualHasher.class);

    /**
     * This thread-local gets a HashBuilder that can be used for hashing on a per-thread basis.
     */
    private static final ThreadLocal<HashBuilder> HASH_BUILDER_THREAD_LOCAL =
            ThreadLocal.withInitial(() -> new HashBuilder(Cryptography.DEFAULT_DIGEST_TYPE));

    /**
     * A function to look up clean hashes by path during hashing. This function is stored in
     * a class field to avoid passing it as an arg to every hashing task.
     */
    private LongFunction<Hash> hashReader;

    /**
     * A listener to notify about hashing events. This listener is stored in a class field to
     * avoid passing it as an arg to every hashing task.
     */
    private VirtualHashListener<K, V> listener;

    /**
     * An instance of {@link Cryptography} used to hash leaves. This should be a static final
     * field, but it doesn't work very well as platform configs aren't loaded at the time when
     * this class is initialized. It would result in a cryptography instance with default (and
     * possibly wrong) configs be used by the hasher. Instead, this field is initialized in
     * the {@link #hash(LongFunction, Iterator, long, long, VirtualMapConfig)} method and used by all hashing
     * tasks.
     */
    private static final Cryptography CRYPTOGRAPHY = CryptographyProvider.getInstance();

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

    /**
     * Hash the given dirty leaves and the minimal subset of the tree necessary to produce a single root hash.
     * The root hash is returned.
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
     * @param virtualMapConfig platform configuration for VirtualMap
     * @return The hash of the root of the tree
     */
    public Hash hash(
            final LongFunction<Hash> hashReader,
            Iterator<VirtualLeafRecord<K, V>> sortedDirtyLeaves,
            final long firstLeafPath,
            final long lastLeafPath,
            final @NonNull VirtualMapConfig virtualMapConfig) {
        requireNonNull(virtualMapConfig);
        return hash(hashReader, sortedDirtyLeaves, firstLeafPath, lastLeafPath, null, virtualMapConfig);
    }

    class HashHoldingTask extends AbstractTask {

        // Input hashes. Some hashes may be null, which indicates they should be loaded from disk
        protected final Hash[] ins;

        HashHoldingTask(final ForkJoinPool pool, final int dependencies, final int numHashes) {
            super(pool, dependencies);
            ins = numHashes > 0 ? new Hash[numHashes] : null;
        }

        @Override
        protected boolean onExecute() {
            return true;
        }

        void setHash(final int index, final Hash hash) {
            ins[index] = hash;
            send();
        }
    }

    class ChunkHashTask extends HashHoldingTask {

        private final long path;

        private final int height; // 1 for 3-node chunk, 2 for 7-node chunk, and so on

        private HashHoldingTask out;

        // If not null, the task hashes the leaf. If null, the task processes the input hashes
        private VirtualLeafRecord<K, V> leaf;

        ChunkHashTask(final ForkJoinPool pool, final long path, final int height) {
            super(pool, 1 + (1 << height), height > 0 ? 1 << height : 0);
            this.height = height;
            this.path = path;
        }

        void setOut(final HashHoldingTask out) {
            this.out = out;
            send();
        }

        void setLeaf(final VirtualLeafRecord<K, V> leaf) {
            assert leaf != null && path == leaf.getPath() && height == 0;
            this.leaf = leaf;
            send();
        }

        void complete() {
            assert (leaf == null) && (ins == null || Arrays.stream(ins).allMatch(Objects::isNull));
            out.send();
        }

        @Override
        public void onException(Throwable ex) {
            if (out != null) {
                out.completeExceptionally(ex);
            }
        }

        @Override
        protected boolean onExecute() {
            final Hash hash;
            if (leaf != null) {
                hash = CRYPTOGRAPHY.digestSync(leaf);
                listener.onLeafHashed(leaf);
                listener.onNodeHashed(path, hash);
            } else {
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
                                left = hashReader.apply(rankPath + i * 2);
                            }
                            if (right == null) {
                                right = hashReader.apply(rankPath + i * 2 + 1);
                            }
                            ins[i] = hash(hashedPath, left, right);
                            listener.onNodeHashed(hashedPath, ins[i]);
                        }
                    }
                    rankPath = Path.getParentPath(rankPath);
                    len = len >> 1;
                }
                hash = ins[0];
            }
            out.setHash(getIndexInOut(), hash);
            return true;
        }

        static Hash hash(final long path, final Hash left, final Hash right) {
            final long classId = path == ROOT_PATH ? VirtualRootNode.CLASS_ID : VirtualInternalNode.CLASS_ID;
            final int serId = path == ROOT_PATH
                    ? VirtualRootNode.ClassVersion.CURRENT_VERSION
                    : VirtualInternalNode.SERIALIZATION_VERSION;
            final HashBuilder builder = HASH_BUILDER_THREAD_LOCAL.get();
            builder.reset();
            builder.update(classId);
            builder.update(serId);
            builder.update(left);
            builder.update(right);
            return builder.build();
        }

        private int getIndexInOut() {
            if (out instanceof ChunkHashTask t) {
                final long firstInPathInOut = Path.getLeftGrandChildPath(t.path, t.height);
                return (int) (path - firstInPathInOut);
            } else {
                return 0;
            }
        }
    }

    /**
     * If a dirty leaves stream is empty, returns {@code null}. If leaf path is empty, that
     * is when {@code firstLeafPath} and/or {@code lastLeafPath} are zero or less, and
     * dirty leaves stream is not empty, throws an {@link IllegalArgumentException}.
     *
     * @param hashReader A function to read hashes for clean paths
     * @param sortedDirtyLeaves A stream of leaf records, sorted by path
     * @param firstLeafPath First leaf path
     * @param lastLeafPath Last leaf path
     * @param listener Hash listener. May be null
     * @param virtualMapConfig VirtualMap config
     */
    public Hash hash(
            final LongFunction<Hash> hashReader,
            final Iterator<VirtualLeafRecord<K, V>> sortedDirtyLeaves,
            final long firstLeafPath,
            final long lastLeafPath,
            VirtualHashListener<K, V> listener,
            final @NonNull VirtualMapConfig virtualMapConfig) {
        requireNonNull(virtualMapConfig);

        // We don't want to include null checks everywhere, so let the listener be NoopListener if null
        if (listener == null) {
            listener =
                    new VirtualHashListener<>() {
                        /* noop */
                    };
        }

        ForkJoinPool hashingPool = Thread.currentThread() instanceof ForkJoinWorkerThread thread
                ? thread.getPool()
                : ForkJoinPool.commonPool();

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
        final HashMap<Long, ChunkHashTask> map = new HashMap<>();
        // The result task is never executed but used as an output dependency for
        // the root task below. When the root task is done executing, that is it produced
        // a root hash, this hash is set as an input dependency for this result task, where
        // it's read and returned in the end of this method
        final HashHoldingTask resultTask = new HashHoldingTask(hashingPool, 1, 1);
        final int rootTaskHeight = Math.min(firstLeafRank, chunkHeight);
        final ChunkHashTask rootTask = new ChunkHashTask(hashingPool, ROOT_PATH, rootTaskHeight);
        rootTask.setOut(resultTask);
        map.put(ROOT_PATH, rootTask);

        boolean firstLeaf = true;
        final long[] stack = new long[lastLeafRank + 1];
        Arrays.fill(stack, INVALID_PATH);

        // Tasks may have different heights. The root task has a default height. If the whole
        // virtual tree has fewer ranks than the default height, the root task will cover all
        // the tree (almost all, see comments below about leaf task heights)
        final int[] parentRankHeights = new int[lastLeafRank + 1];
        parentRankHeights[0] = 1;
        for (int i = 1; i <= firstLeafRank; i++) {
            parentRankHeights[i] = Math.min((i - 1) % chunkHeight + 1, i);
        }
        // Leaf tasks are different. All of them are of height 1, which means 3 dependencies:
        // output (parent task to set the leaf hash to) and two inputs (both are null, both are
        // met when a task is given a leaf). Besides that, if last leaf rank is not the same as
        // the first leaf rank, then all parent tasks for last leaf rank leaf tasks also are
        // of height 1
        if (firstLeafRank != lastLeafRank) {
            parentRankHeights[lastLeafRank] = 1;
        }

        // Iterate over all dirty leaves one by one. For every leaf, create a new task, if not
        // created. Then look up for a parent task. If it's created, it must not be executed yet,
        // as one of the inputs is this dirty leaf task. If the parent task is not created,
        // create it here.

        // For the created leaf task, set the leaf as an input. Together with the parent task
        // it completes all task dependencies, so the task is executed.

        while (sortedDirtyLeaves.hasNext()) {
            VirtualLeafRecord<K, V> leaf = sortedDirtyLeaves.next();
            long curPath = leaf.getPath();
            ChunkHashTask curTask = map.remove(curPath);
            if (curTask == null) {
                curTask = new ChunkHashTask(hashingPool, curPath, 0);
            }
            curTask.setLeaf(leaf);

            // The next step is to iterate over parent tasks, until an already created task
            // is met (e.g. the root task). For every parent task, check all already created
            // tasks at the same (parent) rank using "stack". This array contains the left
            // most path to the right of the last task processed at the rank. All tasks at
            // the rank between "stack" and the current parent are guaranteed to be clear,
            // since dirty leaves are sorted in path order. All such tasks are set "null"
            // input dependency, which is propagated to their parent (output) tasks.

            while (true) {
                final int curRank = Path.getRank(curPath);
                final int chunkWidth = 1 << parentRankHeights[curRank];
                // If some tasks have been created at this rank, they can now be marked as
                // clean. No dirty leaves in the remaining stream may affect these tasks
                if (stack[curRank] != INVALID_PATH) {
                    long curStackPath = stack[curRank];
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
                        final ChunkHashTask t = map.remove(curStackPath);
                        assert t != null;
                        t.complete();
                    }
                }

                // If the out is already set at this rank, all parent tasks and siblings are already
                // processed, so break the loop
                if (curTask.out != null) {
                    break;
                }

                final long parentPath = Path.getGrandParentPath(curPath, parentRankHeights[curRank]);
                ChunkHashTask parentTask = map.remove(parentPath);
                if (parentTask == null) {
                    parentTask = new ChunkHashTask(hashingPool, parentPath, parentRankHeights[curRank]);
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
                        parentTask.setHash((int) (siblingPath - firstSiblingPath), Cryptography.NULL_HASH);
                    } else if ((siblingPath < curPath) && !firstLeaf) {
                        // Mark the sibling as clean, reducing the number of dependencies
                        parentTask.send();
                    } else {
                        // Get or create a sibling task
                        final int siblingHeight;
                        if (curTask.height == 0) {
                            siblingHeight = siblingPath < firstLeafPath ? 1 : 0;
                        } else {
                            siblingHeight = curTask.height;
                        }
                        ChunkHashTask siblingTask = map.computeIfAbsent(
                                siblingPath, path -> new ChunkHashTask(hashingPool, path, siblingHeight));
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
        map.forEach((path, task) -> task.complete());
        map.clear();

        try {
            resultTask.join();
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

        return resultTask.ins[0];
    }

    public Hash emptyRootHash() {
        return ChunkHashTask.hash(ROOT_PATH, Cryptography.NULL_HASH, Cryptography.NULL_HASH);
    }
}
