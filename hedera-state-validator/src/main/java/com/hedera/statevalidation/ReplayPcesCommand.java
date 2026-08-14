// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation;

import static com.swirlds.platform.builder.internal.StaticPlatformBuilder.getMetricsProvider;
import static com.swirlds.platform.builder.internal.StaticPlatformBuilder.setupGlobalMetrics;

import com.hedera.node.app.ServicesMain;
import com.hedera.statevalidation.blockstream.ReplayPcesWorkflow;
import com.swirlds.base.time.Time;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.file.FileSystemManager;
import org.hiero.consensus.PathsConfig;
import org.hiero.consensus.model.node.NodeId;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Loads a saved state, replays a PCES stream on top of it using the consensus node's real replay mechanism, and writes
 * the resulting state snapshot to disk.
 *
 * <p>This builds the consensus layer (the same one {@code ServicesMain} builds) so that the production
 * {@code PcesModule.replayPcesEvents} path is exercised — it is not a custom replay harness. Gossip is never started;
 * only PCES replay runs, after which the advanced state is dumped.
 *
 * <p>Intended to be paired with {@code blocks-to-pces}: that tool reconstructs PCES files from a block stream, and this
 * command replays them on a node started from the matching state snapshot, to validate block-stream equivalence.
 */
@Command(
        name = "replay-pces",
        description = "Load a state, replay PCES files on top of it via the real consensus replay path, "
                + "and snapshot the resulting state.")
public class ReplayPcesCommand implements Callable<Integer> {

    @CommandLine.ParentCommand
    @SuppressWarnings("unused")
    private StateOperatorCommand parent;

    private static final Logger log = LogManager.getLogger(ReplayPcesCommand.class);

    public static final long DEFAULT_TARGET_ROUND = Long.MAX_VALUE;

    private long targetRound = DEFAULT_TARGET_ROUND;
    private Path pcesDir;
    private Path outDir = Path.of("./replay-out");
    private long selfIdValue = 0;
    private String consensusEventStreamName = "0.0.3";
    private boolean forceMockSignatures = true;

    @Option(
            names = {"-p", "--pces-dir"},
            required = true,
            description = "Directory containing the PCES files to replay (output of blocks-to-pces).")
    private void setPcesDir(final Path pcesDir) {
        this.pcesDir = pcesDir;
    }

    @Option(
            names = {"-o", "--out"},
            description = "Directory where the resulting state snapshot is written. Default = ./replay-out")
    private void setOutDir(final Path outDir) {
        this.outDir = outDir;
    }

    @Option(
            names = {"-id", "--self-id"},
            description = "Node id to run as. Must match the node id the PCES files were generated for. Default = 0")
    private void setSelfId(final long selfId) {
        this.selfIdValue = selfId;
    }

    @Option(
            names = {"-es", "--event-stream-name"},
            description = "Consensus event stream name (e.g. 0.0.3). Internal platform label only; does not "
                    + "affect replay correctness or the output state path. Default = 0.0.3")
    private void setConsensusEventStreamName(final String name) {
        this.consensusEventStreamName = name;
    }

    @Option(
            names = {"--force-mock-signatures"},
            description = "Tier 1 signing: use deterministic mock TSS proofs instead of real hinTS. Default = true")
    private void setForceMockSignatures(final boolean forceMockSignatures) {
        this.forceMockSignatures = forceMockSignatures;
    }

    @Option(
            names = {"-t", "--target-round"},
            required = true,
            description = "The round whose resulting state is retained as the output snapshot. The full PCES "
                    + "stream is still replayed (decision-margin events past this round are required to bring "
                    + "this round to consensus), and blocks for later rounds may still be generated. Required.")
    private void setTargetRound(final long targetRound) {
        this.targetRound = targetRound;
    }

