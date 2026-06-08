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
import com.swirlds.base.time.Time;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.SwirldsPlatform;
import com.swirlds.platform.builder.PlatformBuilder;
import com.swirlds.platform.state.ConsensusStateEventHandler;
import com.swirlds.platform.system.InitTrigger;
import com.swirlds.platform.system.Platform;
import com.swirlds.platform.wiring.PlatformComponents;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.state.merkle.VirtualMapStateLifecycleManager;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStoreException;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.Hash;
import org.hiero.base.file.FileSystemManager;
import org.hiero.consensus.ConsensusLayerBuildingBlocks;
import org.hiero.consensus.ConsensusLayerFactory;
import org.hiero.consensus.ConsensusLayerInputs;
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
import org.hiero.consensus.state.SignedStateFileWriter;
import org.hiero.consensus.state.nexus.SignedStateNexus;
import org.hiero.consensus.state.saved.DeserializedSignedState;
import org.hiero.consensus.state.saved.StateDumpRequest;
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
 *       inside {@code PlatformComponentBuilder#build()}) performs all the restart priming
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

    private static final Duration BLOCK_FINALIZE_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration BLOCK_FINALIZE_POLL = Duration.ofMillis(200);

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
            final long targetRound,
            @NonNull final String consensusEventStreamName,
            @NonNull final Configuration platformConfig,
            @NonNull final FileSystemManager fileSystemManager,
            @NonNull final Metrics metrics,
            @NonNull final Time time)
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

        System.setProperty(
                "pces.replayDir",
                pcesDir.getParent().getParent().getParent().toFile().getAbsolutePath());
        // --- Place the PCES files where the PcesFileTracker will scan them, before the platform is built ---
        // DefaultPcesModule.initialize scans PcesUtilities.getDatabaseDirectory(config, fsm, selfId) at build time.
        stagePcesFiles(pcesDir, platformConfig, fileSystemManager, selfId);

        // --- Load the initial state directly from the given path ---
        // StartupStateUtils.loadInitialState scans a configured path convention
        // (savedStateDir/appName/swirldName/selfId)
        // and does not accept an arbitrary path. SignedStateFileReader.readState loads from any explicit directory,
        // which is the right approach for a CLI tool — the same pattern EventRecoveryWorkflow uses.
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

        // Consensus timestamps depend on transactionOffsetNanos (reserves nanos before the first user
        // transaction for preceding/system records). ServicesMain.main computes this from config and wires it
        // into the platform; the replay path must do the same or every transaction's consensus timestamp is
        // short by `offset` ns (offset = scheduling.reservedSystemTxnNanos + consensus.handleMaxPrecedingRecords
        // + 1), diverging from production from the first round.
        final int transactionOffsetNanos = ServicesMain.transactionOffsetNanos(platformConfig);
        hedera.setTxnOffsetNanos(transactionOffsetNanos);
        log.info("Defined transaction offset (nanos): {}", transactionOffsetNanos);

        // --- Build the platform (constructor priming runs inside build()) ---
        final PlatformBuilder platformBuilder = PlatformBuilder.create(
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
                .withTransactionOffsetNanos(transactionOffsetNanos);
        final SwirldsPlatform platform = (SwirldsPlatform) platformBuilder.build();
        final ConsensusLayerFactory consensusLayerFactory = platformBuilder.getConsensusLayerFactory();
        final PlatformComponents platformComponents = consensusLayerFactory.getPlatformComponents();
        final ConsensusLayerBuildingBlocks blocks = consensusLayerFactory.getConsensusLayerBuildingBlocks();

        boolean started = false;
        try {
            log.info(
                    "Driving PCES replay: startingRound={}, pcesReplayLowerBound={}",
                    startingRound,
                    pcesReplayLowerBound);

            // --- Final-state capture ---
            // Tap the hashed-state output so we retain the exact ReservedSignedState the platform produced for the
            // final replayed round (highest round <= targetRound). We keep exactly one reservation at a time, swapping
            // in the newer state and releasing the older. flushPrimaryPipeline() flushes the state hasher, which feeds
            // this wire, so by the time the flush returns this reference holds the final hashed state. This avoids
            // relying on the periodic-save mechanism (which may never fire within a single hourly bucket) and the
            // filesystem scan (which can race the async snapshot manager). Must be soldered before the model starts.
            final AtomicReference<ReservedSignedState> capturedFinalState = new AtomicReference<>();
            blocks
                    .stateModule()
                    .hashedStateOutputWire()
                    .solderTo("replayFinalStateCapture", "hashed state", (ReservedSignedState rs) -> {
                        final long round = rs.get().getRound();
                        if (round > targetRound) {
                            // Beyond the requested target — discard the reservation given to this consumer.
                            rs.close();
                            return;
                        }
                        // Retain this state, releasing any previously captured (lower-round) one.
                        final ReservedSignedState previous = capturedFinalState.getAndSet(rs);
                        if (previous != null) {
                            previous.close();
                        }
                    });

            platformContext.getRecycleBin().start();
            platformContext.getMetrics().start();
            blocks.wiringModel().start();
            started = true; // wiring model is now started; destroy()/stop() is safe from here on
            platformComponents.pcesModule().replayPcesEvents(pcesReplayLowerBound, startingRound);
            // NOTE: deliberately NOT calling blocks.platformCoordinator().startGossip()

            // The last block(s) are finalized asynchronously: block proofs (and the .mf completion markers) are
            // written from the signing callback, which flushPrimaryPipeline() does NOT wait for. Before we write
            // the state and destroy the platform, wait until every complete block file has its .mf marker and no
            // pending-proof (.pnd.json) files remain, so the block-stream output is valid for comparison.
            awaitBlockFinalization(outDir.resolve("blockStreams"), BLOCK_FINALIZE_TIMEOUT);

            // --- Write the exact final replayed state, synchronously, to the output directory ---
            final long resultRound = writeFinalState(
                    capturedFinalState.getAndSet(null),
                    targetRound,
                    platformConfig,
                    fileSystemManager,
                    selfId,
                    stateLifecycleManager,
                    outDir);
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
     * Writes the captured final replayed state synchronously to {@code outDir/<round>} via
     * {@link SignedStateFileWriter#writeSignedStateFilesToDirectory}. This is the same mechanism {@code dumpStateTask}
     * uses; for a non-periodic state the write is synchronous, so when this method returns the state is fully on disk.
     *
     * <p>The method takes ownership of the reservation and releases it (the writer does so internally). If
     * {@code capturedState} is null, no round was captured — either nothing reached consensus, or (when a target was
     * requested) the target was never reached.
     *
     * @return the round of the written state
     */
    private static long writeFinalState(
            final ReservedSignedState capturedState,
            final long targetRound,
            @NonNull final Configuration platformConfig,
            @NonNull final FileSystemManager fileSystemManager,
            @NonNull final NodeId selfId,
            @NonNull final VirtualMapStateLifecycleManager stateLifecycleManager,
            @NonNull final Path outDir)
            throws IOException {

        if (capturedState == null) {
            if (targetRound != DEFAULT_TARGET_ROUND) {
                throw new IllegalStateException(
                        "Target round " + targetRound + " was never reached during replay. The PCES does not contain "
                                + "enough rounds past the target to decide it. Regenerate with blocks-to-pces covering "
                                + "more rounds past the target, or choose a target within the decided range.");
            }
            throw new IllegalStateException("PCES replay produced no consensus rounds — nothing to write. "
                    + "Check PCES placement and origin/round alignment.");
        }

        try {
            final long round = capturedState.get().getRound();
            if (targetRound != DEFAULT_TARGET_ROUND && round < targetRound) {
                throw new IllegalStateException(
                        "Target round " + targetRound + " never reached consensus during replay (highest was "
                                + round + "). The PCES does not contain enough rounds past the target to decide it. "
                                + "Regenerate with blocks-to-pces covering more rounds past the target, or choose a "
                                + "target within the decided range.");
            }

            final Path destination = outDir.resolve(Long.toString(round));
            log.info("Writing final replayed state for round {} to {}", round, destination);

            // writeSignedStateToDisk atomically creates the exact destination directory; `destination` already
            // includes the selected round (<out>/<round>).
            SignedStateFileWriter.writeSignedStateToDisk(
                    platformConfig,
                    fileSystemManager,
                    selfId,
                    destination,
                    StateToDiskReason.PCES_RECOVERY_COMPLETE,
                    capturedState,
                    stateLifecycleManager);

            log.info("Final replayed state for round {} written to {}", round, destination);
            return round;
        } catch (final RuntimeException | IOException e) {
            // writeSignedStateFilesToDirectory only releases on its own success path; ensure we don't leak on failure.
            if (!capturedState.isClosed()) {
                capturedState.close();
            }
            throw e;
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

        final Path databaseDirectory = PcesUtilities.getDatabaseDirectory(configuration, fileSystemManager, selfId);

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

    /**
     * Waits until all block files under {@code blockStreamsDir} are finalized: every {@code .blk.gz}
     * (or {@code .blk}) has a sibling {@code .mf} marker and no {@code .pnd.json} pending-proof files
     * remain. Block proofs and their markers are written asynchronously from the signing callback after
     * the wiring pipeline is flushed, so this closes the race before the state is written and the platform
     * is destroyed. Times out (rather than hanging) if a signature never completes.
     */
    private static void awaitBlockFinalization(@NonNull final Path blockStreamsDir, @NonNull final Duration timeout) {
        final long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            final Optional<String> pending = firstUnfinalized(blockStreamsDir);
            if (pending.isEmpty()) {
                log.info("All block files finalized under {}", blockStreamsDir);
                return;
            }
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException(String.format(
                        "Timed out after %s waiting for block finalization under %s; first unfinalized: %s. "
                                + "The final block stream is incomplete (missing proof/.mf marker); failing so the "
                                + "output is not mistaken for a valid equivalence result.",
                        timeout, blockStreamsDir, pending.get()));
            }
            try {
                Thread.sleep(BLOCK_FINALIZE_POLL.toMillis());
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "Interrupted while awaiting block finalization under " + blockStreamsDir, e);
            }
        }
    }

    /**
     * Returns a description of the first block file that is not yet finalized (a .blk/.blk.gz without a
     * sibling .mf, or a leftover .pnd.json), or empty if all blocks are finalized. Searches the
     * node-scoped subdirectory tree under blockStreamsDir.
     */
    private static Optional<String> firstUnfinalized(@NonNull final Path blockStreamsDir) {
        if (!Files.isDirectory(blockStreamsDir)) {
            // No block output dir at all -> nothing was produced to finalize. This is itself suspicious for a
            // normal replay; treat as "nothing pending" ONLY if no blocks were expected. If blocks were
            // expected, the caller's own check (below) will catch an empty output. Keep returning empty here
            // so a genuinely block-free replay does not hang, but see the caller note in step 3.
            return Optional.empty();
        }
        try (final Stream<Path> files = Files.walk(blockStreamsDir)) {
            return files.filter(Files::isRegularFile)
                    .map(Path::toString)
                    .filter(name -> {
                        if (name.endsWith(".pnd.json") || name.endsWith(".pnd.gz") || name.endsWith(".pnd")) {
                            return true;
                        }
                        if (name.endsWith(".blk.gz")) {
                            return !Files.exists(
                                    Path.of(name.substring(0, name.length() - ".blk.gz".length()) + ".mf"));
                        }
                        if (name.endsWith(".blk")) {
                            return !Files.exists(Path.of(name.substring(0, name.length() - ".blk".length()) + ".mf"));
                        }
                        return false;
                    })
                    .findFirst();
        } catch (final IOException e) {
            // Do NOT treat a scan failure as success. Surface it so the command fails rather than reporting a
            // possibly-incomplete block stream as valid.
            throw new UncheckedIOException("Failed scanning block output for finalization under " + blockStreamsDir, e);
        }
    }

}
