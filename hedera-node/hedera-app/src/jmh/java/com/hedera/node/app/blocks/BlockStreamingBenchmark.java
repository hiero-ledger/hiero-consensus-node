// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.node.app.blocks.impl.streaming.BlockBufferService;
import com.hedera.node.app.blocks.impl.streaming.BlockNodeClientFactory;
import com.hedera.node.app.blocks.impl.streaming.BlockNodeConnection;
import com.hedera.node.app.blocks.impl.streaming.BlockNodeConnectionHelper;
import com.hedera.node.app.blocks.impl.streaming.BlockNodeConnectionManager;
import com.hedera.node.app.blocks.impl.streaming.ConnectionState;
import com.hedera.node.app.blocks.impl.streaming.GrpcBlockItemWriter;
import com.hedera.node.app.blocks.impl.streaming.config.BlockNodeConfiguration;
import com.hedera.node.app.blocks.impl.streaming.config.BlockNodeHelidonGrpcConfiguration;
import com.hedera.node.app.blocks.impl.streaming.config.BlockNodeHelidonHttpConfiguration;
import com.hedera.node.app.blocks.utils.BlockGeneratorUtil;
import com.hedera.node.app.blocks.utils.FakeGrpcServer;
import com.hedera.node.app.blocks.utils.SimulatedNetworkProxy;
import com.hedera.node.app.metrics.BlockStreamMetrics;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.data.BlockBufferConfig;
import com.hedera.node.config.data.BlockNodeConnectionConfig;
import com.hedera.node.config.data.BlockStreamConfig;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.metrics.api.Metrics;
import java.io.IOException;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import jdk.jfr.Recording;
import org.hiero.consensus.metrics.config.MetricsConfig;
import org.hiero.consensus.metrics.platform.DefaultPlatformMetrics;
import org.hiero.consensus.metrics.platform.MetricKeyRegistry;
import org.hiero.consensus.metrics.platform.PlatformMetricsFactoryImpl;
import org.hiero.consensus.model.node.NodeId;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

/**
 * JMH benchmark for production block streaming components with simulated
 * network conditions.
 *
 * <p>
 * This benchmark supports comprehensive parametrization across 5 configuration
 * domains:
 * - BlockStreamConfig: Core streaming settings (hashing, receipts)
 * - BlockBufferConfig: Buffer capacity and backpressure thresholds
 * - BlockNodeConnectionConfig: Connection management and failover behavior
 * - HTTP/2 Client Config: Transport layer settings (window sizes, frame sizes,
 * health checks)
 * - gRPC Client Config: Protocol settings (buffers, heartbeats, timeouts)
 *
 * <p>
 * Architecture:
 * Client (App Components) <-> SimulatedNetworkProxy <-> FakeGrpcServer
 *
 * <p>
 * Lifecycle:
 * - Trial: Starts Server & Proxy, pre-generates blocks (Expensive, done once).
 * - Iteration: Re-initializes all App Components (Service, Manager, Writer) to
 * ensure
 * ACK counters and internal state are fresh for every measurement.
 *
 * @see com.hedera.node.config.data.BlockStreamConfig
 * @see com.hedera.node.config.data.BlockBufferConfig
 * @see com.hedera.node.config.data.BlockNodeConnectionConfig
 * @see com.hedera.node.app.blocks.impl.streaming.config.BlockNodeHelidonHttpConfiguration
 * @see com.hedera.node.app.blocks.impl.streaming.config.BlockNodeHelidonGrpcConfiguration
 */
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 5)
@Measurement(iterations = 10)
@Fork(1)
public class BlockStreamingBenchmark {

    // --- Workload Params ---
    @Param({"100"})
    private int numBlocks;

    @Param({"1000000"}) // 1 MB
    private long blockSizeBytes;

    @Param({"2000"}) // 2 KB items
    private int itemSizeBytes;

    // --- Network Simulation Params ---

    // Latency in milliseconds (one-way)
    // @Param({"0"})
    @Param({"20"})
    // @Param({"50"})
    private int networkLatencyMs;

    // Bandwidth in Mbps (0 = unlimited)
    // @Param({"0"})
    @Param({"1000"})
    // @Param({"100"})
    private double bandwidthMbps;

    @Param({"0.00"})
    private double packetLossRate;

    // --- BlockStreamConfig Params ---
    // @Param({"16"})
    @Param({"32"})
    // @Param({"64"})
    private int hashCombineBatchSize;

    // @Param({"1024"})
    @Param({"8192"})
    private int receiptEntriesBatchSize;

    // --- BlockBufferConfig Params ---

    // @Param({"100"})
    @Param({"150"})

    // @Param({"200"})
    private int maxBlocks;

