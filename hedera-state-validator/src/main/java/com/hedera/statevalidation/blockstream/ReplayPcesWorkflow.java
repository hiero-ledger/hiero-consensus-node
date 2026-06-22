// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.blockstream;

import static java.util.Objects.requireNonNull;
import static org.hiero.consensus.concurrent.manager.AdHocThreadManager.getStaticThreadManager;
import static org.hiero.consensus.platformstate.PlatformStateUtils.ancientThresholdOf;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.node.app.Hedera;
import com.hedera.node.app.ServicesMain;
import com.hedera.pbj.runtime.ParseException;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.io.filesystem.FileSystemManager;
import com.swirlds.common.io.utility.RecycleBinImpl;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.builder.PlatformBuilder;
import com.swirlds.platform.builder.PlatformBuildingBlocks;
import com.swirlds.platform.builder.PlatformComponentBuilder;
import com.swirlds.platform.state.ConsensusStateEventHandler;
import com.swirlds.platform.state.snapshot.DeserializedSignedState;
import com.swirlds.platform.state.snapshot.SignedStateFileReader;
import com.swirlds.platform.state.snapshot.StateDumpRequest;
import com.swirlds.platform.system.InitTrigger;
import com.swirlds.platform.system.Platform;
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
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.crypto.KeysAndCertsGenerator;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.pces.impl.common.PcesUtilities;
import org.hiero.consensus.roster.ReadableRosterStore;
import org.hiero.consensus.roster.ReadableRosterStoreImpl;
import org.hiero.consensus.roster.RosterHistory;
import org.hiero.consensus.roster.RosterStateId;
import org.hiero.consensus.roster.RosterStateUtils;
import org.hiero.consensus.state.signed.ReservedSignedState;
import org.hiero.consensus.state.signed.SignedState;
import org.hiero.consensus.state.snapshot.StateToDiskReason;

