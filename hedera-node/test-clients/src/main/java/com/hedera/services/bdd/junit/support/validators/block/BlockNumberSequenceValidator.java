// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.validators.block;

import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.workingDirFor;

import com.hedera.hapi.block.stream.Block;
import com.hedera.services.bdd.junit.support.BlockStreamAccess;
import com.hedera.services.bdd.junit.support.BlockStreamValidator;
import com.hedera.services.bdd.spec.HapiSpec;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Assertions;

/**
 * A validator that ensures block numbers are sequential and not repeated in the block stream.
 */
public class BlockNumberSequenceValidator implements BlockStreamValidator {
    private static final Logger logger = LogManager.getLogger(BlockNumberSequenceValidator.class);

    public static final Factory FACTORY = new Factory() {
        @NonNull
        @Override
        public BlockStreamValidator create(@NonNull final HapiSpec spec) {
            return new BlockNumberSequenceValidator();
        }
    };

    public static void main(String[] args) {
        final var node0Dir = Paths.get("hedera-node/test-clients")
                .resolve(workingDirFor(0, "hapi"))
                .toAbsolutePath()
                .normalize();
        final var blocks =
                BlockStreamAccess.BLOCK_STREAM_ACCESS.readBlocks(node0Dir.resolve("data/blockStreams/block-0.0.3"));
        new BlockNumberSequenceValidator().validateBlocks(blocks);
    }

    @Override
    public void validateBlocks(@NonNull final List<Block> blocks) {
        logger.info("Beginning validation of block number sequence");

        Set<Long> seenBlockNumbers = new HashSet<>();
        long expectedBlockNumber = -1;

        for (int i = 0; i < blocks.size(); i++) {
            Block block = blocks.get(i);
            long currentBlockNumber = Objects.requireNonNull(
                            block.items().getFirst().blockHeader())
                    .number();

            // Check if block number is repeated
            if (!seenBlockNumbers.add(currentBlockNumber)) {
                Assertions.fail(String.format("Block number %d is repeated at position %d", currentBlockNumber, i));
            }

            // Check if block number is sequential
            if (expectedBlockNumber >= 0 && currentBlockNumber != expectedBlockNumber) {
                Assertions.fail(String.format(
                        "Block number %d at position %d is not sequential. Expected %d",
                        currentBlockNumber, i, expectedBlockNumber));
            }

            expectedBlockNumber = currentBlockNumber + 1;
        }

        logger.info("Block number sequence validation completed successfully");
    }
}