    // @Param({"20.0"})
    @Param({"50.0"})
    private double actionStageThreshold; // (%)

    @Param({"85.0"})
    private double recoveryThreshold; // (%)

    // --- BlockNodeConnectionConfig Params ---

    // @Param({"10000"})
    @Param({"30000"})
    private int highLatencyThresholdMs;

    // @Param({"3"})
    @Param({"5"})
    // @Param({"10"})
    private int highLatencyEventsBeforeSwitching;

    // @Param({"5000"})
    @Param({"10000"})
    private int maxBackoffDelayMs;

    @Param({"30000"})
    private int grpcOverallTimeoutMs;

    @Param({"3000"})
    private int pipelineOperationTimeoutMs;

    // --- HTTP/2 Client Config Params ---
    @Param({"65535" /* , "131072" */})
    private int http2InitialWindowSize;

    @Param({"16384" /* , "32768" */})
    private int http2MaxFrameSize;

    @Param({"false" /* , "true" */})
    private boolean http2Ping; // HTTP/2 ping health check

    @Param({"true"})
    private boolean http2PriorKnowledge;

    // --- gRPC Client Config Params ---
    @Param({"false"})
    private boolean grpcAbortPollTimeExpired;

    @Param({"0" /* , "30000" */}) // (0 = disabled)
    private int grpcHeartbeatPeriodMs;

    @Param({"512" /* , "1024", "4096" */})
    private int grpcInitialBufferSize;

    @Param({"10000"})
    private int grpcPollWaitTimeMs;

    // --- Backpressure Testing Params ---
    // Delay between block writes in milliseconds (0 = no delay, write as fast as
    // possible)
    // Use non-zero values to simulate realistic block production rate and test
    // sustained backpressure
    @Param({"0"})
    private int blockProductionDelayMs;

    // --- Infrastructure (Long-lived) ---
    private FakeGrpcServer server;
    private SimulatedNetworkProxy networkProxy;
    private List<Block> blocks;
    private Recording jfrRecording; // JFR recording handle

    // --- Application Components (Recreated per Iteration) ---
    private BlockBufferService bufferService;
    private BlockNodeConnectionManager connectionManager;
    private GrpcBlockItemWriter writer;
    private ScheduledExecutorService scheduler;
    private ExecutorService pipelineExecutor;
    private ScheduledExecutorService metricsScheduler;

    // --- Metrics ---
    private long benchmarkStartTime;
    private long benchmarkEndTime;

    /**
     * Heavy initialization: Run once per complete Benchmark Trial.
     * Starts the Server and Proxy, and pre-generates the data.
     */
    @Setup(Level.Trial)
    public void setupTrial() throws IOException {
        System.out.println(">>> Setting up Trial Infrastructure...");

        // 0. Start JFR recording with custom short filename based on key parameters
        try {
            String projectRoot = System.getProperty("user.dir");
            String jfrFilename = String.format(
                    "%s/hedera-node/hedera-app/src/jmh/java/com/hedera/node/app/blocks/jfr/bench-lat%d-bw%.0f-buf%d-http%d-grpc%d.jfr",
                    projectRoot,
                    networkLatencyMs,
                    bandwidthMbps,
                    maxBlocks,
                    http2InitialWindowSize,
                    grpcInitialBufferSize);
            jfrRecording = new Recording(jdk.jfr.Configuration.getConfiguration("profile"));
            jfrRecording.setDestination(Paths.get(jfrFilename));
            jfrRecording.start();
            System.out.println(">>> JFR recording started: " + jfrFilename);
        } catch (Exception e) {
            System.err.println(">>> Failed to start JFR recording: " + e.getMessage());
            jfrRecording = null;
        }

        // 1. Start Real Server
        // Note: Server latency (LatencyConfig) simulates processing time, distinct from
        // network latency (handled by
        // Proxy)
        server = FakeGrpcServer.builder()
                .port(0)
                .latency(FakeGrpcServer.LatencyConfig.none())
                .build();
        server.start();

        // 2. Start Network Proxy
        // This sits between Client and Server to inject delays/bandwidth limits
        networkProxy = new SimulatedNetworkProxy(server.getPort(), networkLatencyMs, bandwidthMbps, packetLossRate);
        networkProxy.start();

        // 3. Pre-generate Blocks to exclude generation time from benchmark
        System.out.printf("Pre-generating %d blocks of %d bytes...%n", numBlocks, blockSizeBytes);
        blocks = BlockGeneratorUtil.generateBlocks(0, numBlocks, blockSizeBytes, itemSizeBytes);
        System.out.printf(
                "Infrastructure Ready. Proxy Port: %d -> Server Port: %d%n", networkProxy.getPort(), server.getPort());
    }

