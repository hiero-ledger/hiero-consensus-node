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
import com.hedera.node.config.types.BlockStreamWriterMode;
import com.hedera.services.bdd.junit.extensions.NetworkTargetingExtension;
import com.hedera.services.bdd.junit.hedera.BlockNodeMode;
import com.hedera.services.bdd.junit.hedera.BlockNodeNetwork;
import com.hedera.services.bdd.junit.hedera.containers.BlockNodeSubscribeClient;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.utilops.UtilOp;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class HapiSpecWaitUntilNextBlock extends UtilOp {
    private static final Logger log = LogManager.getLogger(HapiSpecWaitUntilNextBlock.class);
    private static final String BLOCK_FILE_EXTENSION = ".blk";
    private static final String COMPRESSED_BLOCK_FILE_EXTENSION = BLOCK_FILE_EXTENSION + ".gz";
    private static final String MARKER_FILE_EXTENSION = ".mf";
    private static final Duration POLL_INTERVAL = Duration.ofMillis(100);
    private static final Duration BACKGROUND_TRAFFIC_INTERVAL = Duration.ofMillis(1000);
    private Duration timeout = Duration.ofSeconds(30);

    private boolean backgroundTraffic;
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
        if (isWriterModeGrpcOnly(spec)) {
            return submitOpViaBlockNodes(spec);
        }
        return submitOpViaDisk(spec);
    }

    private boolean submitOpViaBlockNodes(@NonNull final HapiSpec spec) throws Throwable {
        final var blockNodeNetwork = resolveBlockNodeNetwork();
        if (blockNodeNetwork == null) {
            throw new IllegalStateException("No block node network available for GRPC-only writer mode");
        }

        final var currentBlock = findLatestBlockNumberFromBlockNodes(blockNodeNetwork);
        final var targetBlock = currentBlock + blocksToWaitFor;

        log.info(
                "Waiting for block {} via block nodes (current block is {}, waiting for {})",
                targetBlock,
                currentBlock,
                blocksToWaitFor);

        final var stopTraffic = new AtomicBoolean(false);
        CompletableFuture<?> trafficFuture = backgroundTraffic ? startBackgroundTraffic(spec, stopTraffic) : null;

        try {
            final var startTime = System.currentTimeMillis();
            while (true) {
                if (isBlockCompleteOnBlockNodes(blockNodeNetwork, targetBlock)) {
                    log.info("Block {} confirmed on block nodes", targetBlock);
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

    private boolean submitOpViaDisk(@NonNull final HapiSpec spec) throws Throwable {
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

        final var stopTraffic = new AtomicBoolean(false);
        CompletableFuture<?> trafficFuture = backgroundTraffic ? startBackgroundTraffic(spec, stopTraffic) : null;

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

    private CompletableFuture<?> startBackgroundTraffic(
            @NonNull final HapiSpec spec, @NonNull final AtomicBoolean stopTraffic) {
        return CompletableFuture.runAsync(() -> {
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

    @Nullable
    private static BlockNodeNetwork resolveBlockNodeNetwork() {
        var network = HapiSpec.TARGET_BLOCK_NODE_NETWORK.get();
        if (network == null) {
            network = NetworkTargetingExtension.SHARED_BLOCK_NODE_NETWORK.get();
        }
        return network;
    }

    private static long findLatestBlockNumberFromBlockNodes(@NonNull final BlockNodeNetwork blockNodeNetwork) {
        final var mode = blockNodeNetwork.getBlockNodeModeById().values().stream()
                .findFirst()
                .orElse(BlockNodeMode.NONE);
        if (mode == BlockNodeMode.SIMULATOR) {
            return blockNodeNetwork.getSimulatedBlockNodeById().values().stream()
                    .mapToLong(sim -> sim.getLastVerifiedBlockNumber())
                    .max()
                    .orElse(-1L);
        } else if (mode == BlockNodeMode.REAL) {
            for (final var entry : blockNodeNetwork.getBlockNodeContainerById().entrySet()) {
                try (final var client = new BlockNodeSubscribeClient(
                        entry.getValue().getHost(), entry.getValue().getPort())) {
                    return client.getLastAvailableBlock();
                } catch (Exception e) {
                    log.warn("Failed to query real block node {} for latest block", entry.getKey(), e);
                }
            }
        }
        return -1L;
    }

    private static boolean isBlockCompleteOnBlockNodes(
            @NonNull final BlockNodeNetwork blockNodeNetwork, final long blockNumber) {
        final var mode = blockNodeNetwork.getBlockNodeModeById().values().stream()
                .findFirst()
                .orElse(BlockNodeMode.NONE);
        if (mode == BlockNodeMode.SIMULATOR) {
            return blockNodeNetwork.getSimulatedBlockNodeById().values().stream()
                    .anyMatch(sim -> sim.hasReceivedBlock(blockNumber));
        } else if (mode == BlockNodeMode.REAL) {
            for (final var entry : blockNodeNetwork.getBlockNodeContainerById().entrySet()) {
                try (final var client = new BlockNodeSubscribeClient(
                        entry.getValue().getHost(), entry.getValue().getPort())) {
                    return client.getLastAvailableBlock() >= blockNumber;
                } catch (Exception e) {
                    log.warn("Failed to query real block node {} for block {}", entry.getKey(), blockNumber, e);
                }
            }
        }
        return false;
    }

    private static boolean isWriterModeGrpcOnly(@NonNull final HapiSpec spec) {
        try {
            final var writerMode = spec.startupProperties().get("blockStream.writerMode");
            return BlockStreamWriterMode.GRPC.name().equals(writerMode);
        } catch (Exception e) {
            return false;
        }
    }
}
