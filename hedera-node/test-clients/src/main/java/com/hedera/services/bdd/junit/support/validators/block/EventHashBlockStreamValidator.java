// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.validators.block;

import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.workingDirFor;
import static org.assertj.core.api.Fail.fail;

import com.hedera.hapi.block.stream.Block;
import com.hedera.node.app.hapi.utils.blocks.BlockStreamAccess;
import com.hedera.services.bdd.junit.support.BlockStreamValidator;
import com.hedera.services.bdd.spec.HapiSpec;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.model.event.PlatformEvent;

/**
 * A BlockStreamValidator implementation that reassembles consensus events and verifies the hash integrity of the event
 * chain that forms the hashgraph. Specifically, it checks that events whose parents are in a previous block are found.
 * Doing so proves that the hash of the parent event was properly calculated. This is the best check we can do, since
 * parents within a block are referenced by an index and not the hash.
 */
public class EventHashBlockStreamValidator implements BlockStreamValidator {

    private static final Logger logger = LogManager.getLogger();

    /**
     * A main method to run a standalone validation of the block stream files produced by HAPI tests in their default
     * location.
     *
     * @param args unused
     * @throws IOException if there is an error reading the block or record streams
     */
    public static void main(@NonNull final String[] args) throws IOException {
        final var node0Data = Paths.get("hedera-node/test-clients")
                .resolve(workingDirFor(0, "hapi").resolve("data"))
                .toAbsolutePath()
                .normalize();
        final var blocksLoc =
                node0Data.resolve("blockStreams/block-11.12.3").toAbsolutePath().normalize();
        final var blocks = BlockStreamAccess.BLOCK_STREAM_ACCESS.readBlocks(blocksLoc);

        final var validator = new EventHashBlockStreamValidator();
        validator.validateBlocks(blocks);
    }

    /**
     * Factory for creating EventHashBlockStreamValidator instances.
     */
    public static final Factory FACTORY = new Factory() {
        @Override
        public boolean appliesTo(@NonNull final HapiSpec spec) {
            // Apply to all specs by default, but could be configured based on spec properties
            return true;
        }

        @Override
        @NonNull
        public BlockStreamValidator create(@NonNull final HapiSpec spec) {
            return new EventHashBlockStreamValidator();
        }
    };

    @Override
    public void validateBlocks(@NonNull final List<Block> blocks) {
        logger.info("Processing {} blocks for event chain verification", blocks.size());

        final BlockStreamEventBuilder eventBuilder = new BlockStreamEventBuilder(blocks);

        validateEventHashChain(eventBuilder.getEvents(), eventBuilder.getCrossBlockParentHashes());

        logger.info(
                "Successfully processed and verified {} events in {} blocks",
                eventBuilder.getEvents().size(),
                blocks.size());
    }

    /**
     * Validates the event hash chain by looking up all events that have a parent reference to an event in another
     * block. If we are unable to locate the parent event hash among the reconstructed events, the validation fails.
     *
     * @param events the list of reconstructed events
     */
    static void validateEventHashChain(
            @NonNull final List<PlatformEvent> events, @NonNull final Set<Hash> crossBlockParentHashes) {
        if (events.isEmpty()) {
            fail("No events found in the block stream");
            return;
        }

        final Set<Hash> eventHashes =
                events.stream().map(PlatformEvent::getHash).collect(Collectors.toSet());

        for (final Hash crossBlockParentHash : crossBlockParentHashes) {
            if (!eventHashes.contains(crossBlockParentHash)) {
                fail("Cross block parent hash {} not found among event hashes!", crossBlockParentHash);
            }
        }
    }
}
