// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.utils;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.pbj.grpc.helidon.PbjRouting;
import com.hedera.pbj.grpc.helidon.config.PbjConfig;
import com.hedera.pbj.runtime.grpc.Pipeline;
import com.hedera.pbj.runtime.grpc.ServiceInterface;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.helidon.webserver.ConnectionConfig;
import io.helidon.webserver.WebServer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.block.api.BlockStreamPublishServiceInterface;
import org.hiero.block.api.PublishStreamRequest;
import org.hiero.block.api.PublishStreamResponse;
import org.hiero.block.api.PublishStreamResponse.BlockAcknowledgement;

/**
 * Fake gRPC server for benchmarking block streaming throughput.
 * Supports latency simulation and collects detailed metrics.
 * Duplicate EndOfBlock messages are detected and excluded from metrics.
 */
public class FakeGrpcServer {
    private static final Logger log = LogManager.getLogger(FakeGrpcServer.class);

    // Default values matching SimulatedBlockNodeServer
    private static final int DEFAULT_MAX_MESSAGE_SIZE_BYTES = 4_194_304; // 4 MB
    private static final int DEFAULT_BUFFER_SIZE = 32768; // 32 KB

    private final WebServer webServer;
    private final int port;
    private final BlockStreamServiceImpl serviceImpl;
    private final LatencyConfig latencyConfig;
    private final Metrics metrics = new Metrics();

    public static Builder builder() {
        return new Builder();
    }

    private FakeGrpcServer(Builder builder) {
        this.port = builder.port;
        this.latencyConfig = builder.latencyConfig != null ? builder.latencyConfig : LatencyConfig.none();
        this.serviceImpl = new BlockStreamServiceImpl();

        final PbjConfig pbjConfig = PbjConfig.builder()
                .name("pbj")
                .maxMessageSizeBytes(builder.maxMessageSizeBytes)
                .build();
        @SuppressWarnings("deprecation")
        final ConnectionConfig connectionConfig = ConnectionConfig.builder()
                .sendBufferSize(builder.sendBufferSize)
                .receiveBufferSize(builder.receiveBufferSize)
                .build();

        this.webServer = WebServer.builder()
                .port(port)
                .addRouting(PbjRouting.builder().service(serviceImpl))
                .addProtocol(pbjConfig)
                .connectionConfig(connectionConfig)
                .build();
    }

    public void start() {
        webServer.start();
        log.info("FakeGrpcServer started on port {}", getPort());
    }

    public void stop() {
        if (webServer != null) {
            try {
                webServer.stop();
                log.info("FakeGrpcServer stopped on port {}", port);
            } catch (final Exception e) {
                log.error("Error stopping FakeGrpcServer on port {}", port, e);
            }
        }
    }

    public int getPort() {
        return webServer != null ? webServer.port() : port;
    }

    public Metrics getMetrics() {
        return metrics.snapshot();
    }

    public void resetMetrics() {
        metrics.reset();
    }

    /** Builder for configuring a FakeGrpcServer. */
    public static class Builder {
        private int port = 0; // Ephemeral port by default
        private LatencyConfig latencyConfig;
        private int maxMessageSizeBytes = DEFAULT_MAX_MESSAGE_SIZE_BYTES;
        private int sendBufferSize = DEFAULT_BUFFER_SIZE;
        private int receiveBufferSize = DEFAULT_BUFFER_SIZE;

        private Builder() {}

        /** Sets the port to listen on (0 for ephemeral port). */
        public Builder port(int port) {
            this.port = port;
            return this;
        }

        /** Sets the latency configuration. */
        public Builder latency(LatencyConfig latencyConfig) {
            this.latencyConfig = latencyConfig;
            return this;
        }

        /** Sets the maximum message size in bytes. */
        public Builder maxMessageSize(int maxMessageSizeBytes) {
            this.maxMessageSizeBytes = maxMessageSizeBytes;
            return this;
        }

        /** Sets the send buffer size in bytes. */
        public Builder sendBufferSize(int sendBufferSize) {
            this.sendBufferSize = sendBufferSize;
            return this;
        }

        /** Sets the receive buffer size in bytes. */
        public Builder receiveBufferSize(int receiveBufferSize) {
            this.receiveBufferSize = receiveBufferSize;
            return this;
        }

        /** Builds the FakeGrpcServer with the configured settings. */
        public FakeGrpcServer build() {
            return new FakeGrpcServer(this);
        }
    }

    /** Configuration for network latency simulation. */
    public static class LatencyConfig {
        private final LatencyType type;
        private final Duration fixedDelay;
        private final Duration mean;
        private final Duration jitter;
        private final Map<RequestType, Duration> perRequestDelays;

