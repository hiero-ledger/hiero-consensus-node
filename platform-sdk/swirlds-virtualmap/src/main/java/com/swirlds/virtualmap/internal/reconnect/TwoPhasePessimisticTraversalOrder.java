// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.reconnect;

import static com.swirlds.virtualmap.internal.Path.ROOT_PATH;

import com.swirlds.common.merkle.synchronization.task.ReconnectNodeCount;
import com.swirlds.virtualmap.internal.Path;
import java.util.Deque;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Virtual node traversal policy to traverse the virtual tree bottom-up. It contains two phases,
 * the first includes internal nodes, the second is for leaves.
 *
 * <p>The first phase. At the leaf parent rank (the rank of the first leaf path, minus 1) all
 * internal nodes are split into chunks. Every chunk has a start rank (leaf parent rank, or
 * first leaf rank for the part of the tree on top of the last rank leaves) and width (number
 * of internal nodes in the chunk at its rank). For every chunk, its starting path is sent first,
 * this is the first path at chunk's rank.
 *
 * <p>When a response for an internal node is received from the teacher: if the node is clean,
 * and it's the left child, a request for its parent node is sent. If the parent is clean and
 * left, a request is sent for the grand parent, and so on. If the node is dirty, the next
 * request for this chunk is sent at its starting rank again, skipping all nodes in clear
 * sub-trees.
 *
 * <p>Since responses are received independently of requests, it may happen that a request
 * for every chunk are sent, but no responses are there yet. In this case, this class makes a
 * pessimistic assumption that the previously sent path is dirty, and proceeds to the next
 * path at chunk's starting rank. It may result in redundant nodes sent (a clean node is sent
 * before a response for one of its clean parents is received), but it increases overall
 * throughput.
 *
 * <p>The second phase starts when requests for all internal nodes in all chunks have been
 * sent (but responses may not have been recorded). At this phase, all leaves are traversed
 * from the first leaf path to the last leaf path, skipping paths with clean parents. At
 * this step some clean leaves may be sent redundantly, too.
 */
public class TwoPhasePessimisticTraversalOrder implements NodeTraversalOrder {

    private ReconnectNodeCount nodeCount;

    private long reconnectFirstLeafPath;
    private long reconnectLastLeafPath;

    private final Set<Long> cleanNodes = ConcurrentHashMap.newKeySet();

    // Number of parent node chunks processed in parallel in phase 1
    private int chunkCount;

    // The rank of top-most nodes in every chunk. For example, if chunks are started at
    // rank 20, and there are 512 chunks, it means chunk height is 11, and chunk stop
    // rank is 9. Top-most paths of all chunks are at rank 9
    private int chunksStopRank;

    // Start ranks for every chunk. A chunk may start at first leaf rank or first leaf rank - 1
    private AtomicReferenceArray<Integer> chunkStartRanks;

    // Start path for every chunk. This path is on the chunk's start rank
    private AtomicReferenceArray<Long> chunkStartPaths;

    // Chunk width, i.e. number of paths in the chunk, at the chunk's start rank
    private AtomicReferenceArray<Long> chunkWidths;

    // Index of the chunk, in which the last internal path at phase 1 was sent
    private int lastSentPathChunk;

    // For every chunk, a list of paths to check next. Initially this is a starting
    // path for every chunk. Then this list may contain clean nodes' parents. If for a chunk
    // this list is not empty, paths from the list are sent before "pessimistic" paths
    // for this chunk
    private AtomicReferenceArray<Deque<Long>> chunkNextToCheckPaths;

    // For every chunk, the next "pessimistic" internal node in the chunk to check. These
    // nodes are at chunk starting ranks. If such a path appears to be clean, it's skipped
    // up to the next dirty path or the end of the chunk
    private Map<Integer, Long> chunkNextPessimisticPaths;

    // Used during phase 2
    private long lastLeafPath = Path.INVALID_PATH;

    public TwoPhasePessimisticTraversalOrder() {}

    @Override
    public void start(final long firstLeafPath, final long lastLeafPath, final ReconnectNodeCount nodeCount) {
        this.reconnectFirstLeafPath = firstLeafPath;
        this.reconnectLastLeafPath = lastLeafPath;
        this.nodeCount = nodeCount;

        final int leafParentRank = Path.getRank(firstLeafPath) - 1;
        if (leafParentRank < 5) {
            chunkCount = 0;
            return; // no phase 1, just iterate over all leaves
        }

        // Higher the stop rank, less number of chunks. Half of the tree height seems to work well
        chunksStopRank = leafParentRank / 2;
        chunkCount = 1 << chunksStopRank;
        // Height of chunks starting from leaf parent rank. Chunks starting from first leaf rank
        // will be of minChunkHeight + 1 height
        final int minChunkHeight = leafParentRank - chunksStopRank;

        final long firstPathInLeafParentRank = Path.getLeftGrandChildPath(0, leafParentRank);

        chunkStartPaths = new AtomicReferenceArray<>(chunkCount);
        chunkWidths = new AtomicReferenceArray<>(chunkCount);
        chunkStartRanks = new AtomicReferenceArray<>(chunkCount);
        chunkNextToCheckPaths = new AtomicReferenceArray<>(chunkCount);
        chunkNextPessimisticPaths = new ConcurrentHashMap<>(chunkCount);
        for (int i = 0; i < chunkCount; i++) {
            final long p = firstPathInLeafParentRank + ((long) i << minChunkHeight);
            final int startRank;
            final long startPath;
            final long chunkWidth;
            if (Path.getLeftChildPath(p) + (2L << minChunkHeight) <= reconnectFirstLeafPath) {
                startRank = leafParentRank + 1;
                startPath = Path.getLeftChildPath(p);
                chunkWidth = 2L << minChunkHeight;
            } else {
                startRank = leafParentRank;
                startPath = p;
                chunkWidth = 1L << minChunkHeight;
            }
            chunkStartPaths.set(i, startPath);
            chunkStartRanks.set(i, startRank);
            chunkWidths.set(i, chunkWidth);
            chunkNextToCheckPaths.set(i, new ConcurrentLinkedDeque<>());
            chunkNextPessimisticPaths.put(i, chunkStartPaths.get(i));
        }

        lastSentPathChunk = -1;
    }

