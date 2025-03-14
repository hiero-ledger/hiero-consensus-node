// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.crypto;

import static com.hedera.node.config.types.StreamMode.RECORDS;
import static com.hedera.services.bdd.junit.hedera.ExternalPath.BLOCK_STREAMS_DIR;
import static com.hedera.services.bdd.junit.support.BlockStreamAccess.BLOCK_STREAM_ACCESS;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.BlockProof;
import com.hedera.pbj.runtime.io.buffer.Bytes;
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

        // Node 0 should always be the stream with the ISS. Verify its stream first
        final var deviatingBlockStream = blocksPerNode.getFirst();
        verifyExpectedIssStream(deviatingBlockStream);

        // Verify the non-ISS block streams
        int freezeThreshold = deviatingBlockStream.size() - 1 - 3;
        for (int i = 1; i < blocksPerNode.size(); i++) { // i = the node id
            final List<Block> nodeBlocks = blocksPerNode.get(i);

            // Assert the stream has a freeze transaction
            Assertions.assertTrue(
                    hasFreezeWithProof(nodeBlocks, freezeThreshold),
                    "No freeze transaction found in stream for node " + i);

            // Assert the last block has a non-empty block proof
            final Block last = nodeBlocks.getLast();
            final Bytes actualSig = last.items().getLast().blockProofOrThrow().blockSignature();
            Assertions.assertNotEquals(Bytes.EMPTY, actualSig);
            Assertions.assertTrue(actualSig.length() > 0);

            // Assert that this node has more blocks than the ISS node, since it should have continued processing
            // transactions
            Assertions.assertTrue(
                    nodeBlocks.size() > deviatingBlockStream.size(),
                    "Non-ISS node " + i + " should have more blocks than ISS node 0");
        }

        return false;
    }

    private boolean hasFreezeWithProof(final List<Block> blocks, int startIndex) {
        boolean foundFreeze = false;
        for (int i = startIndex; i < blocks.size(); i++) {
            final List<BlockItem> items = blocks.get(i).items();
            for (final BlockItem item : items) {
                if (!foundFreeze && item.hasEventTransaction()) {
                    final var appTxn = item.eventTransactionOrThrow().applicationTransactionOrThrow();
                    final var txnBody = TransactionParts.from(appTxn).body();
                    if (txnBody.hasFreeze()) {
                        foundFreeze = true;
                    }
                }
                if (foundFreeze && item.hasBlockProof()) {
                    return item.blockProofOrThrow().blockSignature().length() > 0;
                }
            }
        }

        return false;
    }

    private void verifyExpectedIssStream(final List<Block> deviatingBlockStream) {
        // Node 0 should always be the stream with the ISS. Verify its stream first
        final Block issLast = deviatingBlockStream.getLast();
        final BlockProof lastIssProof = issLast.items().getLast().blockProof();
        // It doesn't matter if the block proof item is empty, just that it's parseable/exists
        // (FUTURE) parse the processed round numbers from the log and assert corresponding blocks can be parsed
        Assertions.assertNotNull(lastIssProof);
        Assertions.assertNotEquals(BlockProof.DEFAULT, lastIssProof);
        Assertions.assertTrue(lastIssProof.siblingHashes().isEmpty());
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