/**
 * Loads a saved state, replays a PCES stream on top of it using the consensus node's <b>real</b> replay mechanism, and
 * dumps the resulting state to disk.
 *
 * <p>This intentionally reuses the production startup path rather than a bespoke replay harness. It constructs the same
 * {@link Hedera} execution layer and {@link Platform} that {@link ServicesMain#main} builds, then drives the body of
 * {@link com.swirlds.platform.SwirldsPlatform#start()} <i>minus gossip</i>:
 *
 * <ol>
 *   <li>Build the platform exactly as {@code ServicesMain} does — loading the initial state, initializing the States
 *       API, deriving roster/keys, and calling {@link PlatformBuilder}. The {@code SwirldsPlatform} constructor (run
 *       inside {@link PlatformComponentBuilder#build()}) performs all the restart priming
 *       ({@code consensusSnapshotOverride}, {@code updateEventWindow}, signature-collector and ISS-detector seeding,
 *       etc.) automatically.</li>
 *   <li>Start the recycle bin, metrics, and platform coordinator (the first three lines of {@code start()}).</li>
 *   <li>Call {@code pcesModule().replayPcesEvents(pcesReplayLowerBound, startingRound)} — the same call normal startup
 *       makes — but do <b>not</b> call {@code startGossip()}.</li>
 *   <li>Pull the latest immutable state, mark it {@link StateToDiskReason#PCES_RECOVERY_COMPLETE}, and dump it to disk,
 *       blocking until the write finishes.</li>
 * </ol>
 *
 * <p>The replay bounds are recomputed from the loaded state using the same public helpers the {@code SwirldsPlatform}
 * constructor uses ({@code initialState.getRound()} and {@link org.hiero.consensus.platformstate.PlatformStateUtils#ancientThresholdOf}),
 * so they are identical to a production restart — no reflection into platform internals is required.
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
        final Hedera hedera = ServicesMain.newHedera(platformConfig, metrics, time);
        final SemanticVersion version = hedera.getSemanticVersion();
        log.info("Replaying PCES on node {} with software version {}", selfId, version);

        final var recycleBin = RecycleBinImpl.create(
                metrics, platformConfig, getStaticThreadManager(), time, fileSystemManager, selfId);
        final ConsensusStateEventHandler consensusStateEventHandler = hedera.newConsensusStateEvenHandler();
        final PlatformContext platformContext =
                PlatformContext.create(platformConfig, time, metrics, fileSystemManager, recycleBin);

        // --- Place the PCES files where the PcesFileTracker will scan them, before the platform is built ---
        // DefaultPcesModule.initialize scans PcesUtilities.getDatabaseDirectory(config, fsm, selfId) at build time.
        stagePcesFiles(pcesDir, platformConfig, fileSystemManager, selfId);

        // --- Load the initial state directly from the given path ---
        // StartupStateUtils.loadInitialState scans a configured path convention
        // (savedStateDir/appName/swirldName/selfId)
        // and does not accept an arbitrary path. SignedStateFileReader.readState loads from any explicit directory,
        // which is the right approach for a CLI tool — the same pattern EventRecoveryWorkflow uses.
        final var stateLifecycleManager = new VirtualMapStateLifecycleManager(metrics, time, platformConfig);

        log.info("Loading state from {}", stateDir);
        final DeserializedSignedState deserializedSignedState =
                SignedStateFileReader.readState(stateDir, platformContext, stateLifecycleManager);

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
        final RosterHistory rosterHistory = RosterStateUtils.createRosterHistory(state);

        // --- Generate ephemeral keys for this node ---
        // initNodeSecurity loads keys from disk (PKCS12 keystores), which don't exist in an offline replay
        // environment, causing KEY_LOADING_FAILED. For PCES replay we never start gossip or interact with
        // peers, so real keys are not needed — the platform only requires them to satisfy internal certificate
        // validation at build time. KeysAndCertsGenerator.generateKeysAndCerts produces self-consistent
        // ephemeral keys that satisfy those checks without any on-disk key infrastructure.
        final KeysAndCerts keysAndCerts =
                KeysAndCertsGenerator.generateKeysAndCerts(List.of(selfId)).get(selfId);

        // Register the platform service-state stubs (PlatformStateService ID 26, RosterService) on the manager's
        // current mutable state, immediately before building the platform. This must come AFTER Hedera's
        // initializeStatesApi (whose onStateInitialized migration rebuilds the services map and would otherwise
        // overwrite earlier stub registration) and BEFORE PlatformComponentBuilder.build() (whose SwirldsPlatform
        // constructor calls copyMutableState() then accesses PlatformStateService — the copy carries this metadata
        // forward via VirtualMapStateImpl's copy constructor). This is the same registration StateUtils.initState
        // performs; the platform's own init path does not register these platform stubs.
        SignedStateFileReader.registerServiceStates(stateLifecycleManager.getMutableState());

        // --- Recompute replay bounds exactly as the SwirldsPlatform constructor does (non-genesis) ---
        // These MUST be read before PlatformBuilder.build(): build() takes ownership of the initialState
        // reservation and closes it during construction (copyMutableState + startup handling), after which
        // initialState.get() throws ReferenceCountException.
        final long startingRound = initialState.get().getRound();
        final long pcesReplayLowerBound = ancientThresholdOf(state);

        // --- Build the platform (constructor priming runs inside build()) ---
        final PlatformComponentBuilder componentBuilder = PlatformBuilder.create(
                        Hedera.APP_NAME,
                        Hedera.SWIRLD_NAME,
                        version,
                        initialState,
                        consensusStateEventHandler,
                        selfId,
                        consensusEventStreamName,
                        rosterHistory,
                        stateLifecycleManager)
                .withPlatformContext(platformContext)
                .withConfiguration(platformConfig)
                .withKeysAndCerts(keysAndCerts)
                .withExecutionLayer(hedera)
                .withStaleEventCallback(hedera)
                .buildComponentBuilder();

        final Platform platform = componentBuilder.build();
        final PlatformBuildingBlocks blocks = componentBuilder.getBuildingBlocks();

        boolean started = false;
        try {
            log.info(
                    "Driving PCES replay: startingRound={}, pcesReplayLowerBound={}",
                    startingRound,
                    pcesReplayLowerBound);

            // --- Drive the body of SwirldsPlatform.start() MINUS startGossip() ---
            platformContext.getRecycleBin().start();
            platformContext.getMetrics().start();
            blocks.platformCoordinator().start();
            started = true; // wiring model is now started; destroy()/stop() is safe from here on
            blocks.platformComponents().pcesModule().replayPcesEvents(pcesReplayLowerBound, startingRound);
            // NOTE: deliberately NOT calling blocks.platformCoordinator().startGossip()

            // Drain the downstream pipeline before capturing/dumping. replayPcesEvents returns once all events
            // have been fed in, but consensus rounds continue through transaction handling -> block production
            // asynchronously on the wiring threads. Without these flushes the process tears down before the last
            // block is written and closed, leaving a 0-byte trailing block (the events for those rounds ARE
            // present in the PCES; only the flush was missing). flushTransactionHandler drives round handling
            // (which closes blocks via endRound); flushStateHasher ensures end-of-round state hashes complete.
            blocks.platformCoordinator().flushTransactionHandler();
            blocks.platformCoordinator().flushStateHasher();

            // --- Capture the resulting state and dump it to disk (ADR-003 steps 3-4) ---
            final long resultRound = dumpResultingState(blocks, outDir);
            log.info("PCES replay complete. Resulting state round: {}, written under {}", resultRound, outDir);
            return resultRound;
        } finally {
            // platform.destroy() calls platformCoordinator.stop(), which throws if the wiring model was never
            // started. Only destroy when start succeeded, and never let cleanup mask the original exception.
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
     * Pulls the latest immutable state from the nexus, marks it for saving, and dumps it to disk, blocking until the
     * write completes.
     *
     * @return the round of the dumped state
     */
    private static long dumpResultingState(@NonNull final PlatformBuildingBlocks blocks, @NonNull final Path outDir) {
        try (final ReservedSignedState reserved =
                blocks.latestImmutableStateNexus().getState("replay-pces result")) {
            if (reserved == null) {
                throw new IllegalStateException(
                        "PCES replay produced no immutable state — nothing to dump. "
                                + "This usually means no events were replayed (check PCES placement and origin/round alignment).");
            }
            final SignedState signedState = reserved.get();
            signedState.markAsStateToSave(StateToDiskReason.PCES_RECOVERY_COMPLETE);

            final StateDumpRequest request =
                    StateDumpRequest.create(signedState.reserve("dumping replay-pces result state"));
            blocks.platformCoordinator().dumpStateToDisk(request);
            request.waitForFinished().run();

            return signedState.getRound();
        }
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

        final Path databaseDirectory = PcesUtilities.getDatabaseDirectory(configuration, selfId);

        // Find the directory in pcesDir that actually contains the .pces files. blocks-to-pces writes them under a
        // node-id subdirectory (node 0). Accept either pcesDir directly containing .pces files, or a single
        // node-id subdirectory containing them.
        final Path sourceDir = locatePcesFiles(pcesDir);

        log.info("Staging PCES files from {} into {}", sourceDir, databaseDirectory);

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

    /**
     * Resolves the directory that actually holds the {@code .pces} files. Accepts {@code pcesDir} itself if it directly
     * contains them, otherwise descends into a single node-id subdirectory (as produced by {@code blocks-to-pces}).
     */
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
}
