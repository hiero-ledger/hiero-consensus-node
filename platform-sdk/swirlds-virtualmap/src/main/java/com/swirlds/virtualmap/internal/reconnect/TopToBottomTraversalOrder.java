// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.reconnect;

import static com.swirlds.virtualmap.internal.Path.ROOT_PATH;

import com.swirlds.common.merkle.synchronization.task.ReconnectNodeCount;
import com.swirlds.virtualmap.internal.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Virtual node traversal policy, which starts sending requests from the root node and proceeds
 * virtual tree rank by rank, top to bottom. Every node is checked to have a clean parent on
 * its path to the root. If a clean parent is found, the node is skipped (not sent to the teacher).
 *
 * <p>Clean nodes are tracked in a concurrent set. This set is populated in {@link
 * #nodeReceived(long, boolean)} on the receiving thread. Since teacher responses are always
 * lagging behind learner requests, some clean nodes may be requested redundantly. That is,
 * a request for a clean node is sent before a response for any of its clean parents is
 * received from the teacher.
 */
public class TopToBottomTraversalOrder implements NodeTraversalOrder {

    private final ReconnectNodeCount nodeCount;

    private volatile long reconnectFirstLeafPath;
    private volatile long reconnectLastLeafPath;

    // Last sent path. Initialized to 0, since the root path is always sent first
    private final AtomicLong lastPath = new AtomicLong(0);

    // Clean node paths, as received from the teacher. Only internal paths are recorded here,
    // there is no need to track clean leaves, since they don't have children. This set is
    // populated on the receiving thread and queried on the sending thread
    private final Set<MutableLong> cleanNodes = ConcurrentHashMap.newKeySet();

    private final AtomicInteger lastResponseRank = new AtomicInteger(0);

    public TopToBottomTraversalOrder(final ReconnectNodeCount nodeCount) {
        this.nodeCount = nodeCount;
    }

    @Override
    public void start(final long firstLeafPath, final long lastLeafPath) {
        this.reconnectFirstLeafPath = firstLeafPath;
        this.reconnectLastLeafPath = lastLeafPath;

        // If the tree has at least three ranks, skip some top-most ranks
        if (firstLeafPath > 2) {
            final int leafParentRank = Path.getRank(firstLeafPath) - 1;
            final int startRank = Math.min(16, leafParentRank);
            final long firstPathAtStartRank = Path.getLeftGrandChildPath(0, startRank);
            assert firstPathAtStartRank > 0;
            lastPath.set(firstPathAtStartRank - 1);
        }
    }

    @Override
    public void nodeReceived(final long path, final boolean isClean) {
        // This is not very atomic, but it isn't critical
        lastResponseRank.set(Math.max(Path.getRank(path), lastResponseRank.get()));
        final boolean isLeaf = path >= reconnectFirstLeafPath;
        if (isClean && !isLeaf && !hasCleanParent(path)) {
            cleanNodes.add(new MutableLong(path));
        }
        if (isLeaf) {
            nodeCount.incrementLeafCount();
            if (isClean) {
                nodeCount.incrementRedundantLeafCount();
            }
        } else {
            nodeCount.incrementInternalCount();
            if (isClean) {
                nodeCount.incrementRedundantInternalCount();
            }
        }
    }

    @Override
    public long getNextInternalPathToSend() {
        long wasLastPath;
        long result;
        // This method may be run in parallel on multiple threads. Only one of the threads should
        // update lastPath, that's why a loop with compareAndSet() is here
        do {
            wasLastPath = lastPath.get();
            if ((wasLastPath == Path.INVALID_PATH) || (wasLastPath >= reconnectFirstLeafPath)) {
                return Path.INVALID_PATH;
            }
            long path = wasLastPath + 1;
            // Skip as many clean paths as possible, up to the first leaf path (excluding)
            result = skipCleanPaths(path, reconnectFirstLeafPath - 1);
            while ((result != Path.INVALID_PATH) && (result != path)) {
                path = result;
                result = skipCleanPaths(path, reconnectFirstLeafPath - 1);
            }
            // If the next clean path is a leaf, return INVALID_PATH. It will trigger a call to
            // getNextLeafPathToSend() below
            if (result == Path.INVALID_PATH) {
                return Path.INVALID_PATH;
            }
        } while (!lastPath.compareAndSet(wasLastPath, result));
        // Slow down a little bit, if requests are too far ahead of responses
        applyRequestResponseBackpressure(result);
        return result;
    }

    @Override
    public long getNextLeafPathToSend() {
        if (lastPath.get() == Path.INVALID_PATH) {
            return Path.INVALID_PATH;
        }
        long path = lastPath.get() + 1;
        long result = skipCleanPaths(path, reconnectLastLeafPath);
        // Find the highest clean path and skip all paths in its sub-tree. Repeat
        while ((result != Path.INVALID_PATH) && (result != path)) {
            path = result;
            result = skipCleanPaths(path, reconnectLastLeafPath);
        }
        lastPath.set(result);
        // Slow down a little bit, if requests are too far ahead of responses
        applyRequestResponseBackpressure(result);
        return result;
    }

    private void applyRequestResponseBackpressure(final long path) {
        final int rank = Path.getRank(path);
        if (rank - lastResponseRank.get() > 1) {
            try {
                Thread.sleep(0, 1);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private boolean hasCleanParent(final long path) {
        final MutableLong parent = new MutableLong(Path.getParentPath(path));
        boolean clean = false;
        while ((parent.getValue() > 0) && !clean) {
            clean = cleanNodes.contains(parent);
            parent.update(Path.getParentPath(parent.getValue()));
        }
        return clean;
    }

    /**
     * Skip all clean paths starting from the given path at the same rank, un until the limit. If
     * all paths are clean to the very limit, Path.INVALID_PATH is returned
     */
    private long skipCleanPaths(long path, final long limit) {
        long result = skipCleanPaths(path);
        while ((result < limit) && (result != path)) {
            path = result;
            result = skipCleanPaths(path);
        }
        return (result <= limit) ? result : Path.INVALID_PATH;
    }

    /**
     * For the given path, find the highest clean parent path on the way to the root. If such
     * a clean parent is found, all paths in the parent's sub-tree at the same rank as the
     * initial path are skipped, and the next path outside of the sub-tree is returned (it may
     * also be clean, this is handled in the loop in {@link #getNextLeafPathToSend()}. Is the
     * sub-tree spans up to the last leaf path, this method returns Path.INVALID_PATH, which
     * indicates there are no more requests to the teacher to send. If no clean parents are
     * found, the initial path is returned.
     */
    private long skipCleanPaths(final long path) {
        assert path > 0;
        final MutableLong parent = new MutableLong(Path.getParentPath(path));
        long cleanParent = Path.INVALID_PATH;
        int parentRanksAbove = 1;
        int cleanParentRanksAbove = 1;
        while (parent.getValue() != ROOT_PATH) {
            if (cleanNodes.contains(parent)) {
                cleanParent = parent.getValue();
                cleanParentRanksAbove = parentRanksAbove;
            }
            parentRanksAbove++;
            parent.update(Path.getParentPath(parent.getValue()));
        }
        long result;
        if (cleanParent == Path.INVALID_PATH) {
            // no clean parent found
            result = path;
        } else {
            // If found, get the right-most path in parent's sub-tree at the initial rank
            // and return the next path
            result = Path.getRightGrandChildPath(cleanParent, cleanParentRanksAbove) + 1;
        }
        assert result >= path;
        return result;
    }

    // A small utility class to reduce memory allocations from Long.valueOf() in various loops
    // with cleanNodes lookups above
    static final class MutableLong {

        private long value;

        public MutableLong(final long value) {
            this.value = value;
        }

        public void update(final long value) {
            this.value = value;
        }

        public long getValue() {
            return value;
        }

        @Override
        public int hashCode() {
            return Long.hashCode(value);
        }

        @Override
        public boolean equals(final Object obj) {
            if (!(obj instanceof MutableLong that)) {
                return false;
            }
            return value == that.value;
        }
    }
}
