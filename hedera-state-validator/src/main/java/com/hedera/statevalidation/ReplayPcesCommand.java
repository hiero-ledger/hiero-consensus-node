// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation;

import static com.swirlds.platform.builder.internal.StaticPlatformBuilder.getMetricsProvider;
import static com.swirlds.platform.builder.internal.StaticPlatformBuilder.setupGlobalMetrics;

import com.hedera.node.app.ServicesMain;
import com.hedera.statevalidation.blockstream.ReplayPcesWorkflow;
import com.swirlds.base.time.Time;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.util.BootstrapUtils;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.file.FileSystemManager;
import org.hiero.consensus.config.PathsConfig;
import org.hiero.consensus.model.node.NodeId;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Loads a saved state, replays a PCES stream on top of it using the consensus node's real replay mechanism, and writes
 * the resulting state snapshot to disk.
 *
 * <p>This builds and starts a genuine {@code SwirldsPlatform} (the same one {@code ServicesMain} builds) so that the
 * production {@code PcesModule.replayPcesEvents} path is exercised — it is not a custom replay harness. Gossip is never
 * started; only PCES replay runs, after which the advanced state is dumped.
 *
 * <p>Intended to be paired with {@code blocks-to-pces}: that tool reconstructs PCES files from a block stream, and this
 * command replays them on a node started from the matching state snapshot, to validate block-stream equivalence.
 */
@Command(
        name = "replay-pces",
        description = "Load a state, replay PCES files on top of it via the real consensus replay path, "
                + "and snapshot the resulting state.")
public class ReplayPcesCommand implements Callable<Integer> {

    private static final Logger log = LogManager.getLogger(ReplayPcesCommand.class);

    private Path stateDir;
    private Path pcesDir;
    private Path outDir = Path.of("./replay-out");
    private long selfIdValue = 0;
    private String consensusEventStreamName = "0.0.3";
    private boolean forceMockSignatures = true;

    @Option(
            names = {"-s", "--state-dir"},
            required = true,
            description = "Directory containing the saved state snapshot to load.")
    private void setStateDir(final Path stateDir) {
        this.stateDir = stateDir;
    }

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
            description = "Consensus event stream name (e.g. 0.0.3). Names an output directory only. Default = 0.0.3")
    private void setConsensusEventStreamName(final String name) {
        this.consensusEventStreamName = name;
    }

    @Option(
            names = {"--force-mock-signatures"},
            description = "Tier 1 signing: use deterministic mock TSS proofs instead of real hinTS. Default = true")
    private void setForceMockSignatures(final boolean forceMockSignatures) {
        this.forceMockSignatures = forceMockSignatures;
    }

    @Override
    public Integer call() throws Exception {
        // --- Replay-only flags, set before the platform configuration is built so the config picks them up ---
        // buildPlatformConfig() includes SystemPropertiesConfigSource, so these system properties take effect.
        //  - allowUnsignedPcesEvents: blocks-to-pces produces unsigned reconstructed events; without this the intake
        //    pipeline drops every replayed event at signature validation (see the unsigned-event intake path).
        //  - forceMockSignatures: Tier-1 deterministic block signing with no live TSS network.
        System.setProperty("event.preconsensus.intake.allowUnsignedPcesEvents", "true");
        if (forceMockSignatures) {
            System.setProperty("tss.forceMockSignatures", "true");
        }

        final NodeId selfId = NodeId.of(selfIdValue);

        // Constructable registry must be set up before any state deserialization, exactly as ServicesMain.main does.
        BootstrapUtils.setupConstructableRegistry();

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
