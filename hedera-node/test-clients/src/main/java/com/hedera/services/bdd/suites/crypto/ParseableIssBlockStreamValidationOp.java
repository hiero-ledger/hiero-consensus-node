// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.crypto;

import static com.hedera.node.app.hapi.utils.blocks.BlockStreamAccess.BLOCK_STREAM_ACCESS;
import static com.hedera.node.config.types.StreamMode.RECORDS;
import static com.hedera.services.bdd.junit.hedera.ExternalPath.BLOCK_STREAMS_DIR;
import static com.hedera.services.bdd.junit.hedera.ExternalPath.BLOCK_STREAMS_PARENT_DIR;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.block.stream.Block;
import com.hedera.services.bdd.junit.support.translators.inputs.TransactionParts;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.utilops.UtilOp;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Assertions;

/**
 * A {@link UtilOp} that validates the streams produced in an ISS scenario, where the ISS node
 * block stream diverges from the block streams of the other nodes.
 */
// (FUTURE) Split up into more granular ops
public class ParseableIssBlockStreamValidationOp extends UtilOp {
    private static final Logger log = LogManager.getLogger(ParseableIssBlockStreamValidationOp.class);

    public static final long ISS_NODE_ID = 1;

    public static void main(String[] args) {}

    @Override
    protected boolean submitOp(@NonNull final HapiSpec spec) throws Throwable {
        // Skip if the network is in RECORDS stream mode (not applicable)
        if (spec.startupProperties().getStreamMode("blockStream.streamMode") == RECORDS) {
            log.warn("Skipping validation since the network is in record stream mode");
            return false;
        }

        // Verify we can find the right number of streams
        final var blockPaths = spec.getNetworkNodes().stream()
                .map(node -> node.getExternalPath(BLOCK_STREAMS_DIR))
                .map(Path::toAbsolutePath)
                .map(Path::toString)
                .toList();
        final List<List<Block>> blocksPerNode = readBlockStreamsFor(blockPaths);
        if (blocksPerNode.isEmpty()) {
            Assertions.fail("Block stream validation failed: No blocks found");
        }
        if (blocksPerNode.size() != spec.getNetworkNodes().size()) {
            log.warn(
                    "Block streams found for {} nodes, but the network has {} nodes",
                    blocksPerNode.size(),
                    spec.getNetworkNodes().size());
        }

        // We cause the ISS in node1
        final var deviatingBlockStream = blocksPerNode.get(1);

        // Verify the non-ISS block streams
        for (int i = 0, n = blocksPerNode.size(); i < n; i++) {
            if (i != ISS_NODE_ID) {
                final var nodeBlocks = blocksPerNode.get(i);
                // Assert the stream has a freeze transaction
                assertTrue(hasFreeze(nodeBlocks), "No freeze transaction found in stream for node" + i);
                // Assert that this node has more complete blocks than the ISS node. The ISS node keeps streaming
                // until it reaches CATASTROPHIC_FAILURE, while the others continue all the way through the freeze.
                assertTrue(
                        nodeBlocks.size() > deviatingBlockStream.size(),
                        "Non-ISS node" + i + " should have more blocks than ISS node" + ISS_NODE_ID);
            }
        }

        // Rather than stopping at ISS detection, the ISS node continues until CATASTROPHIC_FAILURE and
        // then flushes the contents of any open/pending blocks to disk for triage. Verify those pending artifacts
        // (.pnd.gz / .pnd.json) are present on the ISS node's disk. We scan the node's whole blockStreams/ tree
        // (including any cutover/archive subdirectories) for robustness. This relies on the ISS node having at least
        // one block still awaiting its proof when it failed, which is expected given asynchronous block signing; the
        // deterministic backstop that the flush ran at all is the "Block stream fatal shutdown complete" log asserted
        // in IssHandlingTest.
        final var issNodeBlockDir =
                spec.getNetworkNodes().get((int) ISS_NODE_ID).getExternalPath(BLOCK_STREAMS_PARENT_DIR);
        assertTrue(
                hasPendingBlockArtifacts(issNodeBlockDir),
                "ISS node" + ISS_NODE_ID
                        + " should have flushed pending/open block artifacts (.pnd.gz/.pnd.json) to "
                        + issNodeBlockDir.toAbsolutePath());
        return false;
    }

    /**
     * Returns true if the given block-stream directory contains any pending-block artifacts flushed for triage,
     * i.e. files ending in {@code .pnd.gz}, {@code .pnd}, or {@code .pnd.json}.
     *
     * @param blockDir the node's block stream directory to scan
     * @return true if any pending-block artifact is present
     */
    private static boolean hasPendingBlockArtifacts(@NonNull final Path blockDir) {
        if (!Files.exists(blockDir)) {
            return false;
        }
        try (final var paths = Files.walk(blockDir)) {
            return paths.map(p -> p.getFileName().toString())
                    .anyMatch(name -> name.endsWith(".pnd.gz") || name.endsWith(".pnd") || name.endsWith(".pnd.json"));
        } catch (final Exception e) {
            log.warn("Failed to scan for pending block artifacts in {}", blockDir, e);
            return false;
        }
    }

    /**
     * Checks if the given list of blocks contains a freeze transaction.
     * @param blocks the blocks to check
     * @return true if a freeze transaction is found, false otherwise
     */
    private boolean hasFreeze(@NonNull final List<Block> blocks) {
        for (final var block : blocks) {
            for (final var item : block.items()) {
                if (item.hasSignedTransaction()) {
                    final var txnBody = TransactionParts.from(item.signedTransactionOrThrow())
                            .body();
                    if (txnBody.hasFreeze()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Reads the block streams for the given paths and returns them as an ordered collection of blocks
     * @param blockPaths the paths to search for block streams
     * @return the blocks found for each node. The index of the outer list corresponds to the node ids as
     * returned by {@link HapiSpec#getNetworkNodes()}
     */
    private static List<List<Block>> readBlockStreamsFor(List<String> blockPaths) {
        final List<List<Block>> blocksByNode = new ArrayList<>();
        final StringBuilder errors = new StringBuilder();
        for (final var path : blockPaths) {
            List<Block> singleNodeBlocks = null;
            try {
                log.info("Trying to read blocks from {}", path);
                singleNodeBlocks = BLOCK_STREAM_ACCESS.readBlocks(Path.of(path));
                log.info("Read {} blocks from {}", singleNodeBlocks.size(), path);
            } catch (Exception e) {
                final String message = "Failed to read blocks from '" + path + "' due to '" + e.getMessage() + "'";
                log.error(message, path, e);

                errors.append(message).append("\n");
            }
            if (singleNodeBlocks != null && !singleNodeBlocks.isEmpty()) {
                blocksByNode.add(singleNodeBlocks);
            } else {
                errors.append("Failed to read blocks from '").append(path).append("'\n");
            }
        }
        if (!errors.isEmpty()) {
            Assertions.fail(errors.toString());
        }

        return blocksByNode;
    }
}
