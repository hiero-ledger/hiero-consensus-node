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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeSet;
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

    /** Number of nearby birth rounds to show around a gap in diagnostics. */
    private static final int CONTEXT_WINDOW = 5;

    private final PcesEventHashReader.PcesData pcesData;

    /**
     * Constructor for standalone use (e.g., from main method) without PCES data.
     */
    public EventHashBlockStreamValidator() {
        this(new PcesEventHashReader.PcesData(Set.of(), Map.of()));
    }

    /**
     * Constructor with PCES data for parent hash validation and diagnostics.
     *
     * @param pcesData PCES event hashes and per-creator birth round data
     */
    public EventHashBlockStreamValidator(@NonNull final PcesEventHashReader.PcesData pcesData) {
        this.pcesData = pcesData;
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
            final var pcesData = readPcesDataFromSpec(spec);
            return new EventHashBlockStreamValidator(pcesData);
        }
    };

    @Override
    public void validateBlocks(@NonNull final List<Block> blocks) {
        logger.info("Processing {} blocks for event chain verification", blocks.size());

        final BlockStreamEventBuilder eventBuilder = new BlockStreamEventBuilder(blocks);
        final var events = eventBuilder.getEvents();

        validateEventHashChain(events, eventBuilder.getCrossBlockParentRefs(), pcesData);

        logger.info("Successfully processed and verified {} events in {} blocks", events.size(), blocks.size());
    }

    /**
     * Validates the event hash chain by looking up all events that have a parent reference to an event in another
     * block. A cross-block parent hash is valid if it is found either among reconstructed block stream events or
     * among PCES event hashes (for stale events that were gossiped but never reached consensus). If a parent hash
     * is not found in either source, the validation fails with detailed diagnostics.
     *
     * @param events the list of reconstructed events
     * @param crossBlockParentRefs cross-block parent references with context about parent and child events
     * @param pcesData PCES event hashes and per-creator birth round data
     */
    static void validateEventHashChain(
            @NonNull final List<PlatformEvent> events,
            @NonNull final List<BlockStreamEventBuilder.CrossBlockParentRef> crossBlockParentRefs,
            @NonNull final PcesEventHashReader.PcesData pcesData) {
        if (events.isEmpty()) {
            fail("No events found in the block stream");
            return;
        }

        final Set<Hash> eventHashes =
                events.stream().map(PlatformEvent::getHash).collect(Collectors.toSet());

        // Build per-creator birth round index from block stream events for gap diagnostics
        final Map<Long, TreeSet<Long>> blockStreamBirthRounds = new HashMap<>();
        for (final var event : events) {
            blockStreamBirthRounds
                    .computeIfAbsent(event.getEventCore().creatorNodeId(), k -> new TreeSet<>())
                    .add(event.getBirthRound());
        }

        final List<BlockStreamEventBuilder.CrossBlockParentRef> pcesOnlyRefs = new ArrayList<>();
        final List<BlockStreamEventBuilder.CrossBlockParentRef> unresolvedRefs = new ArrayList<>();
        for (final var ref : crossBlockParentRefs) {
            if (!eventHashes.contains(ref.parentHash())) {
                if (pcesData.eventHashes().contains(ref.parentHash())) {
                    pcesOnlyRefs.add(ref);
                } else {
                    unresolvedRefs.add(ref);
                }
            }
        }

        if (!pcesOnlyRefs.isEmpty()) {
            logger.warn(
                    "{} of {} cross-block parent hashes were resolved via PCES only (stale events not in block stream):",
                    pcesOnlyRefs.size(),
                    crossBlockParentRefs.size());
            for (final var ref : pcesOnlyRefs) {
                logger.warn(
                        "  Stale parent: creator={}, birthRound={}, hash={} | child: creator={}, birthRound={}, block={}",
                        ref.parentDescriptor().creatorNodeId(),
                        ref.parentDescriptor().birthRound(),
                        ref.parentHash(),
                        ref.childCreatorId(),
                        ref.childBirthRound(),
                        ref.childBlockIndex());
            }
        }

        if (!unresolvedRefs.isEmpty()) {
            final var sb = new StringBuilder();
            for (final var ref : unresolvedRefs) {
                final long creator = ref.parentDescriptor().creatorNodeId();
                final long missingBirthRound = ref.parentDescriptor().birthRound();

                sb.append(String.format(
                        "%n  Parent creator=%d, birthRound=%d, hash=%s -> Child creator=%d, birthRound=%d, block=%d",
                        creator,
                        missingBirthRound,
                        ref.parentHash(),
                        ref.childCreatorId(),
                        ref.childBirthRound(),
                        ref.childBlockIndex()));

                // Show nearby birth rounds from block stream for this creator
                final var bsRounds = blockStreamBirthRounds.get(creator);
                if (bsRounds != null) {
                    sb.append(String.format(
                            "%n    Block stream creator %d: %d total events, range [%d..%d], nearby: %s ... [missing %d] ... %s",
                            creator,
                            bsRounds.size(),
                            bsRounds.first(),
                            bsRounds.last(),
                            nearbyRounds(bsRounds, missingBirthRound, true),
                            missingBirthRound,
                            nearbyRounds(bsRounds, missingBirthRound, false)));
                } else {
                    sb.append(String.format("%n    Block stream creator %d: no events found", creator));
                }

                // Show nearby birth rounds from PCES for this creator
                final var pcesRounds = pcesData.birthRoundsByCreator().get(creator);
                if (pcesRounds != null) {
                    sb.append(String.format(
                            "%n    PCES creator %d: %d total events, range [%d..%d], nearby: %s ... [missing %d] ... %s",
                            creator,
                            pcesRounds.size(),
                            pcesRounds.first(),
                            pcesRounds.last(),
                            nearbyRounds(pcesRounds, missingBirthRound, true),
                            missingBirthRound,
                            nearbyRounds(pcesRounds, missingBirthRound, false)));
                } else {
                    sb.append(String.format("%n    PCES creator %d: no events found", creator));
                }

                // Show which other creators have events at the missing birth round
                final var creatorsAtRound = new ArrayList<Long>();
                for (final var entry : blockStreamBirthRounds.entrySet()) {
                    if (entry.getValue().contains(missingBirthRound)) {
                        creatorsAtRound.add(entry.getKey());
                    }
                }
                sb.append(String.format(
                        "%n    Other creators with events at birthRound %d in block stream: %s",
                        missingBirthRound, creatorsAtRound.isEmpty() ? "(none)" : creatorsAtRound));
            }
            fail(
                    "%d of %d cross-block parent hashes not found in block stream or PCES:%s",
                    unresolvedRefs.size(), crossBlockParentRefs.size(), sb);
        }
    }

    /**
     * Returns a string showing birth rounds near the missing one (before or after).
     *
     * @param rounds sorted set of birth rounds
     * @param target the missing birth round
     * @param before true to show rounds before target, false for after
     * @return formatted string of nearby rounds
     */
    private static String nearbyRounds(
            @NonNull final NavigableSet<Long> rounds, final long target, final boolean before) {
        final var nearby = before ? rounds.headSet(target, false).descendingSet() : rounds.tailSet(target, false);
        final var result = nearby.stream().limit(CONTEXT_WINDOW).sorted().toList();
        return result.isEmpty() ? "(none)" : result.toString();
    }

    /**
     * Reads PCES data from all network nodes in the given spec.
     *
     * @param spec the HapiSpec providing access to network node paths
     * @return merged PCES data from all nodes
     */
    static PcesEventHashReader.PcesData readPcesDataFromSpec(@NonNull final HapiSpec spec) {
        final var mergedHashes = new java.util.HashSet<Hash>();
        final var mergedBirthRounds = new HashMap<Long, TreeSet<Long>>();
        final var nodesWithPces = new ArrayList<Long>();
        final var nodesWithoutPces = new ArrayList<Long>();
        for (final var node : spec.getNetworkNodes()) {
            final Path pcesDir =
                    node.getExternalPath(ExternalPath.PCES_DIR).toAbsolutePath().normalize();
            if (Files.exists(pcesDir)) {
                final var nodeData = PcesEventHashReader.readPcesData(pcesDir);
                mergedHashes.addAll(nodeData.eventHashes());
                nodeData.birthRoundsByCreator().forEach((creator, rounds) -> mergedBirthRounds
                        .computeIfAbsent(creator, k -> new TreeSet<>())
                        .addAll(rounds));
                nodesWithPces.add(node.getNodeId());
            } else {
                nodesWithoutPces.add(node.getNodeId());
            }
        }
        logger.info(
                "Read {} total PCES event hashes. Nodes with PCES: {}, nodes without: {}",
                mergedHashes.size(),
                nodesWithPces,
                nodesWithoutPces);
        return new PcesEventHashReader.PcesData(mergedHashes, mergedBirthRounds);
    }
}
