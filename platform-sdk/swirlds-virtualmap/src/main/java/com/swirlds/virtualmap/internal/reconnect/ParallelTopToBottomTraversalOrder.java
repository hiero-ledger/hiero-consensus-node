package com.swirlds.virtualmap.internal.reconnect;

import static com.swirlds.logging.legacy.LogMarker.RECONNECT;
import static com.swirlds.virtualmap.internal.Path.ROOT_PATH;

import com.swirlds.virtualmap.internal.Path;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class ParallelTopToBottomTraversalOrder implements NodeTraversalOrder {

    private static final Logger logger = LogManager.getLogger(ParallelTopToBottomTraversalOrder.class);

    private volatile boolean simpleMode = false;

    private volatile long oldFirstLeafPath;
    private volatile long oldLastLeafPath;
    private volatile long firstLeafPath;
    private volatile long lastLeafPath;

    private volatile int firstLeafRank;
    private volatile int lastLeafRank;

    private volatile int chunkRootRank;
    private volatile long chunkRootPath;
    private volatile int chunkLastRank; // Can be either firstLeafRank or lastLeafRank
    private volatile long chunkLastLeafPath;

    private final Set<Long> cleanPaths = ConcurrentHashMap.newKeySet();
    private final Set<Long> someDirtyPaths = ConcurrentHashMap.newKeySet();

    private final AtomicInteger internalsInFlight = new AtomicInteger(0);
    private final AtomicInteger maxInFlight = new AtomicInteger(0);
    private final Queue<Long> internals = new ConcurrentLinkedQueue<>();

    private final AtomicLong currentLeafPath = new AtomicLong();

    @Override
    public void start(
            final long oldFirstLeafPath,
            final long oldLastLeafPath,
            final long firstLeafPath,
            final long lastLeafPath) {
        this.oldFirstLeafPath = oldFirstLeafPath;
        this.oldLastLeafPath = oldLastLeafPath;
        this.firstLeafPath = firstLeafPath;
        this.lastLeafPath = lastLeafPath;

        currentLeafPath.set(firstLeafPath);

        firstLeafRank = Path.getRank(firstLeafPath);
        lastLeafRank = Path.getRank(lastLeafPath);
        chunkLastRank = firstLeafRank;

        if (firstLeafRank < 21) {
            simpleMode = true;
        } else {
            chunkRootRank = firstLeafRank - 20;
            chunkRootPath = Path.getGrandParentPath(firstLeafPath, firstLeafRank - chunkRootRank);
            addInitialChunkInternals(chunkRootPath);
            chunkLastLeafPath = Path.getRightGrandChildPath(chunkRootPath, firstLeafRank - chunkRootRank);
            logger.info(RECONNECT.getMarker(), "Pull start: chunk root rank = {}", chunkRootRank);
        }
    }

    private void addInitialChunkInternals(final long chunkRootPath) {
        final int skipRanks = 12;
        final long firstPath = Path.getLeftGrandChildPath(chunkRootPath, skipRanks);
        final long lastPath = Path.getRightGrandChildPath(chunkRootPath, skipRanks);
        for (long path = firstPath; path <= lastPath; path++) {
            internals.add(path);
        }
    }

    @Override
    public void nodeReceived(final long path, final boolean isClean) {
        final boolean isLeaf = path >= firstLeafPath;
        if ((path == 0) || isLeaf) {
            return;
        }
        internalsInFlight.decrementAndGet();
        if (isClean) {
            assert !cleanPaths.contains(Path.getParentPath(path));
            cleanPaths.add(path);
        } else {
            final long lastChunkInternal = Math.min(firstLeafPath - 1, Path.getParentPath(chunkLastLeafPath));
            final long left = Path.getLeftChildPath(path);
            if (left <= lastChunkInternal) {
                internals.add(left);
            }
            final long right = Path.getRightChildPath(path);
            if (right <= lastChunkInternal) {
                internals.add(right);
            } else {
                someDirtyPaths.add(path);
            }
        }
    }

    @Override
    public long getNextInternalPathToSend() {
        if (simpleMode) {
            return Path.INVALID_PATH;
        }
        long leafPath = currentLeafPath.get();
        if (leafPath < oldFirstLeafPath) {
            // Proceed to leaves
            return Path.INVALID_PATH;
        }
        if (leafPath > oldLastLeafPath) {
            // Proceed to leaves
            return Path.INVALID_PATH;
        }
        final Long internal = internals.poll();
        if (internal != null) {
            final int inFlight = internalsInFlight.incrementAndGet();
            maxInFlight.set(Math.max(maxInFlight.get(), inFlight));
            return internal;
        }
        return Path.INVALID_PATH;
    }

    @Override
    public long getNextLeafPathToSend() {
        long leafPath = currentLeafPath.get();
        if (leafPath == Path.INVALID_PATH) {
            return Path.INVALID_PATH;
        }
        if (simpleMode) {
            if (leafPath <= lastLeafPath) {
                currentLeafPath.set(leafPath + 1);
                return leafPath;
            } else {
                currentLeafPath.set(Path.INVALID_PATH);
                return Path.INVALID_PATH;
            }
        }
        if (leafPath < oldFirstLeafPath) {
            // Processing leaves before the old path range, all known to be dirty
            currentLeafPath.set(leafPath + 1);
            return leafPath;
        }
        if (leafPath > lastLeafPath) {
            logger.info(RECONNECT.getMarker(), "Max in flight: " + maxInFlight.get());
        }
        if (leafPath > lastLeafPath) {
            currentLeafPath.set(Path.INVALID_PATH);
            return Path.INVALID_PATH;
        }
        if (leafPath > oldLastLeafPath) {
            // Processing leaves after the old path range, all known to be dirty
            currentLeafPath.set(leafPath + 1);
            return leafPath;
        }
        leafPath = skipCleanPaths(leafPath, chunkLastLeafPath);
        if (leafPath == Path.INVALID_PATH) {
            leafPath = chunkLastLeafPath + 1;
            if (leafPath > lastLeafPath) {
                currentLeafPath.set(Path.INVALID_PATH);
                return Path.INVALID_PATH;
            } else {
                logger.info(RECONNECT.getMarker(), "Chunk end: some clean paths: {} some dirty paths: {} last chunk path: {}", cleanPaths.size(), someDirtyPaths.size(), chunkLastLeafPath);
                cleanPaths.clear();
                someDirtyPaths.clear();
                long nextChunkRootPath = chunkRootPath + 1;
                if (Path.getRank(nextChunkRootPath) != chunkRootRank) {
                    assert Path.getRank(nextChunkRootPath) == chunkRootRank + 1;
                    nextChunkRootPath = Path.getParentPath(nextChunkRootPath);
                    assert chunkLastRank == firstLeafRank;
                    chunkLastRank = lastLeafRank;
                }
                chunkRootPath = nextChunkRootPath;
                chunkLastLeafPath = Path.getRightGrandChildPath(chunkRootPath, chunkLastRank - chunkRootRank);
//                internals.add(chunkRootPath);
                addInitialChunkInternals(chunkRootPath);
                currentLeafPath.set(leafPath);
                return PATH_NOT_AVAILABLE_YET;
            }
        }
        final long parent = Path.getParentPath(leafPath);
        if (!someDirtyPaths.contains(parent)) {
            currentLeafPath.set(leafPath);
            return PATH_NOT_AVAILABLE_YET;
        }
        currentLeafPath.set(leafPath + 1);
        return leafPath;
    }

    private boolean hasCleanParent(final long path) {
        long parent = Path.getParentPath(path);
        boolean clean = false;
        while ((parent > 0) && !clean) {
            clean = cleanPaths.contains(parent);
            parent = Path.getParentPath(parent);
        }
        return clean;
    }

    /**
     * Skip all clean paths starting from the given path at the same rank, un until the limit. If
     * all paths are clean up to and including the limit, Path.INVALID_PATH is returned
     */
    private long skipCleanPaths(long path, final long limit) {
        long result = skipCleanPaths(path);
        while ((result < limit) && (result != path)) {
            path = result;
            result = skipCleanPaths(path);
        }
        return (result <= limit) ? result : Path.INVALID_PATH;
    }

    private final AtomicLong skippedLeaves = new AtomicLong(0);

    /**
     * For a given path, find its highest parent path in cleanNodes. If such a parent exists,
     * skip all paths at the original paths's rank in the parent sub-tree and return the first
     * path after that. If no clean parent is found, the original path is returned
     */
    private long skipCleanPaths(final long path) {
        assert path > 0;
        long parent = Path.getParentPath(path);
        long cleanParent = Path.INVALID_PATH;
        int parentRanksAbove = 1;
        int cleanParentRanksAbove = 1;
        while (parent != ROOT_PATH) {
            if (cleanPaths.contains(parent)) {
                cleanParent = parent;
                cleanParentRanksAbove = parentRanksAbove;
            }
            parentRanksAbove++;
            parent = Path.getParentPath(parent);
        }
        final long result;
        if (cleanParent == Path.INVALID_PATH) {
            // no clean parent found
            result = path;
        } else {
            result = Path.getRightGrandChildPath(cleanParent, cleanParentRanksAbove) + 1;
        }
        assert result >= path;
        skippedLeaves.addAndGet(result - path);
        return result;
    }
}
