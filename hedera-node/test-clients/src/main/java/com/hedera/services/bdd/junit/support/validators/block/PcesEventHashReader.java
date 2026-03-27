// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.validators.block;

import com.hedera.hapi.platform.event.GossipEvent;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.crypto.PbjStreamHasher;
import org.hiero.consensus.model.event.EventOrigin;
import org.hiero.consensus.model.event.PlatformEvent;

/**
 * Reads PCES (Pre-Consensus Event Stream) files from disk and extracts event hashes and metadata.
 * These hashes serve as the source of truth for validating cross-block parent references in the
 * block stream, since some events may exist in PCES but never reach consensus (stale events).
 */
public final class PcesEventHashReader {

    private static final Logger logger = LogManager.getLogger();

    /** PCES file version for protobuf-serialized events. Must match PcesFileVersion.PROTOBUF_EVENTS (= 2). */
    private static final int PROTOBUF_EVENTS_VERSION = 2;

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
        try (final Stream<Path> paths = Files.walk(pcesDirectory)) {
            paths.filter(p -> p.toString().endsWith(".pces")).sorted().forEach(pcesFile -> {
                try {
                    readEventsFromFile(pcesFile, hashes, birthRoundsByCreator);
                } catch (final IOException e) {
                    logger.warn("Failed to read PCES file {}", pcesFile, e);
                }
            });
        } catch (final IOException e) {
            logger.warn("Failed to walk PCES directory {}", pcesDirectory, e);
        }
        logger.info("Read {} event hashes from PCES files in {}", hashes.size(), pcesDirectory);
        return new PcesData(hashes, birthRoundsByCreator);
    }

    /**
     * Reads events from a single PCES file and populates hashes and birth round tracking.
     */
    private static void readEventsFromFile(
            @NonNull final Path pcesFile,
            @NonNull final Set<Hash> hashes,
            @NonNull final Map<Long, TreeSet<Long>> birthRoundsByCreator)
            throws IOException {
        final PbjStreamHasher hasher = new PbjStreamHasher();
        try (final DataInputStream dis =
                new DataInputStream(new BufferedInputStream(new FileInputStream(pcesFile.toFile())))) {
            final int version = dis.readInt();
            if (version != PROTOBUF_EVENTS_VERSION) {
                logger.warn("Unsupported PCES file version {} in {}", version, pcesFile);
                return;
            }
            while (true) {
                try {
                    final int size = dis.readInt();
                    if (size < 0) {
                        logger.warn("Negative event size {} in PCES file {}, stopping read", size, pcesFile);
                        break;
                    }
                    final byte[] bytes = dis.readNBytes(size);
                    if (bytes.length < size) {
                        break;
                    }
                    final GossipEvent gossipEvent = GossipEvent.PROTOBUF.parse(Bytes.wrap(bytes));
                    final PlatformEvent event = new PlatformEvent(gossipEvent, EventOrigin.STORAGE);
                    hasher.hashEvent(event);
                    hashes.add(event.getHash());
                    final long creator = event.getEventCore().creatorNodeId();
                    final long birthRound = event.getBirthRound();
                    birthRoundsByCreator
                            .computeIfAbsent(creator, k -> new TreeSet<>())
                            .add(birthRound);
                } catch (final EOFException e) {
                    break;
                } catch (final ParseException | NullPointerException e) {
                    logger.warn("Failed to parse event in PCES file {}", pcesFile, e);
                    break;
                }
            }
        }
    }
}
