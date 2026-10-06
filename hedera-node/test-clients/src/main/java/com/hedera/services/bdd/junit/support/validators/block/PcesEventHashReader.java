// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.validators.block;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.crypto.PbjStreamHasher;
import org.hiero.consensus.pces.impl.test.fixtures.PcesFileIteratorFactory;

/**
 * Reads PCES (Pre-Consensus Event Stream) files from disk and extracts event hashes and metadata.
 * These hashes serve as the source of truth for validating cross-block parent references in the
 * block stream, since some events may exist in PCES but never reach consensus (stale events).
 */
public final class PcesEventHashReader {

    private static final Logger logger = LogManager.getLogger();

    private PcesEventHashReader() {}

    /**
     * Result of reading PCES files: event hashes and per-creator birth round sets for diagnostics.
     *
     * @param eventHashes all event hashes found in PCES
     * @param birthRoundsByCreator mapping from creator node ID to the set of birth rounds found in PCES
     */
    public record PcesData(
            @NonNull Set<Hash> eventHashes, @NonNull Map<Long, TreeSet<Long>> birthRoundsByCreator) {}

    /**
     * Reads all PCES files from the given directory (recursively) and returns event hashes
     * and per-creator birth round data.
     *
     * @param pcesDirectory the root directory containing PCES files
     * @return PCES data containing event hashes and per-creator birth rounds
     */
    @NonNull
    public static PcesData readPcesData(@NonNull final Path pcesDirectory) {
        final Set<Hash> hashes = new HashSet<>();
        final Map<Long, TreeSet<Long>> birthRoundsByCreator = new HashMap<>();
        final PbjStreamHasher hasher = new PbjStreamHasher();
        try (final var iterator = PcesFileIteratorFactory.createIterator(pcesDirectory)) {
            while (iterator.hasNext()) {
                final var event = iterator.next();
                hasher.hashEvent(event);
                hashes.add(event.getHash());
                final long creator = event.getEventCore().creatorNodeId();
                final long birthRound = event.getBirthRound();
                birthRoundsByCreator
                        .computeIfAbsent(creator, k -> new TreeSet<>())
                        .add(birthRound);
            }
        } catch (final IOException e) {
            logger.warn("Failed to read PCES data from {}", pcesDirectory, e);
        }
        logger.info("Read {} event hashes from PCES files in {}", hashes.size(), pcesDirectory);
        return new PcesData(hashes, birthRoundsByCreator);
    }
}
