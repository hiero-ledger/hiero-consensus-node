// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.pauses;

import static com.hedera.services.bdd.junit.hedera.ExternalPath.BLOCK_STREAMS_DIR;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.suites.HapiSuite.FUNDING;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;

import com.hedera.node.app.blocks.impl.streaming.FileBlockItemWriter;
import com.hedera.node.app.hapi.utils.blocks.BlockStreamAccess;
import com.hedera.services.bdd.junit.extensions.NetworkTargetingExtension;
import com.hedera.services.bdd.junit.hedera.BlockNodeNetwork;
import com.hedera.services.bdd.junit.hedera.simulator.SimulatedBlockNodeServer;
import com.hedera.services.bdd.junit.support.BlockStreamAccess;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.utilops.UtilOp;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;

/**
 * Utility operation that waits until the next block(s) have been created.
 *
 * <p>This operation automatically detects the block stream configuration from the spec:
 * <ul>
 *   <li><b>Writer Mode</b>: Automatically determines whether to wait for file-based or GRPC-based blocks
 *       based on the {@code blockStream.writerMode} property.</li>
 *   <li><b>Block Period</b>: Automatically uses the {@code blockStream.blockPeriod} property for timing.</li>
 *   <li><b>Block Node ID</b>: Automatically selects the first available simulated block node when in GRPC mode.</li>
 * </ul>
 */
public class HapiSpecWaitUntilNextBlock extends UtilOp {
    private static final Logger log = LogManager.getLogger(HapiSpecWaitUntilNextBlock.class);
    private static final String BLOCK_FILE_EXTENSION = ".blk";
    private static final String COMPRESSED_BLOCK_FILE_EXTENSION = BLOCK_FILE_EXTENSION + ".gz";
    private static final String MARKER_FILE_EXTENSION = ".mf";
    private static final Duration POLL_INTERVAL = Duration.ofMillis(100);
    private static final Duration BACKGROUND_TRAFFIC_INTERVAL = Duration.ofMillis(1000);
    private Duration timeout = Duration.ofSeconds(30);

    private boolean backgroundTraffic;
    private Duration blockPeriod = Duration.ofSeconds(2);
    private int blocksToWaitFor = 1; // Default to waiting for the next single block

    public HapiSpecWaitUntilNextBlock withBackgroundTraffic(final boolean backgroundTraffic) {
        this.backgroundTraffic = backgroundTraffic;
        return this;
    }

    /**
     * Sets the number of blocks to wait for after the current latest block.
     *
     * @param count the number of blocks to wait for
     * @return this operation
     */
    public HapiSpecWaitUntilNextBlock waitingForBlocks(final int count) {
        if (count <= 0) {
            throw new IllegalArgumentException("Must wait for at least one block");
        }
        this.blocksToWaitFor = count;
        this.timeout = Duration.ofSeconds(10L * count);
        return this;
    }

    @Override
    protected boolean submitOp(@NonNull final HapiSpec spec) throws Throwable {

        // Check if we are in subprocess network or embedded
        final var networkMode = spec.startupProperties().get("network.mode");
        final boolean isSubprocess = "SUBPROCESS".equals(networkMode);
        log.info("Auto-detected network mode: {}", networkMode);

        // Auto-detect writer mode from spec configuration
        final var writerMode = spec.startupProperties().get("blockStream.writerMode");

        // Check if simulator is available regardless of network mode
        final BlockNodeNetwork blockNodeNetwork = NetworkTargetingExtension.SHARED_BLOCK_NODE_NETWORK.get();
        final boolean hasSimulator = blockNodeNetwork != null
                && !blockNodeNetwork.getSimulatedBlockNodeById().isEmpty();

        // In GRPC mode, use simulator if available; in FILE_AND_GRPC mode, use simulator if in subprocess
        boolean onBlockNodeSide =
                ("GRPC".equals(writerMode) && hasSimulator) || ("FILE_AND_GRPC".equals(writerMode) && isSubprocess);
        log.info(
                "Auto-detected writer mode: {}, hasSimulator: {} -> onBlockNodeSide = {}",
                writerMode,
                hasSimulator,
                onBlockNodeSide);

        // Auto-detect block period from spec configuration
        try {
            this.blockPeriod = spec.startupProperties().getConfigDuration("blockStream.blockPeriod");
            log.info("Auto-detected block period: {}", blockPeriod);
        } catch (Exception e) {
            log.warn("Failed to auto-detect block period, using default: {}", blockPeriod, e);
        }

        // Auto-detect block node ID from available simulators
        long blockNodeId = 0; // Default fallback
        if (onBlockNodeSide && hasSimulator) {
            // Use the first available block node ID
            blockNodeId = blockNodeNetwork
                    .getSimulatedBlockNodeById()
                    .keySet()
                    .iterator()
                    .next();
            log.info("Auto-detected block node ID: {}", blockNodeId);
        }

        if (onBlockNodeSide) {
            return submitOpGrpc(spec, blockNodeId);
        } else {
            return submitOpFile(spec);
        }
    }