    @Override
    public Integer call() throws Exception {
        parent.resolveAndGetStateDir();
        final Path stateDir = parent.getStateDir().toPath();
        // --- Replay-only flags, set before the platform configuration is built so the config picks them up ---
        // buildPlatformConfig() includes SystemPropertiesConfigSource, so these system properties take effect.
        //  - allowUnsignedPcesEvents: blocks-to-pces produces unsigned reconstructed events; without this the intake
        //    pipeline drops every replayed event at signature validation (see the unsigned-event intake path).
        //  - forceMockSignatures: Tier-1 deterministic block signing with no live TSS network.
        // IMPORTANT: this flag is non-existent in the production codebase. It requires
        // production code change that should never be a part of the main branch.
        // See commit 140f94fff19a3a6f809df339ed17349ef5ae3426 for more details.
        System.setProperty("event.preconsensus.intake.allowUnsignedPcesEvents", "true");
        if (forceMockSignatures) {
            System.setProperty("tss.forceMockSignatures", "true");
        }
        System.setProperty("event.preconsensus.forceIgnorePcesSignatures", "true");
        System.setProperty("event.preconsensus.copyRecentStreamToStateSnapshots", "false");

        // Suppress ONLY the PERIODIC_SNAPSHOT marking, without disabling state saving. With periodic
        // marking on, DefaultSavedStateController marks the first replayed state crossing a save-period
        // boundary as PERIODIC_SNAPSHOT, which routes the final-state writer into the ASYNC snapshot path
        // (blocks to the async timeout as the last replayed state; can collide with the platform's own
        // periodic snapshot over the same VirtualMap copy). This toggle gates shouldSaveToDisk()'s periodic
        // branch only; freeze-state and first-round saves still occur, and saveStatePeriod stays > 0 so the
        // normal state-release/garbage-collection cadence is unaffected (important to bound memory on long
        // replays — do NOT set saveStatePeriod=0).
        System.setProperty("state.periodicSnapshotsEnabled", "false");
        System.setProperty("state.saveStateAsync", "false");
        System.setProperty("state.saveStatePeriod", "3600");

        System.setProperty("blockStream.writerMode", "FILE");

        // --- Keep all stream output under outDir; never touch the production /opt/hgcapp defaults. ---
        // The block stream is the equivalence-validation deliverable, so direct it under outDir. The event
        // and record streams are not needed for replay and their production default directories live under
        // /opt/hgcapp (root-owned on a normal workstation); the consensus event stream eagerly creates its
        // directory during platform build, which would throw before replay starts. Disable them.

        // Block stream -> <outDir>/blockStreams  (BlockStreamConfig.blockFileDir)
        final Path blockOut = outDir.resolve("blockStreams");
        Files.createDirectories(blockOut);
        System.setProperty("blockStream.blockFileDir", blockOut.toAbsolutePath().toString());

        // Event stream -> disabled  (EventConfig.enableEventStreaming); also redirect its dir defensively
        System.setProperty("event.enableEventStreaming", "false");
        System.setProperty(
                "event.eventsLogDir",
                outDir.resolve("eventsStreams").toAbsolutePath().toString());

        // Record stream -> redirect its dir under outDir  (BlockRecordStreamConfig.logDir)
        // (writerMode=FILE + streamMode drive block output; the record stream dir must still not default to
        //  /opt/hgcapp in case any record-stream writer initializes.)
        System.setProperty(
                "hedera.recordStream.logDir",
                outDir.resolve("recordStreams").toAbsolutePath().toString());
        System.setProperty(
                "hedera.recordStream.sidecarDir",
                outDir.resolve("recordStreams")
                        .resolve("sidecar")
                        .toAbsolutePath()
                        .toString());

        final NodeId selfId = NodeId.of(selfIdValue);

        // Build the platform configuration the same way ServicesMain does (mirrors production; reads system props).
        final Configuration platformConfig = ServicesMain.buildPlatformConfig();

        // Metrics: the same static setup ServicesMain.main performs.
        setupGlobalMetrics(platformConfig);
        final Time time = Time.getCurrent();
        final Metrics metrics = getMetricsProvider().createPlatformMetrics(selfId);

        final PathsConfig pathsConfig = platformConfig.getConfigData(PathsConfig.class);
        final FileSystemManager fileSystemManager =
                new FileSystemManager(pathsConfig.savedStateDir(), pathsConfig.tmpDir());

        final long resultRound = ReplayPcesWorkflow.run(
                stateDir,
                pcesDir,
                outDir,
                selfId,
                targetRound,
                consensusEventStreamName,
                platformConfig,
                fileSystemManager,
                metrics,
                time);

        log.info("replay-pces complete: resulting state round {} written to {}", resultRound, outDir);
        return 0;
    }

    public static void main(final String... args) {
        final int rc = new CommandLine(new ReplayPcesCommand()).execute(args);
        System.exit(rc);
    }
}
