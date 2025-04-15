// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.preconsensus;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.STARTUP;
import static com.swirlds.platform.event.preconsensus.PcesUtilities.getDatabaseDirectory;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.util.Objects.requireNonNull;
import static org.hiero.consensus.model.event.AncientMode.BIRTH_ROUND_THRESHOLD;
import static org.hiero.consensus.model.event.AncientMode.GENERATION_THRESHOLD;

import com.hedera.hapi.platform.event.GossipEvent;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.io.IOIterator;
import com.swirlds.common.io.utility.FileUtils;
import com.swirlds.common.io.utility.LegacyTemporaryFileBuilder;
import com.swirlds.common.io.utility.RecycleBin;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.io.streams.SerializableDataOutputStream;
import org.hiero.consensus.model.event.AncientMode;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.node.NodeId;

/**
 * This class is capable of migrating a PCES in generation mode to a PCES in birth round mode.
 */
public final class PcesBirthRoundMigration {

    private static final Logger logger = LogManager.getLogger(PcesBirthRoundMigration.class);

    private PcesBirthRoundMigration() {}

    /**
     * Migrate a PCES in generation mode to a PCES in birth round mode if needed. No op if the migration has already
     * been completed.
     *
     * @param platformContext                        the platform context
     * @param selfId                                 the ID of this node
     * @param migrationRound                         the round at which the migration is occurring, this will be equal
     *                                               to the round number of the initial state
     * @param minimumJudgeGenerationInMigrationRound the minimum judge generation in the migration round
     * @return true if any events were migrated to support birth rounds, else false
     */
    public static boolean migratePcesToBirthRoundMode(
            @NonNull final PlatformContext platformContext,
            @NonNull final NodeId selfId,
            final long migrationRound,
            final long minimumJudgeGenerationInMigrationRound)
            throws IOException {
        requireNonNull(platformContext);
        requireNonNull(selfId);

        final Path databaseDirectory = getDatabaseDirectory(platformContext, selfId);

        if (findPcesFiles(databaseDirectory, GENERATION_THRESHOLD).isEmpty()) {
            // No migration needed if there are no PCES files in generation mode.

            logger.info(STARTUP.getMarker(), "PCES birth round migration is not necessary.");
            return false;
        } else if (!findPcesFiles(databaseDirectory, BIRTH_ROUND_THRESHOLD).isEmpty()) {
            // We've found PCES files in both birth round and generation mode.
            // This is a signal that we attempted to do the migration but crashed.
            // The migrated PCES file is written atomically, so if it exists,
            // the important part of the migration has been completed. Remaining
            // work is to clean up the old files.

            logger.error(
                    EXCEPTION.getMarker(),
                    "PCES birth round migration has already been completed, but there "
                            + "are still legacy formatted PCES files present. Cleaning up.");
            makeBackupFiles(platformContext.getRecycleBin(), databaseDirectory, platformContext.getConfiguration());
            cleanUpOldFiles(databaseDirectory);

            return true;
        }

        logger.info(
                STARTUP.getMarker(),
                "Migrating PCES to birth round mode. Migration round: {}, minimum judge "
                        + "generation in migration round: {}.",
                migrationRound,
                minimumJudgeGenerationInMigrationRound);

        makeBackupFiles(platformContext.getRecycleBin(), databaseDirectory, platformContext.getConfiguration());

        final List<PlatformEvent> eventsToMigrate =
                readEventsToBeMigrated(platformContext, selfId, minimumJudgeGenerationInMigrationRound, migrationRound);

        if (eventsToMigrate.isEmpty()) {
            logger.error(EXCEPTION.getMarker(), "No events to migrate. PCES birth round migration aborted.");
            return false;
        }

        migrateEvents(platformContext, selfId, eventsToMigrate, migrationRound);

        cleanUpOldFiles(databaseDirectory);

        logger.info(STARTUP.getMarker(), "PCES birth round migration complete.");
        return true;
    }

    /**
     * Copy PCES files into recycle bin. A measure to reduce the chances of permanent data loss in the event of a
     * migration failure.
     *
     * @param recycleBin        the fileSystemManager
     * @param databaseDirectory the database directory (i.e. where PCES files are stored)
     * @param configuration platform configuration
     */
    private static void makeBackupFiles(
            @NonNull final RecycleBin recycleBin,
            @NonNull final Path databaseDirectory,
            @NonNull final Configuration configuration)
            throws IOException {
        requireNonNull(recycleBin);
        requireNonNull(databaseDirectory);
        requireNonNull(configuration);

        logger.info(
                STARTUP.getMarker(), "Backing up PCES files prior to PCES modification in case of unexpected failure.");

        final Path copyDirectory = LegacyTemporaryFileBuilder.buildTemporaryFile("pces-backup", configuration);
        FileUtils.hardLinkTree(databaseDirectory, copyDirectory);
        recycleBin.recycle(copyDirectory);
    }

