// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.blockstream;

import static com.hedera.statevalidation.ReplayPcesCommand.DEFAULT_TARGET_ROUND;
import static java.util.Objects.requireNonNull;
import static org.hiero.consensus.concurrent.manager.AdHocThreadManager.getStaticThreadManager;
import static org.hiero.consensus.platformstate.PlatformStateUtils.ancientThresholdOf;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.node.app.Hedera;
import com.hedera.node.app.ServicesMain;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.statevalidation.ReplayPcesCommand;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.builder.PlatformBuilder.PersistenceScope;
import com.swirlds.platform.state.ConsensusStateEventHandler;
import com.swirlds.platform.system.InitTrigger;
import com.swirlds.platform.system.Platform;
import com.swirlds.platform.test.fixtures.builder.TestPlatformBuilder;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.state.merkle.VirtualMapStateLifecycleManager;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStoreException;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.Hash;
import org.hiero.base.file.FileSystemManager;
import org.hiero.consensus.ConsensusLayerBuildingBlocks;
import org.hiero.consensus.config.PathsConfig;
import org.hiero.consensus.crypto.KeysAndCertsGenerator;
import org.hiero.consensus.io.RecycleBinImpl;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.pces.impl.common.PcesUtilities;
import org.hiero.consensus.roster.ReadableRosterStore;
import org.hiero.consensus.roster.ReadableRosterStoreImpl;
import org.hiero.consensus.roster.RosterHistory;
import org.hiero.consensus.roster.RosterStateId;
import org.hiero.consensus.state.SignedStateFileReader;
import org.hiero.consensus.state.saved.DeserializedSignedState;
import org.hiero.consensus.state.signed.ReservedSignedState;

/**
 * Loads a saved state, replays a PCES stream on top of it using the consensus node's <b>real</b> replay mechanism, and
 * dumps the resulting state to disk.
 *
 * <p>This intentionally reuses the production startup path rather than a bespoke replay harness. It constructs the same
 * {@link Hedera} execution layer and {@link Platform} that {@link ServicesMain#main} builds via
 * {@link TestPlatformBuilder}, which performs all restart priming (consensus snapshot override, event window,
 * ISS-detector seeding, {@code onStateInitialized}, etc.) automatically. It then drives the body of
 * {@code SwirldsPlatform.start()} <i>minus gossip</i>:
 *
 * <ol>
 *   <li>Build the platform via {@link TestPlatformBuilder} — loading the initial state, initializing the States API,
 *       deriving roster/keys, and constructing all consensus-layer modules. The builder's {@code build()} performs all
 *       the restart priming that {@code SwirldsPlatform}'s constructor does.</li>
 *   <li>Start the recycle bin, metrics, and wiring model.</li>
 *   <li>Call {@code pcesModule().replayPcesEvents(pcesReplayLowerBound, startingRound)} — the same call normal startup
 *       makes — but do <b>not</b> start gossip.</li>
 *   <li>Flush the pipeline, locate the saved state the platform wrote, and copy it to the output directory. When a
 *       {@code --target-round} is specified, the exact target-round snapshot is located and copied instead of the
 *       latest periodic snapshot.</li>
 * </ol>
 *
 * <p>The replay bounds are recomputed from the loaded state using the same public helpers the platform constructor
 * uses ({@code initialState.getRound()} and
 * {@link org.hiero.consensus.platformstate.PlatformStateUtils#ancientThresholdOf}), so they are identical to a
 * production restart — no reflection into platform internals is required.
 */
public final class ReplayPcesWorkflow {

    private static final Logger log = LogManager.getLogger(ReplayPcesWorkflow.class);

    private ReplayPcesWorkflow() {}

