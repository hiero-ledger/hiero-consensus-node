// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.node.app.blocks.impl.streaming.BlockBufferService;
import com.hedera.node.app.blocks.impl.streaming.BlockNodeClientFactory;
import com.hedera.node.app.blocks.impl.streaming.BlockNodeConnection;
import com.hedera.node.app.blocks.impl.streaming.BlockNodeConnectionManager;
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
import com.swirlds.common.metrics.config.MetricsConfig;
import com.swirlds.common.metrics.platform.DefaultPlatformMetrics;
import com.swirlds.common.metrics.platform.MetricKeyRegistry;
import com.swirlds.common.metrics.platform.PlatformMetricsFactoryImpl;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.metrics.api.Metrics;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.hiero.consensus.model.node.NodeId;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

/**
 * JMH benchmark for production block streaming components with simulated network conditions.
 *
 * Architecture:
 * Client (App Components) <-> SimulatedNetworkProxy (Pure Java) <-> FakeGrpcServer
 *
 * Lifecycle:
 * - Trial: Starts Server & Proxy, pre-generates blocks (Expensive, done once).
 * - Iteration: Re-initializes all App Components (Service, Manager, Writer) to ensure
 * ACK counters and internal state are fresh for every measurement.
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
    @Param({"0", "20", "50"}) // Latency in milliseconds (one-way)
    private int networkLatencyMs;

    @Param({"0", "100", "1000"}) // Bandwidth in Mbps (0 = unlimited)
    private double bandwidthMbps;

    @Param({"0.00"}) // Packet loss probability
    private double packetLossRate;

    // --- Infrastructure (Long-lived) ---
    private FakeGrpcServer server;
    private SimulatedNetworkProxy networkProxy;
    private List<Block> blocks;

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

        // 1. Start Real Server (No internal latency, we handle that in Proxy)
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

        // 1. Configuration (High timeouts for slow network simulation)
        final Configuration config = ConfigurationBuilder.create()
                .withConfigDataType(BlockStreamConfig.class)
                .withConfigDataType(BlockBufferConfig.class)
                .withConfigDataType(BlockNodeConnectionConfig.class)
                .withConfigDataType(MetricsConfig.class)
                .withValue("blockStream.writerMode", "GRPC")
                .withValue("blockNode.highLatencyEventsBeforeSwitching", "50")
                .withValue("blockNode.highLatencyThresholdMs", "10000") // 10s
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

        // 5. Connection Setup (CONNECT TO PROXY PORT)
        final BlockNodeConfiguration nodeConfig = BlockNodeConfiguration.newBuilder()
                .address("localhost")
                .port(networkProxy.getPort()) // <--- Connect to Proxy!
                .priority(0)
                .messageSizeSoftLimitBytes(2_097_152)
                .messageSizeHardLimitBytes(4_194_304)
                .clientHttpConfig(BlockNodeHelidonHttpConfiguration.DEFAULT)
                .clientGrpcConfig(BlockNodeHelidonGrpcConfiguration.DEFAULT)
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

        connection.createRequestPipeline();
        connection.updateConnectionState(BlockNodeConnection.ConnectionState.ACTIVE);

        // 6. Writer
        writer = new GrpcBlockItemWriter(bufferService, connectionManager);
    }

    /**
     * Lightweight Teardown: Run after EVERY iteration.
     * Shuts down app components but keeps Server/Proxy alive.
     */
    @TearDown(Level.Iteration)
    public void teardownIteration() throws Exception {
        // Calculate Throughput
        if (benchmarkStartTime > 0 && benchmarkEndTime > 0) {
            double elapsedSeconds = (benchmarkEndTime - benchmarkStartTime) / 1_000_000_000.0;
            long totalBytes = numBlocks * blockSizeBytes;
            double megabytesPerSec = (totalBytes / (1024.0 * 1024.0)) / elapsedSeconds;
            double gigabitsPerSec = (totalBytes * 8.0) / (1_000_000_000.0 * elapsedSeconds);

            System.out.printf(
                    ">>> [Lat: %dms, BW: %.0f Mbps] Result: %.2f MB/s (%.2f Gbps) in %.2fs%n",
                    networkLatencyMs, bandwidthMbps, megabytesPerSec, gigabitsPerSec, elapsedSeconds);
        }

        // Shutdown App Components
        if (bufferService != null) bufferService.shutdown();
        if (connectionManager != null) connectionManager.shutdown();
        if (scheduler != null) scheduler.shutdownNow();
        if (pipelineExecutor != null) pipelineExecutor.shutdownNow();
        if (metricsScheduler != null) metricsScheduler.shutdownNow();

        // Force GC to keep next iteration clean
        System.gc();
    }

    @Benchmark
    public void streamBlocks(Blackhole bh) throws Exception {
        benchmarkStartTime = System.nanoTime();

        // 1. Write all blocks to the buffer
        for (int i = 0; i < blocks.size(); i++) {
            bufferService.ensureNewBlocksPermitted();
            writer.openBlock(i);

            for (BlockItem item : blocks.get(i).items()) {
                writer.writePbjItemAndBytes(item, BlockItem.PROTOBUF.toBytes(item));
            }

            writer.closeCompleteBlock();
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
        org.openjdk.jmh.Main.main(new String[] {"BlockStreamingBenchmark", "-v", "EXTRA"});
    }
}
