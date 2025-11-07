// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.tools;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.node.app.hapi.utils.blocks.BlockStreamAccess;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.stream.Collectors;

/**
 * Scans a directory of block files and prints the top-N largest BlockItem sizes,
 * including the item type and source block file.
 *
 * Usage:
 *   java com.hedera.services.bdd.tools.BlockItemSizeInspector <directory> [topN]
 *
 * - directory: path containing *.blk or *.blk.gz files (and corresponding marker files)
 * - topN (optional): number of results to print (default 10)
 */
public final class BlockItemSizeInspector {

    private record Entry(long sizeBytes, String type, String fileName, long blockNumber) {}

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: BlockItemSizeInspector <directory> [topN]");
            System.exit(1);
        }

        final Path dir = Path.of(args[0]);
        if (!Files.exists(dir) || !Files.isDirectory(dir)) {
            System.err.println("Not a directory: " + dir);
            System.exit(1);
        }

        final int topN = (args.length >= 2) ? parseTopN(args[1]) : 10;

        final List<Path> blockFiles = listOrderedBlockFiles(dir);
        final PriorityQueue<Entry> heap = new PriorityQueue<>(Comparator.comparingLong(Entry::sizeBytes));

        for (Path path : blockFiles) {
            final Block block = BlockStreamAccess.blockFrom(path);
            final long blockNumber = BlockStreamAccess.extractBlockNumber(path);
            final String fileName = path.getFileName().toString();

            for (BlockItem item : block.items()) {
                final long size = sizeOf(item);
                final String type = itemType(item);
                final Entry e = new Entry(size, type, fileName, blockNumber);
                heap.offer(e);
                if (heap.size() > topN) {
                    heap.poll(); // keep only top-N by size
                }
            }
        }

        final List<Entry> results = new ArrayList<>(heap);
        results.sort(Comparator.comparingLong(Entry::sizeBytes).reversed());

        System.out.println("size_bytes\ttype\tblock_file\tblock_number");
        for (Entry e : results) {
            System.out.printf("%d\t%s\t%s\t%d%n", e.sizeBytes(), e.type(), e.fileName(), e.blockNumber());
        }
    }

    private static int parseTopN(@NonNull String arg) {
        try {
            final int v = Integer.parseInt(arg);
            return (v <= 0) ? 10 : Math.min(v, 1000);
        } catch (NumberFormatException e) {
            return 10;
        }
    }

    private static List<Path> listOrderedBlockFiles(@NonNull Path dir) throws Exception {
        try (var stream = Files.walk(dir)) {
            return stream
                    .filter(p -> BlockStreamAccess.isBlockFile(p, true))
                    .sorted(Comparator.comparingLong(BlockStreamAccess::extractBlockNumber))
                    .collect(Collectors.toList());
        }
    }

    private static long sizeOf(@NonNull BlockItem item) {
        final Bytes bytes = BlockItem.PROTOBUF.toBytes(item);
        return bytes.length();
    }

    private static String itemType(@NonNull BlockItem item) {
        if (item.hasBlockHeader()) return "blockHeader";
        if (item.hasSignedTransaction()) return "signedTransaction";
        if (item.hasStateChanges()) return "stateChanges";
        if (item.hasBlockProof()) return "blockProof";
        return "unknown";
    }
}


