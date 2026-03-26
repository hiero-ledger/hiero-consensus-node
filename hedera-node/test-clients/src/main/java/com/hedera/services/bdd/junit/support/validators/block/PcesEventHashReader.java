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
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.crypto.PbjStreamHasher;
import org.hiero.consensus.model.event.EventOrigin;
import org.hiero.consensus.model.event.PlatformEvent;

/**
 * Reads PCES (Pre-Consensus Event Stream) files from disk and extracts event hashes. These hashes
 * serve as the source of truth for validating cross-block parent references in the block stream,
 * since some events may exist in PCES but never reach consensus (stale events).
 */
public final class PcesEventHashReader {

    private static final Logger logger = LogManager.getLogger();

    /** PCES file version for protobuf-serialized events. Must match PcesFileVersion.PROTOBUF_EVENTS (= 2). */
    private static final int PROTOBUF_EVENTS_VERSION = 2;

    private PcesEventHashReader() {}

    /**
     * Reads all PCES files from the given directory (recursively) and returns the set of event
     * hashes found.
     *
     * @param pcesDirectory the root directory containing PCES files
     * @return the set of event hashes from all PCES events
     */
    @NonNull
    public static Set<Hash> readEventHashes(@NonNull final Path pcesDirectory) {
        final Set<Hash> hashes = new HashSet<>();
        try (final Stream<Path> paths = Files.walk(pcesDirectory)) {
            paths.filter(p -> p.toString().endsWith(".pces")).sorted().forEach(pcesFile -> {
                try {
                    readEventHashesFromFile(pcesFile, hashes);
                } catch (final IOException e) {
                    logger.warn("Failed to read PCES file {}", pcesFile, e);
                }
            });
        } catch (final IOException e) {
            logger.warn("Failed to walk PCES directory {}", pcesDirectory, e);
        }
        logger.info("Read {} event hashes from PCES files in {}", hashes.size(), pcesDirectory);
        return hashes;
    }

    /**
     * Reads events from a single PCES file and adds their hashes to the given set.
     */
    private static void readEventHashesFromFile(@NonNull final Path pcesFile, @NonNull final Set<Hash> hashes)
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
                } catch (final EOFException e) {
                    break;
                } catch (final ParseException | NullPointerException e) {
                    // Partial or corrupted event - PlatformEvent constructor can throw NPE
                    // if the GossipEvent is malformed (e.g., missing eventCore or timeCreated)
                    logger.warn("Failed to parse event in PCES file {}", pcesFile, e);
                    break;
                }
            }
        }
    }
}