    /**
     * Heavy Teardown: Run once at the very end.
     */
    @TearDown(Level.Trial)
    public void teardownTrial() throws Exception {
        if (networkProxy != null) networkProxy.close();
        if (server != null) server.stop();
        System.out.println(">>> Trial Infrastructure Stopped.");

        // Stop JFR recording
        if (jfrRecording != null) {
            try {
                jfrRecording.stop();
                jfrRecording.close();
                System.out.println(">>> JFR recording stopped and saved.");
            } catch (Exception e) {
                System.err.println(">>> Failed to stop JFR recording: " + e.getMessage());
            }
        }
    }

    /**
     * Lightweight Initialization: Run before EVERY single iteration.
     * Re-creates the application services to reset ACK counters and buffers.
     */
    @Setup(Level.Iteration)
    public void setupIteration() {
        server.resetMetrics();
        benchmarkStartTime = 0;
        benchmarkEndTime = 0;

        // 1. Configuration (Using parametrized values)
        final Configuration config = ConfigurationBuilder.create()
                .withConfigDataType(BlockStreamConfig.class)
                .withConfigDataType(BlockBufferConfig.class)
                .withConfigDataType(BlockNodeConnectionConfig.class)
                .withConfigDataType(MetricsConfig.class)
                // BlockStreamConfig
                .withValue("blockStream.writerMode", "GRPC")
                .withValue("blockStream.hashCombineBatchSize", String.valueOf(hashCombineBatchSize))
                .withValue("blockStream.receiptEntriesBatchSize", String.valueOf(receiptEntriesBatchSize))
                // BlockBufferConfig
                .withValue("blockStream.buffer.maxBlocks", String.valueOf(maxBlocks))
                .withValue("blockStream.buffer.actionStageThreshold", String.valueOf(actionStageThreshold))
                .withValue("blockStream.buffer.recoveryThreshold", String.valueOf(recoveryThreshold))
                // BlockNodeConnectionConfig
                .withValue("blockNode.highLatencyThresholdMs", String.valueOf(highLatencyThresholdMs))
                .withValue(
                        "blockNode.highLatencyEventsBeforeSwitching", String.valueOf(highLatencyEventsBeforeSwitching))
                .withValue("blockNode.maxBackoffDelay", maxBackoffDelayMs + "ms")
                .withValue("blockNode.grpcOverallTimeout", grpcOverallTimeoutMs + "ms")
                .withValue("blockNode.pipelineOperationTimeout", pipelineOperationTimeoutMs + "ms")
                .withValue("blockNode.minRetryIntervalMs", "5000")
                .build();

        final ConfigProvider configProvider = () -> new VersionedConfigImpl(config, 1L);

        // 2. Thread Pools
        metricsScheduler = Executors.newScheduledThreadPool(1);
        scheduler = Executors.newScheduledThreadPool(2);
        pipelineExecutor = Executors.newVirtualThreadPerTaskExecutor();

        // 3. Metrics
        final Metrics metrics = new DefaultPlatformMetrics(
                NodeId.of(0L),
                new MetricKeyRegistry(),
                metricsScheduler,
                new PlatformMetricsFactoryImpl(config.getConfigData(MetricsConfig.class)),
                config.getConfigData(MetricsConfig.class));
        final BlockStreamMetrics blockStreamMetrics = new BlockStreamMetrics(metrics);

        // 4. Services
        bufferService = new BlockBufferService(configProvider, blockStreamMetrics);
        bufferService.start();

        connectionManager = new BlockNodeConnectionManager(configProvider, bufferService, blockStreamMetrics);
        bufferService.setBlockNodeConnectionManager(connectionManager);
        connectionManager.start();

        // 5. Connection Setup (CONNECT TO PROXY PORT) with parametrized HTTP/2 and gRPC
        // configs
        final BlockNodeHelidonHttpConfiguration httpConfig = BlockNodeHelidonHttpConfiguration.newBuilder()
                .initialWindowSize(http2InitialWindowSize)
                .maxFrameSize(http2MaxFrameSize)
                .ping(http2Ping)
                .pingTimeout(http2Ping ? Duration.ofMillis(500) : null)
                .priorKnowledge(http2PriorKnowledge)
                .flowControlBlockTimeout(Duration.ofSeconds(15))
                .build();

        final BlockNodeHelidonGrpcConfiguration grpcConfig = BlockNodeHelidonGrpcConfiguration.newBuilder()
                .abortPollTimeExpired(grpcAbortPollTimeExpired)
                .heartbeatPeriod(grpcHeartbeatPeriodMs > 0 ? Duration.ofMillis(grpcHeartbeatPeriodMs) : null)
                .initialBufferSize(grpcInitialBufferSize)
                .pollWaitTime(Duration.ofMillis(grpcPollWaitTimeMs))
                .name("benchmark-grpc")
                .build();

        final BlockNodeConfiguration nodeConfig = BlockNodeConfiguration.newBuilder()
                .address("localhost")
                .streamingPort(networkProxy.getPort()) // Connect to Proxy!
                .priority(0)
                .messageSizeSoftLimitBytes(2_097_152)
                .messageSizeHardLimitBytes(4_194_304)
                .clientHttpConfig(httpConfig)
                .clientGrpcConfig(grpcConfig)
                .build();

        final BlockNodeConnection connection = new BlockNodeConnection(
                configProvider,
                nodeConfig,
                connectionManager,
                bufferService,
                blockStreamMetrics,
                scheduler,
                pipelineExecutor,
                0L,
                new BlockNodeClientFactory());

        connection.initialize();
        BlockNodeConnectionHelper.updateConnectionState(connection, ConnectionState.ACTIVE);

        // 6. Writer
        writer = new GrpcBlockItemWriter(bufferService, connectionManager);
    }