    private boolean submitOpFile(@NotNull HapiSpec spec) throws IOException {
        final var blockDir = spec.targetNetworkOrThrow().nodes().getFirst().getExternalPath(BLOCK_STREAMS_DIR);
        if (blockDir == null) {
            throw new IllegalStateException("Block stream directory not available");
        }

        // Ensure the directory exists before trying to walk it
        if (!Files.exists(blockDir)) {
            log.info("Creating block stream directory at {}", blockDir);
            Files.createDirectories(blockDir);
        }

        final var currentBlock = findLatestBlockNumber(blockDir);
        final var targetBlock = currentBlock + blocksToWaitFor;

        log.info(
                "Waiting for block {} to appear (current block is {}, waiting for {})",
                targetBlock,
                currentBlock,
                blocksToWaitFor);

        // Start background traffic AFTER capturing current block to avoid race condition
        final var stopTraffic = new AtomicBoolean(false);
        CompletableFuture<?> trafficFuture = null;
        if (backgroundTraffic) {
            trafficFuture = CompletableFuture.runAsync(() -> {
                while (!stopTraffic.get()) {
                    try {
                        allRunFor(
                                spec,
                                cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1))
                                        .deferStatusResolution()
                                        .noLogging()
                                        .hasAnyStatusAtAll());
                        spec.sleepConsensusTime(BACKGROUND_TRAFFIC_INTERVAL);
                    } catch (Exception e) {
                        log.info("Background traffic iteration failed", e);
                    }
                }
            });
        }

        try {
            final var startTime = System.currentTimeMillis();
            while (true) {
                if (isBlockComplete(blockDir, targetBlock)) {
                    log.info("Block {} has been created and completed", targetBlock);
                    return false;
                }
                if (System.currentTimeMillis() - startTime > timeout.toMillis()) {
                    throw new RuntimeException(String.format(
                            "Timeout waiting for block %d after %d seconds", targetBlock, timeout.toSeconds()));
                }
                spec.sleepConsensusTime(POLL_INTERVAL);
            }
        } finally {
            if (trafficFuture != null) {
                stopTraffic.set(true);
                trafficFuture.join();
            }
        }
    }

    private long findLatestBlockNumber(Path blockDir) throws IOException {
        try (Stream<Path> files = Files.walk(blockDir)) {
            return files.filter(this::isBlockFile)
                    .map(BlockStreamAccess::extractBlockNumber)
                    .filter(num -> num >= 0)
                    .max(Long::compareTo)
                    .orElse(-1L);
        }
    }

    private boolean isBlockComplete(Path blockDir, long blockNumber) throws IOException {
        try (Stream<Path> files = Files.walk(blockDir)) {
            return files.anyMatch(path -> {
                String fileName = path.getFileName().toString();
                return fileName.startsWith(FileBlockItemWriter.longToFileName(blockNumber))
                        && fileName.endsWith(MARKER_FILE_EXTENSION);
            });
        }
    }

    private boolean isBlockFile(Path path) {
        String fileName = path.getFileName().toString();
        return Files.isRegularFile(path)
                && (fileName.endsWith(BLOCK_FILE_EXTENSION) || fileName.endsWith(COMPRESSED_BLOCK_FILE_EXTENSION));
    }

    private boolean submitOpGrpc(@NotNull HapiSpec spec, long blockNodeId) {
        int blocksReceivedBefore = getSimulatorTotalBlocksReceived(blockNodeId);
        log.info("Simulator has received {} blocks before waiting", blocksReceivedBefore);

        // Start background traffic AFTER capturing baseline to avoid race condition
        final var stopTraffic = new AtomicBoolean(false);
        CompletableFuture<?> trafficFuture = null;
        if (backgroundTraffic) {
            trafficFuture = CompletableFuture.runAsync(() -> {
                while (!stopTraffic.get()) {
                    try {
                        allRunFor(
                                spec,
                                cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1))
                                        .deferStatusResolution()
                                        .noLogging()
                                        .hasAnyStatusAtAll());
                        spec.sleepConsensusTime(BACKGROUND_TRAFFIC_INTERVAL);
                    } catch (Exception e) {
                        log.info("Background traffic iteration failed", e);
                    }
                }
            });
        }

        try {
            final var startTime = System.currentTimeMillis();
            // sleep here to avoid multiple simulator check-ups
            spec.sleepConsensusTime(blockPeriod.multipliedBy(blocksToWaitFor));
            while (true) {
                int blocksReceived = getSimulatorTotalBlocksReceived(blockNodeId);
                if (blocksReceived - blocksReceivedBefore >= blocksToWaitFor) {
                    log.info(
                            "Simulator has received {} blocks total ({} new blocks)",
                            blocksReceived,
                            blocksReceived - blocksReceivedBefore);
                    return false;
                }
                if (System.currentTimeMillis() - startTime > timeout.toMillis()) {
                    throw new RuntimeException(String.format(
                            "Timeout waiting for blocks. Received %d out of %d after %d seconds",
                            blocksReceived - blocksReceivedBefore, blocksToWaitFor, timeout.toSeconds()));
                }
                spec.sleepConsensusTime(blockPeriod);
            }
        } finally {
            if (trafficFuture != null) {
                stopTraffic.set(true);
                trafficFuture.join();
            }
        }
    }

    private Integer getSimulatorTotalBlocksReceived(long blockNodeId) {
        final BlockNodeNetwork blockNodeNetwork = NetworkTargetingExtension.SHARED_BLOCK_NODE_NETWORK.get();
        if (blockNodeNetwork == null) {
            return 0;
        }

        SimulatedBlockNodeServer simulatedBlockNodeServer =
                blockNodeNetwork.getSimulatedBlockNodeById().get(blockNodeId);
        if (Objects.nonNull(simulatedBlockNodeServer)) {
            int size = simulatedBlockNodeServer.getReceivedBlockNumbers().size();
            log.info("Read received block size {} from simulator {}", size, blockNodeId);
            return size;
        }
        return 0;
    }
}