    /**
     * Runs the replay.
     *
     * @param stateDir the directory containing the saved state snapshot to load
     * @param pcesDir the directory containing the PCES files to replay (produced by {@code blocks-to-pces})
     * @param outDir the directory where the resulting state snapshot will be written
     * @param selfId the node id to run as; must match the node id the PCES files were generated for
     * @param targetRound the last round that should be applied ({@link ReplayPcesCommand#DEFAULT_TARGET_ROUND} for all)
     * @param consensusEventStreamName the consensus event stream name (e.g. "0.0.3"); supplied by the caller because
     *     {@code ServicesMain} derives it via private helpers. For replay it only names an output directory.
     * @param platformConfig the fully-built platform configuration (production-mirroring, plus replay flags)
     * @param fileSystemManager the file system manager
     * @param metrics the platform metrics
     * @param time the time source
     * @return the round the replayed state advanced to
     */
    public static long run(
            @NonNull final Path stateDir,
            @NonNull final Path pcesDir,
            @NonNull final Path outDir,
            @NonNull final NodeId selfId,
            final long targetRound,
            @NonNull final String consensusEventStreamName,
            @NonNull final Configuration platformConfig,
            @NonNull final FileSystemManager fileSystemManager,
            @NonNull final Metrics metrics,
            @NonNull final com.swirlds.base.time.Time time)
            throws IOException, InterruptedException, ParseException, KeyStoreException, ExecutionException {
        requireNonNull(stateDir);
        requireNonNull(pcesDir);
        requireNonNull(outDir);
        requireNonNull(consensusEventStreamName);

        // --- Construct the Hedera execution layer exactly as ServicesMain does ---
        final Hedera hedera = ServicesMain.newHedera(platformConfig, fileSystemManager, metrics, time, selfId);
        final SemanticVersion version = hedera.getSemanticVersion();
        log.info("Replaying PCES on node {} with software version {}", selfId, version);

        final var recycleBin = RecycleBinImpl.create(
                metrics, platformConfig, getStaticThreadManager(), time, fileSystemManager, selfId);
        final ConsensusStateEventHandler consensusStateEventHandler = hedera.newConsensusStateEvenHandler();
        final PlatformContext platformContext =
                PlatformContext.create(platformConfig, time, metrics, fileSystemManager, recycleBin);

        // --- Place the PCES files where the PcesFileTracker will scan them, before the platform is built ---
        stagePcesFiles(pcesDir, platformConfig, fileSystemManager, selfId);

        // --- Load the initial state directly from the given path ---
        final var stateLifecycleManager =
                new VirtualMapStateLifecycleManager(metrics, time, platformConfig, fileSystemManager);

        log.info("Loading state from {}", stateDir);
        final DeserializedSignedState deserializedSignedState =
                SignedStateFileReader.readState(stateDir, platformContext.getConfiguration(), stateLifecycleManager);

        final ReservedSignedState initialState = deserializedSignedState.reservedSignedState();
        final Hash originalHash = deserializedSignedState.originalHash();
        final VirtualMapState state = initialState.get().getState();

        if (initialState.get().isGenesisState()) {
            throw new IllegalStateException(
                    "No saved state found in " + stateDir + " — replay-pces requires a loaded (non-genesis) state");
        }

        // --- Initialize the States API on the loaded state (restart path) ---
        hedera.initializeStatesApi(state, InitTrigger.RESTART, platformConfig);
        hedera.setInitialStateHash(originalHash);

        // --- Roster + keys (same derivation the platform uses at restart/reconnect) ---
        final ReadableRosterStore rosterStore =
                new ReadableRosterStoreImpl(state.getReadableStates(RosterStateId.SERVICE_NAME));
        final RosterHistory rosterHistory = rosterStore.getRosterHistory();

        // --- Generate ephemeral keys for this node ---
        final KeysAndCerts keysAndCerts =
                KeysAndCertsGenerator.generateKeysAndCerts(List.of(selfId)).get(selfId);

        // Register the platform service-state stubs on the manager's current mutable state.
        SignedStateFileReader.registerServiceStates(stateLifecycleManager.getMutableState());

        // --- Recompute replay bounds (must read before build() consumes the state) ---
        final long startingRound = initialState.get().getRound();
        final long pcesReplayLowerBound = ancientThresholdOf(state);

        final int transactionOffsetNanos = ServicesMain.transactionOffsetNanos(platformConfig);
        hedera.setTxnOffsetNanos(transactionOffsetNanos);

        // --- Build the platform via TestPlatformBuilder ---
        // TestPlatformBuilder.build() constructs the real SwirldsPlatform (which Hedera needs for
        // onStateInitialized), runs InitialStateLoader, wires all modules, and closes the initial state
        // reservation. We then access buildingBlocks() to drive replay without starting gossip.
        final TestPlatformBuilder builder = new TestPlatformBuilder(
                platformConfig,
                platformContext.getMetrics(),
                platformContext.getTime(),
                rosterHistory,
                keysAndCerts,
                selfId,
                platformContext.getRecycleBin(),
                platformContext.getFileSystemManager(),
                hedera,
                consensusStateEventHandler,
                initialState,
                stateLifecycleManager,
                version,
                new PersistenceScope(Hedera.APP_NAME, Hedera.SWIRLD_NAME),
                consensusEventStreamName,
                transactionOffsetNanos);

        final Platform platform = builder.build();
        final ConsensusLayerBuildingBlocks buildingBlocks = builder.buildingBlocks();

        // --- Target-round capture: tap the hashgraph consensus-round output so we can track the highest round
        //     that reached consensus. Must be soldered before the wiring model starts. ---
        final AtomicLong latestConsensusRound = new AtomicLong(-1);
        buildingBlocks
                .hashgraphModule()
                .consensusRoundOutputWire()
                .solderTo(
                        "replayRoundTracker",
                        "consensus round",
                        round -> latestConsensusRound.set(round.getRoundNum()));

        boolean started = false;
        try {
            log.info(
                    "Driving PCES replay: startingRound={}, pcesReplayLowerBound={}, targetRound={}",
                    startingRound,
                    pcesReplayLowerBound,
                    targetRound == DEFAULT_TARGET_ROUND ? "all" : targetRound);

            // --- Drive the body of SwirldsPlatform.start() MINUS gossip ---
            platformContext.getRecycleBin().start();
            platformContext.getMetrics().start();
            buildingBlocks.wiringModel().start();
            started = true;

            buildingBlocks.pcesModule().replayPcesEvents(pcesReplayLowerBound, startingRound);
            // NOTE: deliberately NOT calling buildingBlocks.gossipModule().startInputWire().inject(...)

            // --- Flush the pipeline so state snapshots are written to disk ---
            buildingBlocks.pipelineFlusher().flushPrimaryPipeline();

            // --- Write the resulting state ---
            final long resultRound;
            if (targetRound != DEFAULT_TARGET_ROUND) {
                resultRound = writeTargetRoundState(platformConfig, outDir, targetRound, latestConsensusRound.get());
            } else {
                resultRound = copyLatestSnapshotToOutDir(platformConfig, outDir);
            }

            log.info("PCES replay complete. Resulting state round: {}, written under {}", resultRound, outDir);
            return resultRound;
        } finally {
            if (started) {
                try {
                    platform.destroy();
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (final RuntimeException e) {
                    log.warn("Error while destroying platform during cleanup", e);
                }
            }
        }
    }

    /**
     * Writes the target-round state to the output directory. Looks for the exact round directory first; falls back to
     * the closest available round if the periodic save didn't land on the target.
     */
    private static long writeTargetRoundState(
            @NonNull final Configuration platformConfig,
            @NonNull final Path outDir,
            final long targetRound,
            final long actualLatestRound)
            throws IOException {

        if (actualLatestRound < targetRound) {
            throw new IllegalStateException(
                    "Target round " + targetRound + " never reached consensus during replay (latest was "
                            + actualLatestRound + "). The PCES does not contain enough rounds past the target to "
                            + "decide it. Regenerate with blocks-to-pces covering more rounds past the target "
                            + "(increase DECISION_MARGIN_ROUNDS), or choose a target within the decided range.");
        }

        final Path savedStateDir =
                platformConfig.getConfigData(PathsConfig.class).savedStateDir();
        final Path existingRoundDir = findRoundDirectory(savedStateDir, targetRound);
        if (existingRoundDir != null) {
            return copyRoundDirToOutDir(existingRoundDir, targetRound, outDir);
        }

        log.info("Target round {} was not periodically saved; falling back to closest available round", targetRound);
        final Path closestRoundDir = findLatestRoundDirectory(savedStateDir);
        if (closestRoundDir == null) {
            throw new IllegalStateException("No saved state found under " + savedStateDir + " after replay. "
                    + "This usually means no events were replayed.");
        }

        final long closestRound = Long.parseLong(closestRoundDir.getFileName().toString());
        log.info("Closest available saved round is {} (target was {})", closestRound, targetRound);
        return copyRoundDirToOutDir(closestRoundDir, closestRound, outDir);
    }

    /**
     * Copies a round directory to the output directory, returning the round number.
     */
    private static long copyRoundDirToOutDir(@NonNull final Path roundDir, final long round, @NonNull final Path outDir)
            throws IOException {
        final Path destination = outDir.resolve(Long.toString(round));
        log.info("Copying replay state from {} to {}", roundDir, destination);
        Files.createDirectories(destination);
        try (final Stream<Path> files = Files.walk(roundDir)) {
            files.forEach(source -> {
                final Path target = destination.resolve(roundDir.relativize(source));
                try {
                    if (Files.isDirectory(source)) {
                        Files.createDirectories(target);
                    } else {
                        Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (final IOException e) {
                    throw new java.io.UncheckedIOException("Failed to copy " + source + " -> " + target, e);
                }
            });
        }
        log.info("Replay state for round {} copied to {}", round, destination);
        return round;
    }

    /**
     * Copies the PCES files into the database directory the platform will scan
     * ({@link PcesUtilities#getDatabaseDirectory}). The {@code blocks-to-pces} tool writes its output under a
     * node-id-0 subtree; this stages those files into the location keyed by {@code selfId} so the
     * {@code PcesFileTracker} discovers them at platform build time.
     */
    private static void stagePcesFiles(
            @NonNull final Path pcesDir,
            @NonNull final Configuration configuration,
            @NonNull final FileSystemManager fileSystemManager,
            @NonNull final NodeId selfId)
            throws IOException {

        final Path databaseDirectory = PcesUtilities.getDatabaseDirectory(configuration, fileSystemManager, selfId);

        final Path sourceDir = locatePcesFiles(pcesDir);

        log.info("Staging PCES files from {} into {}", sourceDir, databaseDirectory);

        if (Files.isDirectory(databaseDirectory)) {
            try (final Stream<Path> stale = Files.list(databaseDirectory)) {
                stale.filter(p -> p.getFileName().toString().endsWith(".pces")).forEach(p -> {
                    try {
                        Files.delete(p);
                    } catch (final IOException e) {
                        throw new RuntimeException("Failed to remove stale PCES file " + p, e);
                    }
                });
            }
        } else {
            Files.createDirectories(databaseDirectory);
        }

        try (final Stream<Path> files = Files.list(sourceDir)) {
            files.filter(p -> p.getFileName().toString().endsWith(".pces")).forEach(p -> {
                final Path target = databaseDirectory.resolve(p.getFileName());
                try {
                    Files.copy(p, target);
                } catch (final IOException e) {
                    throw new RuntimeException("Failed to stage PCES file " + p + " -> " + target, e);
                }
            });
        }
    }

    @NonNull
    private static Path locatePcesFiles(@NonNull final Path pcesDir) throws IOException {
        if (containsPcesFiles(pcesDir)) {
            return pcesDir;
        }
        try (final Stream<Path> entries = Files.list(pcesDir)) {
            final List<Path> subDirs = entries.filter(Files::isDirectory)
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
            for (final Path sub : subDirs) {
                if (containsPcesFiles(sub)) {
                    return sub;
                }
            }
        }
        throw new IOException("No .pces files found under " + pcesDir + " (or its immediate subdirectories)");
    }

    private static boolean containsPcesFiles(@NonNull final Path dir) throws IOException {
        try (final Stream<Path> files = Files.list(dir)) {
            return files.anyMatch(p -> p.getFileName().toString().endsWith(".pces"));
        }
    }

    /**
     * Locates the latest state snapshot written by the platform, copies it to {@code outDir}, and returns the round.
     */
    private static long copyLatestSnapshotToOutDir(
            @NonNull final Configuration platformConfig, @NonNull final Path outDir) throws IOException {
        final Path savedStateDir =
                platformConfig.getConfigData(PathsConfig.class).savedStateDir();
        final Path roundDir = findLatestRoundDirectory(savedStateDir);
        if (roundDir == null) {
            throw new IllegalStateException("PCES replay produced no saved state under " + savedStateDir
                    + ". This usually means no events were replayed "
                    + "(check PCES placement and origin/round alignment).");
        }
        final long round = Long.parseLong(roundDir.getFileName().toString());
        return copyRoundDirToOutDir(roundDir, round, outDir);
    }

    private static Path findRoundDirectory(final Path root, final long round) throws IOException {
        if (!Files.isDirectory(root)) {
            return null;
        }
        final String roundName = Long.toString(round);
        try (final Stream<Path> dirs = Files.walk(root)) {
            return dirs.filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().equals(roundName))
                    .findFirst()
                    .orElse(null);
        }
    }

    private static Path findLatestRoundDirectory(final Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return null;
        }
        try (final Stream<Path> dirs = Files.walk(root)) {
            return dirs.filter(Files::isDirectory)
                    .filter(p -> {
                        try {
                            Long.parseLong(p.getFileName().toString());
                            return true;
                        } catch (final NumberFormatException e) {
                            return false;
                        }
                    })
                    .max(Comparator.comparingLong(
                            p -> Long.parseLong(p.getFileName().toString())))
                    .orElse(null);
        }
    }
}