    /**
     * Lightweight Teardown: Run after EVERY iteration.
     * Shuts down app components but keeps Server/Proxy alive.
     */
    @TearDown(Level.Iteration)
    public void teardownIteration() {
        // Calculate Throughput
        if (benchmarkStartTime > 0 && benchmarkEndTime > 0) {
            double elapsedSeconds = (benchmarkEndTime - benchmarkStartTime) / 1_000_000_000.0;
            long totalBytes = numBlocks * blockSizeBytes;
            double megabytesPerSec = (totalBytes / (1024.0 * 1024.0)) / elapsedSeconds;
            double gigabitsPerSec = (totalBytes * 8.0) / (1_000_000_000.0 * elapsedSeconds);

            System.out.printf(
                    ">>> [Lat: %dms, BW: %.0f Mbps, Buffer: %d, HTTP2Win: %d, gRPC: %d] Result: %.2f MB/s (%.2f Gbps) in %.2fs%n",
                    networkLatencyMs,
                    bandwidthMbps,
                    maxBlocks,
                    http2InitialWindowSize,
                    grpcInitialBufferSize,
                    megabytesPerSec,
                    gigabitsPerSec,
                    elapsedSeconds);
        }

        // Shutdown App Components
        if (bufferService != null) bufferService.shutdown();
        if (connectionManager != null) connectionManager.shutdown();
        if (scheduler != null) scheduler.shutdownNow();
        if (pipelineExecutor != null) pipelineExecutor.shutdownNow();
        if (metricsScheduler != null) metricsScheduler.shutdownNow();

        // GC to keep next iteration clean
        System.gc();
    }

    @Benchmark
    public void streamBlocks(Blackhole bh) throws Exception {
        // Start timing after first block begins to exclude connection setup overhead
        boolean timingStarted = false;

        // 1. Write all blocks to the buffer
        for (int i = 0; i < blocks.size(); i++) {
            bufferService.ensureNewBlocksPermitted();
            writer.openBlock(i);

            // Start timing after opening first block (connection is established)
            if (!timingStarted) {
                benchmarkStartTime = System.nanoTime();
                timingStarted = true;
            }

            for (BlockItem item : blocks.get(i).items()) {
                writer.writePbjItemAndBytes(item, BlockItem.PROTOBUF.toBytes(item));
            }

            writer.closeCompleteBlock();

            // Add configurable delay between blocks to simulate realistic production rate
            // and test sustained backpressure (0 = no delay, write as fast as possible)
            if (blockProductionDelayMs > 0 && i < blocks.size() - 1) {
                Thread.sleep(blockProductionDelayMs);
            }
        }

        // 2. Wait for ACKs
        // Since we re-initialized bufferService in @Setup(Level.Iteration),
        // getHighestAckedBlockNumber() starts at -1 and will correctly wait.
        while (bufferService.getHighestAckedBlockNumber() < numBlocks - 1) {
            Thread.sleep(10);
        }

        benchmarkEndTime = System.nanoTime();
        bh.consume(bufferService.getHighestAckedBlockNumber());
    }

    public static void main(String[] args) throws Exception {
        // JFR profiling managed manually in setupTrial/teardownTrial
        // Files saved to: hedera-app/src/jmh/java/com/hedera/node/app/blocks/jfr/
        // Filename format:
        // bench-lat<ms>-bw<mbps>-buf<blocks>-http<winsize>-grpc<bufsize>.jfr
        // Same parameter combinations will overwrite previous runs
        org.openjdk.jmh.Main.main(new String[] {"BlockStreamingBenchmark", "-v", "EXTRA" /* , "-prof", "gc" */});
    }
}
