package com.swirlds.virtualmap.internal.reconnect;

import static com.swirlds.logging.legacy.LogMarker.RECONNECT;
import static com.swirlds.virtualmap.internal.Path.ROOT_PATH;

import com.swirlds.virtualmap.internal.Path;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class ParallelTopToBottomTraversalOrder implements NodeTraversalOrder {

    private static final Logger logger = LogManager.getLogger(ParallelTopToBottomTraversalOrder.class);

    private static final int DEFAULT_MAX_IN_FLIGHT = 1 << 16;

    private volatile boolean simpleMode = false;

    private volatile long oldFirstLeafPath;
    private volatile long oldLastLeafPath;
    private volatile long firstLeafPath;
    private volatile long lastLeafPath;

    private volatile int firstLeafRank;
    private volatile int lastLeafRank;

    private volatile int chunkRootRank;
    private volatile long chunkRootPath;
    // Can be either firstLeafRank or lastLeafRank
    private volatile int chunkLastRank;
    private volatile long chunkLastLeafPath;

    private final Set<Long> cleanPaths = ConcurrentHashMap.newKeySet();

    private final AtomicInteger internalsInFlight = new AtomicInteger(0);
    private final Queue<Long> internals = new PriorityBlockingQueue<>();

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
        internalsInFlight.set(0);

        firstLeafRank = Path.getRank(firstLeafPath);
        lastLeafRank = Path.getRank(lastLeafPath);
        chunkLastRank = firstLeafRank;

        if (firstLeafRank < 20) {
            simpleMode = true;
        } else {
            chunkRootRank = firstLeafRank - 18;
            chunkRootPath = Path.getGrandParentPath(firstLeafPath, firstLeafRank - chunkRootRank);
//            internals.add(chunkRootPath);
            addInitialChunkInternals(chunkRootPath);
            chunkLastLeafPath = Path.getRightGrandChildPath(chunkRootPath, firstLeafRank - chunkRootRank);
            logger.info(RECONNECT.getMarker(), "Pull start: chunk root rank = {}", chunkRootRank);
        }
    }

    private void addInitialChunkInternals(final long chunkRootPath) {
        final int skipRanks = 5;
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
            final int r = Path.getRank(path);
            if (chunkRootPath == Path.getGrandParentPath(path, r - chunkRootRank)) {
                if (!cleanPaths.contains(Path.getParentPath(path))) {
                    cleanPaths.add(path);
                }
            }
        }
    }

    private volatile int maxInFlight = DEFAULT_MAX_IN_FLIGHT;

    private final AtomicInteger everMaxInFlight = new AtomicInteger(0);

    private final AtomicLong skippedInternals = new AtomicLong(0);

    private final AtomicBoolean firstLeafInChunk = new AtomicBoolean(true);

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
//        if (internalsInFlight.get() >= DEFAULT_MAX_IN_FLIGHT) {
        if (internalsInFlight.get() >= maxInFlight) {
            return PATH_NOT_AVAILABLE_YET;
        }
        for (Long internal = internals.poll(); internal != null; internal = internals.poll()) {
            if (hasCleanParent(internal)) {
                skippedInternals.incrementAndGet();
                continue;
            }
            final int rank = Path.getRank(internal);
            if (Path.getRightGrandChildPath(internal, chunkLastRank - rank) < firstLeafPath) {
                skippedInternals.incrementAndGet();
                continue;
            }
            if (Path.getLeftGrandChildPath(internal, chunkLastRank - rank) > lastLeafPath) {
                skippedInternals.incrementAndGet();
                continue;
            }
            final long left = Path.getLeftChildPath(internal);
            if (left < firstLeafPath) {
                internals.add(Path.getLeftChildPath(internal));
            }
            final long right = Path.getRightChildPath(internal);
            if (right < firstLeafPath) {
                internals.add(Path.getRightChildPath(internal));
            }
            final int inFlight = internalsInFlight.incrementAndGet();
            everMaxInFlight.set(Math.max(everMaxInFlight.get(), inFlight));
            return internal;
        }
        if (firstLeafInChunk.compareAndSet(true, false)) {
            logger.info(RECONNECT.getMarker(), "First leaf, clean paths: {}, in flight: {}", cleanPaths.size(), internalsInFlight.get());
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
            logger.info(RECONNECT.getMarker(), "Skipped: {} / {}", skippedInternals.get(), skippedLeaves.get());
            logger.info(RECONNECT.getMarker(), "Max in flight: {}", everMaxInFlight.get());
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
                currentLeafPath.set(leafPath);
                logger.info(RECONNECT.getMarker(), "Chunk end: clean paths: {} in flight: {} last chunk path: {}", cleanPaths.size(), internalsInFlight.get(), chunkLastLeafPath);
                cleanPaths.clear();
                long nextChunkRootPath = chunkRootPath + 1;
                if (Path.getRank(nextChunkRootPath) != chunkRootRank) {
                    assert Path.getRank(nextChunkRootPath) == chunkRootRank + 1;
                    nextChunkRootPath = Path.getParentPath(nextChunkRootPath);
                    assert chunkLastRank == firstLeafRank;
                    chunkLastRank = lastLeafRank;
                    maxInFlight = maxInFlight * 2;
                }
                chunkRootPath = nextChunkRootPath;
                chunkLastLeafPath = Path.getRightGrandChildPath(chunkRootPath, chunkLastRank - chunkRootRank);
//                internals.add(chunkRootPath);
                addInitialChunkInternals(chunkRootPath);
                firstLeafInChunk.set(true);
                return PATH_NOT_AVAILABLE_YET;
            }
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

    private boolean isPathInSubtree(final long path, final long parent) {
        final int pathRank = Path.getRank(path);
        final int parentRank = Path.getRank(parent);
        return (pathRank >= parentRank) && (parent == Path.getGrandParentPath(path, pathRank - parentRank));
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
