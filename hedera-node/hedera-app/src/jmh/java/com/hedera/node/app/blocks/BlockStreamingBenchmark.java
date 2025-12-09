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
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.hiero.consensus.model.node.NodeId;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

/**
 * JMH benchmark for production block streaming components.
 * Blocks are pre-generated to isolate and measure only the streaming performance.
 */
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 10)
@Measurement(iterations = 15)
@Fork(3) // Run 3 separate JVM processes for better statistical reliability
public class BlockStreamingBenchmark {

    @Param({"100"})
    private int numBlocks;

    @Param({"1000000"}) // 1 MB
    private long blockSizeBytes;

    @Param({"2000"}) // 2 KB items
    private int itemSizeBytes;

    private FakeGrpcServer server;
    private BlockBufferService bufferService;
    private BlockNodeConnectionManager connectionManager;
    private GrpcBlockItemWriter writer;
    private ScheduledExecutorService scheduler;
    private ExecutorService pipelineExecutor;
    private ScheduledExecutorService metricsScheduler;

    private List<Block> blocks; // Pre-generated, excluded from measurement

    private long benchmarkStartTime; // For throughput calculation
    private long benchmarkEndTime;

    @Setup(Level.Trial)
    public void setupTrial() {
        server = FakeGrpcServer.builder()
                .port(0)
                .latency(FakeGrpcServer.LatencyConfig.none())
                .build();
        server.start();

        final Configuration config = ConfigurationBuilder.create()
                .withConfigDataType(BlockStreamConfig.class)
                .withConfigDataType(BlockBufferConfig.class)
                .withConfigDataType(BlockNodeConnectionConfig.class)
                .withConfigDataType(MetricsConfig.class)
                .withValue("blockStream.writerMode", "GRPC")
                .withValue("blockNode.highLatencyEventsBeforeSwitching", "3")
                .withValue("blockNode.highLatencyThresholdMs", "500")
                .build();

        final ConfigProvider configProvider = () -> new VersionedConfigImpl(config, 1L);

        metricsScheduler = Executors.newScheduledThreadPool(1);
        final Metrics metrics = new DefaultPlatformMetrics(
                NodeId.of(0L),
                new MetricKeyRegistry(),
                metricsScheduler,
                new PlatformMetricsFactoryImpl(config.getConfigData(MetricsConfig.class)),
                config.getConfigData(MetricsConfig.class));

        final BlockStreamMetrics blockStreamMetrics = new BlockStreamMetrics(metrics);

        bufferService = new BlockBufferService(configProvider, blockStreamMetrics);
        bufferService.start();

        connectionManager = new BlockNodeConnectionManager(configProvider, bufferService, blockStreamMetrics);
        bufferService.setBlockNodeConnectionManager(connectionManager);
        connectionManager.start();

        scheduler = Executors.newScheduledThreadPool(2);
        pipelineExecutor = Executors.newVirtualThreadPerTaskExecutor();

        final BlockNodeConfiguration nodeConfig = BlockNodeConfiguration.newBuilder()
                .address("localhost")
                .port(server.getPort())
                .priority(0)
                .messageSizeSoftLimitBytes(2_097_152) // 2 MB
                .messageSizeHardLimitBytes(4_194_304) // 4 MB
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

        writer = new GrpcBlockItemWriter(bufferService, connectionManager);

        System.out.println("Pre-generating " + numBlocks + " blocks for benchmark...");
        blocks = BlockGeneratorUtil.generateBlocks(0, numBlocks, blockSizeBytes, itemSizeBytes);
        System.out.println("Blocks pre-generated. Ready for benchmark.");
    }

    @TearDown(Level.Trial)
    public void teardownTrial() throws Exception {
        if (bufferService != null) {
            bufferService.shutdown();
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler.awaitTermination(5, TimeUnit.SECONDS);
        }
        if (pipelineExecutor != null) {
            pipelineExecutor.shutdownNow();
            pipelineExecutor.awaitTermination(5, TimeUnit.SECONDS);
        }
        if (metricsScheduler != null) {
            metricsScheduler.shutdownNow();
            metricsScheduler.awaitTermination(5, TimeUnit.SECONDS);
        }
        if (server != null) {
            server.stop();
        }
    }

    @Setup(Level.Iteration)
    public void setupIteration() {
        server.resetMetrics();
        benchmarkStartTime = 0;
        benchmarkEndTime = 0;
    }

    @TearDown(Level.Iteration)
    public void teardownIteration() {
        if (benchmarkStartTime > 0 && benchmarkEndTime > 0) {
            double elapsedSeconds = (benchmarkEndTime - benchmarkStartTime) / 1_000_000_000.0;
            long totalBytes = numBlocks * blockSizeBytes;
            double megabytesPerSec = (totalBytes / (1024.0 * 1024.0)) / elapsedSeconds;
            double gigabitsPerSec = (totalBytes * 8.0) / (1_000_000_000.0 * elapsedSeconds);

            System.out.printf(
                    ">>> Throughput: %.2f MB/s (%.2f Gbps) for %d blocks%n",
                    megabytesPerSec, gigabitsPerSec, numBlocks);
        }

        System.gc(); // Prevent GC during next measurement
    }

    /**
     * Measures streaming pipeline throughput in isolation.
     *
     * MEASURES:
     * - BlockBufferService flow control
     * - GrpcBlockItemWriter serialization and streaming
     * - BlockNodeConnection acknowledgement pipeline
     * - Network I/O (localhost gRPC)
     *
     * DOES NOT MEASURE:
     * - Block generation from transactions
     * - Transaction processing or state updates
     * - Consensus overhead
     * - Disk I/O (local block file writes)
     * - Real network latency (uses localhost)
     * - Concurrent system load
     *
     * Results show streaming capacity (~18K blocks/sec), not end-to-end production TPS.
     */
    @Benchmark
    public void streamBlocks(Blackhole bh) throws Exception {
        benchmarkStartTime = System.nanoTime();

        for (int i = 0; i < blocks.size(); i++) {
            bufferService.ensureNewBlocksPermitted();
            writer.openBlock(i);

            for (BlockItem item : blocks.get(i).items()) {
                writer.writePbjItemAndBytes(item, BlockItem.PROTOBUF.toBytes(item));
            }

            writer.closeCompleteBlock();
        }

        while (bufferService.getHighestAckedBlockNumber() < numBlocks - 1) {
            Thread.sleep(10);
        }

        benchmarkEndTime = System.nanoTime();

        bh.consume(bufferService.getHighestAckedBlockNumber()); // Prevent dead code elimination
    }

    public static void main(String[] args) throws Exception {
        org.openjdk.jmh.Main.main(new String[] {"BlockStreamingBenchmark", "-v", "EXTRA"});
    }
}
