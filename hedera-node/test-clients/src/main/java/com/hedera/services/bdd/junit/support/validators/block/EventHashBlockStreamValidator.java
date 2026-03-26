// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.validators.block;

import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.workingDirFor;
import static org.assertj.core.api.Fail.fail;

import com.hedera.hapi.block.stream.Block;
import com.hedera.node.app.hapi.utils.blocks.BlockStreamAccess;
import com.hedera.services.bdd.junit.hedera.ExternalPath;
import com.hedera.services.bdd.junit.support.BlockStreamValidator;
import com.hedera.services.bdd.spec.HapiSpec;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.model.event.PlatformEvent;

/**
 * A BlockStreamValidator implementation that reassembles consensus events and verifies the hash integrity of the event
 * chain that forms the hashgraph. Specifically, it checks that events whose parents are in a previous block are found
 * either among the reconstructed block stream events or among events read from PCES files (which serve as the source
 * of truth, since some events may exist in PCES but never reach consensus).
 */
public class EventHashBlockStreamValidator implements BlockStreamValidator {

    private static final Logger logger = LogManager.getLogger();

    private final Set<Hash> pcesEventHashes;

    /**
     * Constructor for standalone use (e.g., from main method) without PCES hashes.
     */
    public EventHashBlockStreamValidator() {
        this(Set.of());
    }

    /**
     * Constructor with PCES event hashes for parent hash validation.
     *
     * @param pcesEventHashes known-valid event hashes from PCES files
     */
    public EventHashBlockStreamValidator(@NonNull final Set<Hash> pcesEventHashes) {
        this.pcesEventHashes = pcesEventHashes;
    }

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
     * Factory for creating EventHashBlockStreamValidator instances. Reads PCES files from the spec's
     * network nodes to use as the source of truth for parent hash validation.
     */
    public static final Factory FACTORY = new Factory() {
        @Override
        public boolean appliesTo(@NonNull final HapiSpec spec) {
            return true;
        }

        @Override
        @NonNull
        public BlockStreamValidator create(@NonNull final HapiSpec spec) {
            final Set<Hash> pcesHashes = readPcesHashesFromSpec(spec);
            return new EventHashBlockStreamValidator(pcesHashes);
        }
    };

    @Override
    public void validateBlocks(@NonNull final List<Block> blocks) {
        logger.info("Processing {} blocks for event chain verification", blocks.size());

        final BlockStreamEventBuilder eventBuilder = new BlockStreamEventBuilder(blocks, pcesEventHashes);
        final var events = eventBuilder.getEvents();

        validateEventHashChain(events, eventBuilder.getCrossBlockParentHashes(), pcesEventHashes);

        logger.info("Successfully processed and verified {} events in {} blocks", events.size(), blocks.size());
    }

    /**
     * Validates the event hash chain by looking up all events that have a parent reference to an event in another
     * block. A cross-block parent hash is valid if it is found either among reconstructed block stream events or
     * among PCES event hashes (for events that were gossiped but never reached consensus).
     *
     * @param events the list of reconstructed events
     * @param crossBlockParentHashes the set of parent hashes referencing events in other blocks
     * @param pcesEventHashes known-valid event hashes from PCES files
     */
    static void validateEventHashChain(
            @NonNull final List<PlatformEvent> events,
            @NonNull final Set<Hash> crossBlockParentHashes,
            @NonNull final Set<Hash> pcesEventHashes) {
        if (events.isEmpty()) {
            fail("No events found in the block stream");
            return;
        }

        final Set<Hash> eventHashes =
                events.stream().map(PlatformEvent::getHash).collect(Collectors.toSet());

        final List<Hash> pcesOnlyHashes = new ArrayList<>();
        for (final Hash crossBlockParentHash : crossBlockParentHashes) {
            if (!eventHashes.contains(crossBlockParentHash)) {
                if (pcesEventHashes.contains(crossBlockParentHash)) {
                    pcesOnlyHashes.add(crossBlockParentHash);
                } else {
                    fail("Cross block parent hash {} not found among event or PCES hashes!", crossBlockParentHash);
                }
            }
        }

        if (!pcesOnlyHashes.isEmpty()) {
            logger.warn(
                    "{} of {} cross-block parent hashes were resolved via PCES only (stale events not in block stream)",
                    pcesOnlyHashes.size(),
                    crossBlockParentHashes.size());
        }
    }

    /**
     * Reads PCES event hashes from all network nodes in the given spec.
     *
     * @param spec the HapiSpec providing access to network node paths
     * @return the union of all PCES event hashes across all nodes
     */
    static Set<Hash> readPcesHashesFromSpec(@NonNull final HapiSpec spec) {
        final Set<Hash> allHashes = new HashSet<>();
        for (final var node : spec.getNetworkNodes()) {
            final Path pcesDir =
                    node.getExternalPath(ExternalPath.PCES_DIR).toAbsolutePath().normalize();
            if (Files.exists(pcesDir)) {
                allHashes.addAll(PcesEventHashReader.readEventHashes(pcesDir));
            }
        }
        logger.info("Read {} total PCES event hashes from all network nodes", allHashes.size());
        return allHashes;
    }
}