    /**
     * Find all PCES files beneath a given directory. Unlike the normal process of reading PCES files via
     * {@link PcesFileReader#readFilesFromDisk(PlatformContext, Path, long, boolean, AncientMode)}, this
     * method ignores discontinuities and returns all files.
     *
     * @param path        the directory tree to search
     * @param ancientMode only return files that conform to this ancient mode
     * @return all PCES files beneath the given directory
     */
    @NonNull
    public static List<PcesFile> findPcesFiles(@NonNull final Path path, @NonNull final AncientMode ancientMode) {
        requireNonNull(path);
        requireNonNull(ancientMode);

        try (final Stream<Path> fileStream = Files.walk(path)) {
            return fileStream
                    .filter(f -> !Files.isDirectory(f))
                    .map(PcesUtilities::parseFile)
                    .filter(Objects::nonNull)
                    .filter(f -> f.getFileType() == ancientMode)
                    .sorted() // sorting is not strictly necessary, but it makes the output & logs more predictable
                    .collect(Collectors.toList());
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Read all events that will be non-ancient after migration.
     *
     * @param platformContext                        the platform context
     * @param selfId                                 this node's ID
     * @param minimumJudgeGenerationInMigrationRound the minimum judge generation in the migration round
     * @param migrationRound                         the migration round (i.e. the round number of the state that we are
     *                                               loading at migration time)
     * @return the events to be migrated
     */
    @NonNull
    private static List<PlatformEvent> readEventsToBeMigrated(
            @NonNull final PlatformContext platformContext,
            @NonNull final NodeId selfId,
            final long minimumJudgeGenerationInMigrationRound,
            final long migrationRound)
            throws IOException {
        requireNonNull(platformContext);
        requireNonNull(selfId);

        final PcesFileTracker originalFiles = PcesFileReader.readFilesFromDisk(
                platformContext,
                getDatabaseDirectory(platformContext, selfId),
                migrationRound,
                platformContext
                        .getConfiguration()
                        .getConfigData(PcesConfig.class)
                        .permitGaps(),
                GENERATION_THRESHOLD);

        // The number of events that qualify for migration is expected to be small,
        // so we can gather them all in memory.

        final IOIterator<PlatformEvent> iterator =
                originalFiles.getEventIterator(minimumJudgeGenerationInMigrationRound, migrationRound);
        final List<PlatformEvent> eventsToMigrate = new ArrayList<>();
        while (iterator.hasNext()) {
            eventsToMigrate.add(iterator.next());
        }
        logger.info(STARTUP.getMarker(), "Found {} events meeting criteria for migration.", eventsToMigrate.size());

        return eventsToMigrate;
    }

    /**
     * Migrate the required events to a new PCES file.
     *
     * @param platformContext the platform context
     * @param selfId          the self ID
     * @param eventsToMigrate the events to migrate
     * @param migrationRound  the migration round
     */
    private static void migrateEvents(
            @NonNull final PlatformContext platformContext,
            @NonNull final NodeId selfId,
            @NonNull final List<PlatformEvent> eventsToMigrate,
            final long migrationRound)
            throws IOException {
        requireNonNull(platformContext);
        requireNonNull(selfId);
        requireNonNull(eventsToMigrate);

        // First, write the data to a temporary file. If we crash, easier to recover if this operation is atomic.
        final Path temporaryFile =
                LegacyTemporaryFileBuilder.buildTemporaryFile("new-pces-file", platformContext.getConfiguration());
        final SerializableDataOutputStream outputStream = new SerializableDataOutputStream(
                new BufferedOutputStream(new FileOutputStream(temporaryFile.toFile())));
        outputStream.writeInt(PcesFileVersion.currentVersionNumber());
        for (final PlatformEvent event : eventsToMigrate) {
            outputStream.writePbjRecord(event.getGossipEvent(), GossipEvent.PROTOBUF);
        }
        outputStream.close();

        // Next, move the temporary file to its final location.
        final PcesFile file = PcesFile.of(
                BIRTH_ROUND_THRESHOLD,
                platformContext.getTime().now(),
                0,
                migrationRound,
                migrationRound,
                migrationRound,
                PcesUtilities.getDatabaseDirectory(platformContext, selfId));
        Files.createDirectories(file.getPath().getParent());
        Files.move(temporaryFile, file.getPath(), ATOMIC_MOVE);
        logger.info(STARTUP.getMarker(), "Events written to file {}.", file.getPath());
    }

    /**
     * Clean up old files.
     *
     * @param databaseDirectory the database directory (i.e. where PCES files are stored)
     */
    private static void cleanUpOldFiles(@NonNull final Path databaseDirectory) throws IOException {
        requireNonNull(databaseDirectory);

        final List<PcesFile> filesToDelete = findPcesFiles(databaseDirectory, GENERATION_THRESHOLD);

        logger.info(STARTUP.getMarker(), "Cleaning up old {} legacy formatted PCES files.", filesToDelete.size());

        for (final PcesFile file : filesToDelete) {
            file.deleteFile(databaseDirectory);
        }
    }
}
