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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.model.event.PlatformEvent;

/**
 * A BlockStreamValidator implementation that reassembles consensus events and verifies the hash integrity of the event
 * chain that forms the hashgraph. Specifically, it checks that events whose parents are in a previous block can be
 * found among the reconstructed events. This proves that event hashes are calculated correctly using only data from
 * the block stream, which is required for downstream consumers to reconstruct the hashgraph.
 *
 * <p>A small percentage of cross-block parent lookups may fail due to "stale" events that went through gossip but
 * never reached consensus and thus are absent from the block stream. Their children still have correct hashes because
 * they contain the full parent {@code EventDescriptor}. The validator tolerates up to
 * {@value #MAX_UNRESOLVED_PARENT_PERCENT}% of such unresolvable parents.
 */
public class EventHashBlockStreamValidator implements BlockStreamValidator {

    private static final Logger logger = LogManager.getLogger();

    /**
     * Maximum percentage of cross-block parent hashes that may be unresolvable before the validation fails.
     * Stale events (events that never reached consensus) cause a small number of parent lookups to fail;
     * a real problem with event reconstruction would affect far more lookups.
     */
    static final double MAX_UNRESOLVED_PARENT_PERCENT = 2.0;

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
        final var events = eventBuilder.getEvents();

        validateEventHashChain(events, eventBuilder.getCrossBlockParentHashes());

        logger.info("Successfully processed and verified {} events in {} blocks", events.size(), blocks.size());
    }

    /**
     * Validates the event hash chain by looking up all events that have a parent reference to an event in another
     * block. If we are unable to locate the parent event hash among the reconstructed events, it is likely a "stale"
     * event that went through gossip but never reached consensus. A small percentage of such failures is tolerated.
     *
     * @param events the list of reconstructed events
     * @param crossBlockParentHashes the set of parent hashes referencing events in other blocks
     */
    static void validateEventHashChain(
            @NonNull final List<PlatformEvent> events, @NonNull final Set<Hash> crossBlockParentHashes) {
        if (events.isEmpty()) {
            fail("No events found in the block stream");
            return;
        }

        final Set<Hash> eventHashes =
                events.stream().map(PlatformEvent::getHash).collect(Collectors.toSet());

        final List<Hash> unresolvedHashes = new ArrayList<>();
        for (final Hash crossBlockParentHash : crossBlockParentHashes) {
            if (!eventHashes.contains(crossBlockParentHash)) {
                unresolvedHashes.add(crossBlockParentHash);
            }
        }

        if (!unresolvedHashes.isEmpty()) {
            logger.warn(
                    "Could not resolve {} of {} cross-block parent hashes (likely stale events)",
                    unresolvedHashes.size(),
                    crossBlockParentHashes.size());
        }

        // Tolerate a small percentage of unresolvable parents (stale events that never reached the
        // block stream). A real problem with event reconstruction would affect far more lookups.
        final double unresolvedPercent = crossBlockParentHashes.isEmpty()
                ? 0.0
                : 100.0 * unresolvedHashes.size() / crossBlockParentHashes.size();
        if (unresolvedPercent > MAX_UNRESOLVED_PARENT_PERCENT) {
            fail(
                    "Too many unresolved cross-block parent hashes: %d of %d (%.1f%% > %.1f%% threshold). Hashes: %s",
                    unresolvedHashes.size(),
                    crossBlockParentHashes.size(),
                    unresolvedPercent,
                    MAX_UNRESOLVED_PARENT_PERCENT,
                    unresolvedHashes);
        }
    }
}