        private LatencyConfig(
                LatencyType type,
                Duration fixedDelay,
                Duration mean,
                Duration jitter,
                Map<RequestType, Duration> perRequestDelays) {
            this.type = type;
            this.fixedDelay = fixedDelay;
            this.mean = mean;
            this.jitter = jitter;
            this.perRequestDelays = perRequestDelays != null
                    ? Collections.unmodifiableMap(new EnumMap<>(perRequestDelays))
                    : Collections.emptyMap();
        }

        /** Creates a latency config with no delay. */
        public static LatencyConfig none() {
            return new LatencyConfig(LatencyType.NONE, null, null, null, null);
        }

        /** Creates a latency config with a fixed delay for all responses. */
        public static LatencyConfig fixed(Duration delay) {
            requireNonNull(delay, "delay cannot be null");
            return new LatencyConfig(LatencyType.FIXED, delay, null, null, null);
        }

        /** Creates Gaussian latency config (mean ± jitter). Truncated at ±3 sigma. */
        public static LatencyConfig gaussian(Duration mean, Duration jitter) {
            requireNonNull(mean, "mean cannot be null");
            requireNonNull(jitter, "jitter cannot be null");
            return new LatencyConfig(LatencyType.GAUSSIAN, null, mean, jitter, null);
        }

        /** Creates a latency config with different delays per request type. */
        public static LatencyConfig perRequest(Map<RequestType, Duration> perRequestDelays) {
            requireNonNull(perRequestDelays, "perRequestDelays cannot be null");
            return new LatencyConfig(LatencyType.PER_REQUEST, null, null, null, perRequestDelays);
        }

        /** Calculates the delay to apply for the given request type. */
        Duration calculateDelay(RequestType requestType) {
            return switch (type) {
                case NONE -> Duration.ZERO;
                case FIXED -> fixedDelay;
                case GAUSSIAN -> {
                    final double meanNanos = mean.toNanos();
                    final double jitterNanos = jitter.toNanos();
                    final double sigma = jitterNanos;
                    // Clamp to 3 sigma (use ThreadLocalRandom for thread-safety)
                    final double jitterValue =
                            Math.clamp(ThreadLocalRandom.current().nextGaussian() * sigma, -3 * sigma, 3 * sigma);
                    yield Duration.ofNanos((long) (meanNanos + jitterValue));
                }
                case PER_REQUEST -> perRequestDelays.getOrDefault(requestType, Duration.ZERO);
            };
        }

        private enum LatencyType {
            NONE,
            FIXED,
            GAUSSIAN,
            PER_REQUEST
        }
    }

    /** Request types for per-request latency configuration. */
    public enum RequestType {
        /** Block header request */
        HEADER,
        /** Block items request */
        ITEMS,
        /** Block proof request */
        PROOF,
        /** End of block request */
        END_OF_BLOCK,
        /** Block acknowledgement response */
        ACKNOWLEDGEMENT
    }

    /** Tracks block start time and accumulated size. */
    private static class BlockMetadata {
        final long startTimeNanos;
        long totalBytes; // Only accessed by single Pipeline thread per block

        BlockMetadata(long startTimeNanos) {
            this.startTimeNanos = startTimeNanos;
            this.totalBytes = 0;
        }

        void addBytes(long bytes) {
            this.totalBytes += bytes;
        }
    }

    /**
     * Metrics collected by the server.
     * Duplicates are ignored - only first occurrence of each block is counted.
     */
    public static class Metrics {
        // Max latency samples to prevent unbounded memory growth
        private static final int MAX_LATENCY_SAMPLES = 10_000;

        private final AtomicLong totalBlocksProcessed; // Total EndOfBlock messages received (including duplicates)
        private final Set<Long> uniqueBlockNumbers; // Unique block numbers received
        private final AtomicLong bytesReceived;
        private final AtomicLong totalLatencyNanos;
        private final List<Long> latencySamples; // Bounded list for percentile calculations
        private final Instant startTime;

        Metrics() {
            this.totalBlocksProcessed = new AtomicLong(0);
            this.uniqueBlockNumbers = ConcurrentHashMap.newKeySet();
            this.bytesReceived = new AtomicLong(0);
            this.totalLatencyNanos = new AtomicLong(0);
            this.latencySamples = new CopyOnWriteArrayList<>();
            this.startTime = Instant.now();
        }

        private Metrics(Metrics source) {
            this.totalBlocksProcessed = new AtomicLong(source.totalBlocksProcessed.get());
            this.uniqueBlockNumbers = ConcurrentHashMap.newKeySet();
            this.uniqueBlockNumbers.addAll(source.uniqueBlockNumbers);
            this.bytesReceived = new AtomicLong(source.bytesReceived.get());
            this.totalLatencyNanos = new AtomicLong(source.totalLatencyNanos.get());
            this.latencySamples = new ArrayList<>(source.latencySamples);
            this.startTime = source.startTime; // Preserve original start time for accurate throughput
        }