    @Override
    public void nodeReceived(final long path, final boolean isClean) {
        final boolean isLeaf = path >= reconnectFirstLeafPath;
        if (isLeaf) {
            nodeCount.incrementLeafCount();
            if (isClean) {
                nodeCount.incrementRedundantLeafCount();
            }
        } else {
            if (path != 0) {
                assert chunkCount > 0;
                final int chunk = getPathChunk(path);
                if (isClean) {
                    cleanNodes.add(path);
                    // Keep cleanNodes lean. If a parent is clean, its children are clean, too, no
                    // need to keep them in the set
                    cleanNodes.remove(Path.getLeftChildPath(path));
                    cleanNodes.remove(Path.getRightChildPath(path));
                    // If clean and left, add the parent to the list of paths to check. Even if
                    // the parent is higher in the tree than chunkStopRank
                    if ((path != 1) && Path.isLeft(path)) {
                        chunkNextToCheckPaths.get(chunk).addFirst(Path.getParentPath(path));
                    }
                } else {
                    // At the chunk start rank, every other path (i.e. all right paths) are skipped by
                    // default. If a left sibling is clean, there is no need to check the right sibling,
                    // as a request for the parent will be sent anyway. However, if the left sibling
                    // is dirty, the right sibling may be either dirty, or clean, so a request for it
                    // should be sent
                    final int chunkStartRank = chunkStartRanks.get(chunk);
                    final int pathRank = Path.getRank(path);
                    if ((pathRank == chunkStartRank) && Path.isLeft(path)) {
                        chunkNextToCheckPaths.get(chunk).addLast(path + 1);
                    }
                }
            }
            nodeCount.incrementInternalCount();
            if (isClean) {
                nodeCount.incrementRedundantInternalCount();
            }
        }
    }

    @Override
    public long getNextPathToSend() {
        long result = -1;
        if (lastLeafPath == -1) {
            for (int i = 0; i < chunkCount; i++) {
                final int chunk = (lastSentPathChunk + 1 + i) % chunkCount;
                // Check the queue first. If not empty, return a path from there (if not clean)
                final Deque<Long> toCheck = chunkNextToCheckPaths.get(chunk);
                result = toCheck.isEmpty() ? Path.INVALID_PATH : toCheck.pollFirst();
                while ((result != Path.INVALID_PATH) && hasCleanParent(result)) {
                    result = toCheck.isEmpty() ? Path.INVALID_PATH : toCheck.pollFirst();
                }
                if (result != Path.INVALID_PATH) {
                    lastSentPathChunk = chunk;
                    break;
                }
                // Otherwise proceed to the next pessimistic path at chunk start rank
                final long nextPessimisticPath = chunkNextPessimisticPaths.get(chunk);
                if (nextPessimisticPath == Path.INVALID_PATH) {
                    continue;
                }
                result = skipCleanPaths(nextPessimisticPath, getLastChunkPath(chunk));
                if (result == Path.INVALID_PATH) {
                    // All remaining paths at chunk start rank are clear. Mark the chunk as done
                    // and try the next chunk
                    chunkNextPessimisticPaths.put(chunk, Path.INVALID_PATH);
                    continue;
                }
                // Only left paths are sent at the chunk start rank. If a left path is dirty, then its
                // right sibling is scheduled to check in nodeReceived(), otherwise a parent will be
                // checked, and there is no need to send a request for the right sibling
                assert Path.isLeft(result);
                lastSentPathChunk = chunk;
                // Skip to the next left path
                long next = result + 2;
                if (next > getLastChunkPath(chunk)) {
                    next = Path.INVALID_PATH;
                }
                chunkNextPessimisticPaths.put(chunk, next);
                break;
            }
        }
        if (result == -1) {
            result = getNextLeafPath();
        }
        return result;
    }

    private long getNextLeafPath() {
        long path = lastLeafPath == Path.INVALID_PATH ? reconnectFirstLeafPath : lastLeafPath + 1;
        if ((path > reconnectLastLeafPath) || (reconnectFirstLeafPath < 0)) {
            return Path.INVALID_PATH;
        }
        long result = skipCleanPaths(path, reconnectLastLeafPath);
        assert (result == Path.INVALID_PATH) || (result >= reconnectFirstLeafPath);
        return lastLeafPath = result;
    }

    private int getPathChunk(long path) {
        int rank = Path.getRank(path);
        if (rank < chunksStopRank) {
            // This may happen if the whole chunk is clean
            path = Path.getLeftGrandChildPath(path, chunksStopRank - rank);
            rank = chunksStopRank;
        }
        final long pathAtTopRank = Path.getGrandParentPath(path, rank - chunksStopRank);
        return (int) (pathAtTopRank - Path.getLeftGrandChildPath(0, chunksStopRank));
    }

    private long getLastChunkPath(final int chunk) {
        return chunkStartPaths.get(chunk) + chunkWidths.get(chunk) - 1;
    }

    private boolean hasCleanParent(final long path) {
        long parent = Path.getParentPath(path);
        boolean clean = false;
        while ((parent > 0) && !clean) {
            clean = cleanNodes.contains(parent);
            parent = Path.getParentPath(parent);
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
            if (cleanNodes.contains(parent)) {
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
        return result;
    }
}
