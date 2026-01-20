// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.crypto;

import static com.hedera.node.app.hapi.utils.blocks.BlockStreamAccess.BLOCK_STREAM_ACCESS;
import static com.hedera.node.config.types.StreamMode.RECORDS;
import static com.hedera.services.bdd.junit.hedera.ExternalPath.BLOCK_STREAMS_DIR;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.block.stream.Block;
import com.hedera.services.bdd.junit.support.translators.inputs.TransactionParts;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.utilops.UtilOp;
import edu.umd.cs.findbugs.annotations.NonNull;
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
                // Assert that this node has more blocks than the ISS node, since it should have continued processing
                // transactions
                assertTrue(
                        nodeBlocks.size() > deviatingBlockStream.size(),
                        "Non-ISS node" + i + " should have more blocks than ISS node" + ISS_NODE_ID);
            }
        }
        return false;
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