        /** Records a block. Returns true if unique, false if duplicate. */
        boolean recordBlock(long blockNumber, long blockSizeBytes, long latencyNanos) {
            totalBlocksProcessed.incrementAndGet();

            // Only record metrics for unique blocks (first occurrence)
            if (!uniqueBlockNumbers.add(blockNumber)) {
                log.debug("Duplicate EndOfBlock for block {} ignored in metrics", blockNumber);
                return false; // Duplicate
            }

            bytesReceived.addAndGet(blockSizeBytes);
            totalLatencyNanos.addAndGet(latencyNanos);

            // Bounded latency sampling (reservoir sampling)
            if (latencySamples.size() < MAX_LATENCY_SAMPLES) {
                latencySamples.add(latencyNanos);
            } else {
                latencySamples.set(ThreadLocalRandom.current().nextInt(MAX_LATENCY_SAMPLES), latencyNanos);
            }

            return true; // New unique block
        }

        /** Gets the set of unique block numbers received. */
        public Set<Long> getReceivedBlockNumbers() {
            return Set.copyOf(uniqueBlockNumbers);
        }

        synchronized void reset() {
            totalBlocksProcessed.set(0);
            uniqueBlockNumbers.clear();
            bytesReceived.set(0);
            totalLatencyNanos.set(0);
            latencySamples.clear();
        }

        synchronized Metrics snapshot() {
            return new Metrics(this);
        }

        /** Gets total EndOfBlock messages received (including duplicates). */
        public long getTotalBlocksProcessed() {
            return totalBlocksProcessed.get();
        }

        /** Gets unique blocks received (excludes duplicates). */
        public long getUniqueBlocksReceived() {
            return uniqueBlockNumbers.size();
        }

        /** Gets total bytes received. */
        public long getBytesReceived() {
            return bytesReceived.get();
        }

        /** Gets average latency per unique block in nanoseconds. */
        public double getAverageLatencyNanos() {
            final long uniqueBlocks = uniqueBlockNumbers.size();
            return uniqueBlocks > 0 ? (double) totalLatencyNanos.get() / uniqueBlocks : 0.0;
        }

        /** Gets throughput in unique blocks per second. */
        public double getThroughputBlocksPerSecond() {
            final Duration elapsed = Duration.between(startTime, Instant.now());
            final double seconds = elapsed.toNanos() / 1_000_000_000.0;
            if (seconds <= 0) {
                return 0.0;
            }
            return uniqueBlockNumbers.size() / seconds;
        }

        /** Gets throughput in bytes per second. */
        public double getThroughputBytesPerSecond() {
            final Duration elapsed = Duration.between(startTime, Instant.now());
            final double seconds = elapsed.toNanos() / 1_000_000_000.0;
            if (seconds <= 0) {
                return 0.0;
            }
            return bytesReceived.get() / seconds;
        }

        /** Calculates latency percentile (0.0 to 1.0). */
        public long getLatencyPercentileNanos(double percentile) {
            if (latencySamples.isEmpty()) {
                return 0;
            }
            final List<Long> sorted = new ArrayList<>(latencySamples);
            sorted.sort(Long::compareTo);
            final int index = (int) Math.ceil(percentile * sorted.size()) - 1;
            return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
        }

        /** Gets P50 latency in nanoseconds. */
        public long getP50LatencyNanos() {
            return getLatencyPercentileNanos(0.50);
        }

        /** Gets P95 latency in nanoseconds. */
        public long getP95LatencyNanos() {
            return getLatencyPercentileNanos(0.95);
        }

        /** Gets P99 latency in nanoseconds. */
        public long getP99LatencyNanos() {
            return getLatencyPercentileNanos(0.99);
        }
    }

    /** Implementation of BlockStreamPublishServiceInterface for benchmarking. */
    private class BlockStreamServiceImpl implements BlockStreamPublishServiceInterface {
        private final List<Pipeline<? super PublishStreamResponse>> activeStreams = new CopyOnWriteArrayList<>();

        @Override
        public @NonNull Pipeline<? super PublishStreamRequest> publishBlockStream(
                @NonNull Pipeline<? super PublishStreamResponse> replies) {
            requireNonNull(replies, "replies cannot be null");

            activeStreams.add(replies);
            log.debug("New block stream connection established. Total streams: {}", activeStreams.size());

            return new Pipeline<>() {
                private volatile Long currentBlockNumber = null; // volatile for thread-safe visibility
                private final Map<Long, BlockMetadata> blockMetadata = new ConcurrentHashMap<>();

                @Override
                public void onNext(final PublishStreamRequest request) {
                    try {
                        if (request.hasEndStream()) {
                            log.debug("Received end of stream");
                            cleanup();
                            removeStream(replies);
                        } else if (request.hasBlockItems()) {
                            processBlockItems(request);
                        } else if (request.hasEndOfBlock()) {
                            processEndOfBlock(request);
                        }
                    } catch (final Exception e) {
                        log.error("Error processing request", e);
                    }
                }

                private void processBlockItems(final PublishStreamRequest request) {
                    for (final BlockItem item : request.blockItems().blockItems()) {
                        if (item.hasBlockHeader()) {
                            final long blockNumber = item.blockHeader().number();
                            currentBlockNumber = blockNumber;
                            blockMetadata.computeIfAbsent(blockNumber, k -> new BlockMetadata(System.nanoTime()));
                            log.debug("Received BlockHeader for block {}", blockNumber);
                        }

                        // Count bytes: Use actual serialization size
                        if (currentBlockNumber != null) {
                            final BlockMetadata metadata = blockMetadata.get(currentBlockNumber);
                            if (metadata != null) {
                                // Use actual serialization size for accurate metrics
                                long itemSize = BlockItem.PROTOBUF.toBytes(item).length();
                                metadata.addBytes(itemSize);
                            }
                        }
                    }
                }

                private void processEndOfBlock(final PublishStreamRequest request) {
                    final long blockNumber = request.endOfBlockOrThrow().blockNumber();
                    final long blockEndTimeNanos = System.nanoTime();

                    final BlockMetadata metadata = blockMetadata.remove(blockNumber);
                    if (currentBlockNumber != null && currentBlockNumber.equals(blockNumber)) {
                        currentBlockNumber = null;
                    }

                    final long latencyNanos = metadata != null ? blockEndTimeNanos - metadata.startTimeNanos : 0;
                    final long blockSize = metadata != null ? metadata.totalBytes : 0;

                    final boolean wasUnique = metrics.recordBlock(blockNumber, blockSize, latencyNanos);
                    log.debug(
                            "Received {} EndOfBlock for block {}. Size: {} bytes, Latency: {} ms",
                            wasUnique ? "" : "duplicate",
                            blockNumber,
                            blockSize,
                            latencyNanos / 1_000_000.0);

                    sendAcknowledgement(replies, blockNumber);
                }

                @Override
                public void onError(final Throwable t) {
                    log.error("Error in block stream", t);
                    cleanup();
                    removeStream(replies);
                }

                @Override
                public void onComplete() {
                    log.debug("Block stream completed");
                    cleanup();
                    removeStream(replies);
                }

                @Override
                public void onSubscribe(Flow.Subscription subscription) {
                    subscription.request(Long.MAX_VALUE);
                }

                @Override
                public void clientEndStreamReceived() {
                    Pipeline.super.clientEndStreamReceived();
                }

                /**
                 * Sends block acknowledgement with configured latency.
                 */
                private void sendAcknowledgement(Pipeline<? super PublishStreamResponse> pipeline, long blockNumber) {
                    try {
                        final Duration delay = latencyConfig.calculateDelay(RequestType.ACKNOWLEDGEMENT);
                        if (!delay.isZero()) {
                            Thread.sleep(delay.toMillis(), delay.getNano() % 1_000_000);
                        }
                        final BlockAcknowledgement ack = BlockAcknowledgement.newBuilder()
                                .blockNumber(blockNumber)
                                .build();
                        final PublishStreamResponse response = PublishStreamResponse.newBuilder()
                                .acknowledgement(ack)
                                .build();
                        pipeline.onNext(response);
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                        log.warn("Interrupted while applying latency delay", e);
                    } catch (final Exception e) {
                        log.error("Failed to send acknowledgement for block {}", blockNumber, e);
                    }
                }

                /** Cleans up incomplete block metadata when stream ends. */
                private void cleanup() {
                    if (!blockMetadata.isEmpty()) {
                        log.debug("Cleaning up {} incomplete block(s) from stream", blockMetadata.size());
                        blockMetadata.clear();
                    }
                }

                private void removeStream(Pipeline<? super PublishStreamResponse> pipeline) {
                    activeStreams.remove(pipeline);
                }
            };
        }

        @Override
        public @NonNull String serviceName() {
            return BlockStreamPublishServiceInterface.super.serviceName();
        }

        @Override
        public @NonNull String fullName() {
            return BlockStreamPublishServiceInterface.super.fullName();
        }

        @Override
        public @NonNull List<ServiceInterface.Method> methods() {
            return BlockStreamPublishServiceInterface.super.methods();
        }

        @Override
        public @NonNull Pipeline<? super Bytes> open(
                @NonNull ServiceInterface.Method method,
                @NonNull ServiceInterface.RequestOptions options,
                @NonNull Pipeline<? super Bytes> replies) {
            return BlockStreamPublishServiceInterface.super.open(method, options, replies);
        }
    }
}
