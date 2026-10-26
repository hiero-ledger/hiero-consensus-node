// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.hedera.containers;

import static com.hedera.node.app.hapi.utils.CommonPbjConverters.MAX_PBJ_RECORD_SIZE;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.pbj.grpc.client.helidon.PbjGrpcClient;
import com.hedera.pbj.grpc.client.helidon.PbjGrpcClientConfig;
import com.hedera.pbj.runtime.grpc.GrpcCompression;
import com.hedera.pbj.runtime.grpc.Pipeline;
import com.hedera.pbj.runtime.grpc.ServiceInterface;
import com.hedera.pbj.runtime.grpc.ServiceInterface.RequestOptions;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.helidon.common.tls.Tls;
import io.helidon.webclient.api.WebClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.block.api.BlockStreamSubscribeServiceInterface.BlockStreamSubscribeServiceClient;
import org.hiero.block.api.ServerStatusRequest;
import org.hiero.block.api.SubscribeStreamRequest;
import org.hiero.block.api.SubscribeStreamResponse;

/**
 * A gRPC client for retrieving blocks from a real block node container via the
 * {@code BlockStreamSubscribeService.subscribeBlockStream} server-streaming RPC.
 *
 * <p>Also supports querying the block node's server status to determine the available block range.
 */
public class BlockNodeSubscribeClient implements AutoCloseable {
    private static final Logger log = LogManager.getLogger(BlockNodeSubscribeClient.class);
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    // Overall timeout for the long-lived live-follow subscription ({@link #streamBlocks}); must
    // exceed the longest suite runtime. Distinct from DEFAULT_TIMEOUT, which bounds the one-shot
    // bounded reads. VERIFY the PbjGrpcClientConfig timeout is an idle/connect bound, not a hard
    // overall deadline, or a long-running stream will still be cut off.
    private static final Duration STREAM_TIMEOUT = Duration.ofMinutes(40);
    // End-block sentinel requesting an unbounded, live-following subscription: stream from the start
    // block and keep delivering new blocks indefinitely. Confirmed against hiero-block-node
    // BlockStreamSubscriberSession: a request of (start >= 0, end == -1L) is "all blocks from the
    // start block onwards indefinitely". -1L is the uint64_max (0xFFFFFFFFFFFFFFFF) "no end" value
    // the server checks for; any value < uint64_max is a bounded end and MAY be rejected as a
    // not-yet-available future block.
    private static final long LIVE_STREAM_END = -1L;

    private final String host;
    private final int port;

    public BlockNodeSubscribeClient(@NonNull final String host, final int port) {
        this.host = requireNonNull(host, "host must not be null");
        this.port = port;
    }

    /**
     * Queries the block node's server status and returns the next expected block number.
     * The next block the block node wants streamed, or -1 if
     * the block node is empty or its status cannot be retrieved.
     *
     * @return the next expected block number, or -1 if the status cannot be retrieved
     */
    public long getNextExpectedBlock() {
        try (final var serviceClient = createServiceClient()) {
            final var response = serviceClient.serverStatus(ServerStatusRequest.DEFAULT);
            log.info("Block node {}:{} server status: nextExpectedBlock={}", host, port, response.nextExpectedBlock());
            return response.nextExpectedBlock();
        } catch (final Exception e) {
            log.error("Failed to get server status from block node {}:{}", host, port, e);
            return -1;
        }
    }

    /**
     * Probes the block node's server status endpoint to confirm its gRPC port is serving requests.
     *
     * <p>Deliberately ignores the response contents. {@link #getNextExpectedBlock()} returns
     * {@code -1} both when the block node is legitimately empty and when the call failed, so no
     * value-based check can distinguish "reachable" from "unreachable"; only whether the RPC
     * completed carries that information.
     *
     * @return true if the server status RPC completed, false if the block node could not be reached
     */
    public boolean isServerStatusReachable() {
        try (final var serviceClient = createServiceClient()) {
            serviceClient.serverStatus(ServerStatusRequest.DEFAULT);
            return true;
        } catch (final Exception e) {
            log.debug("Block node {}:{} server status not reachable yet: {}", host, port, e.toString());
            return false;
        }
    }

    /**
     * Subscribes to the block stream and retrieves all blocks in the given range.
     * Blocks until the stream completes or the timeout expires.
     *
     * @param startBlock the first block number to retrieve (inclusive)
     * @param endBlock the last block number to retrieve (inclusive)
     * @return list of blocks in ascending order
     * @throws IllegalStateException if the complete range cannot be retrieved
     */
    @NonNull
    public List<Block> subscribeBlocks(final long startBlock, final long endBlock) {
        final var request = SubscribeStreamRequest.newBuilder()
                .startBlockNumber(startBlock)
                .endBlockNumber(endBlock)
                .build();

        final var collector = new BlockCollector(startBlock, endBlock);
        try (final var client = createSubscribeClient()) {
            client.subscribeBlockStream(request, collector);
            final var blocks = collector.await(DEFAULT_TIMEOUT);
            log.info("Subscribe stream completed with {} blocks from {}:{}", blocks.size(), host, port);
            return blocks;
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted subscribing to blocks from " + host + ":" + port, e);
        } catch (final Exception e) {
            throw new IllegalStateException(
                    "Failed to retrieve complete block range " + startBlock + "-" + endBlock + " from " + host + ":"
                            + port,
                    e);
        } finally {
            collector.cancel();
        }
    }

    /** Collects a finite subscription without exposing a partial stream after an RPC failure. */
    static final class BlockCollector implements Pipeline<SubscribeStreamResponse> {
        private final long startBlock;
        private final long endBlock;
        private final List<Block> blocks = new ArrayList<>();
        private final List<BlockItem> currentBlockItems = new ArrayList<>();
        private final CountDownLatch done = new CountDownLatch(1);
        private Flow.Subscription subscription;
        private Throwable failure;
        private SubscribeStreamResponse.Code status;
        private boolean terminated;

        BlockCollector(final long startBlock, final long endBlock) {
            if (startBlock < 0 || endBlock < startBlock) {
                throw new IllegalArgumentException("Expected a finite, nonnegative block range");
            }
            this.startBlock = startBlock;
            this.endBlock = endBlock;
        }

        @Override
        public synchronized void onSubscribe(final Flow.Subscription subscription) {
            if (terminated) {
                subscription.cancel();
            } else {
                this.subscription = subscription;
                subscription.request(Long.MAX_VALUE);
            }
        }

        @Override
        public synchronized void onNext(final SubscribeStreamResponse response) {
            if (terminated) {
                return;
            }
            if (status != null) {
                onError(new IllegalStateException("Received data after terminal subscription status " + status));
            } else if (response.hasBlockItems()) {
                currentBlockItems.addAll(response.blockItemsOrThrow().blockItems());
            } else if (response.hasEndOfBlock()) {
                final long blockNumber = response.endOfBlockOrThrow().blockNumber();
                if (blockNumber > endBlock
                        || blockNumber - startBlock != blocks.size()
                        || currentBlockItems.isEmpty()
                        || !currentBlockItems.getFirst().hasBlockHeader()
                        || currentBlockItems.getFirst().blockHeaderOrThrow().number() != blockNumber) {
                    onError(new IllegalStateException("Unexpected block boundary " + blockNumber + " after "
                            + blocks.size() + " blocks in range " + startBlock + "-" + endBlock));
                    return;
                }
                blocks.add(new Block(List.copyOf(currentBlockItems)));
                currentBlockItems.clear();
            } else if (response.hasStatus()) {
                status = response.status();
                if (status != SubscribeStreamResponse.Code.SUCCESS) {
                    onError(new IllegalStateException("Subscribe stream returned " + status));
                }
            } else {
                onError(new IllegalStateException("Subscribe stream returned an empty response"));
            }
        }

        @Override
        public synchronized void onError(final Throwable throwable) {
            if (!terminated) {
                failure = throwable;
                terminated = true;
                done.countDown();
            }
        }

        @Override
        public synchronized void onComplete() {
            if (!terminated) {
                if (status != SubscribeStreamResponse.Code.SUCCESS
                        || !currentBlockItems.isEmpty()
                        || blocks.isEmpty()
                        || blocks.size() - 1L != endBlock - startBlock) {
                    failure = new IllegalStateException("Incomplete subscribe stream for range " + startBlock + "-"
                            + endBlock + ": " + blocks.size() + " complete blocks, " + currentBlockItems.size()
                            + " trailing items, status " + status);
                }
                terminated = true;
                done.countDown();
            }
        }

        List<Block> await(final Duration timeout) throws InterruptedException {
            if (!done.await(timeout.toNanos(), TimeUnit.NANOSECONDS)) {
                onError(new IllegalStateException("Timed out retrieving block range " + startBlock + "-" + endBlock));
            }
            synchronized (this) {
                if (failure != null) {
                    throw new IllegalStateException("Block subscription failed", failure);
                }
                return List.copyOf(blocks);
            }
        }

        synchronized void cancel() {
            terminated = true;
            if (subscription != null) {
                subscription.cancel();
            }
        }
    }

    /**
     * Opens a <em>single</em> long-lived subscription starting at {@code startBlock} and follows the
     * live stream indefinitely, pushing each completed {@link Block} to {@code onBlock} as it arrives.
     * Returns a handle whose {@link AutoCloseable#close()} cancels the subscription and releases the
     * block node's subscriber handler.
     *
     * <p>This exists to avoid the subscriber-handler churn/leak caused by opening a fresh
     * {@link #subscribeBlocks} per poll: one subscription serves the entire poller lifetime, so the
     * block node holds exactly one handler for this consumer rather than hundreds accumulating until
     * its idle-connection timeout.
     *
     * <p>The subscription is <em>not</em> self-renewing: if it drops (error, server completion, or the
     * block node reaping an idle connection) {@code onTerminated} is invoked so the caller can decide
     * whether to re-subscribe (resuming from the next unseen block). Callers that need continuous
     * delivery must supervise reconnection; see {@code BlockNodeBlockSource}.
     *
     * @param startBlock the first block number to stream (inclusive)
     * @param onBlock invoked (on the gRPC callback thread, serially) for each completed block
     * @param onTerminated invoked once when the stream ends (error or completion), for reconnection
     * @return a handle that cancels the subscription when closed
     */
    @NonNull
    public AutoCloseable streamBlocks(
            final long startBlock, @NonNull final Consumer<Block> onBlock, @NonNull final Runnable onTerminated) {
        requireNonNull(onBlock);
        requireNonNull(onTerminated);
        final var request = SubscribeStreamRequest.newBuilder()
                .startBlockNumber(startBlock)
                .endBlockNumber(LIVE_STREAM_END)
                .build();
        final var subscriptionRef = new AtomicReference<Flow.Subscription>();
        // Only accessed from the callback thread (Reactive Streams guarantees serial onNext)
        final List<BlockItem> currentBlockItems = new ArrayList<>();
        final var client = createStreamingSubscribeClient();
        client.subscribeBlockStream(request, new Pipeline<>() {
            @Override
            public void onSubscribe(final Flow.Subscription subscription) {
                subscriptionRef.set(subscription);
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(final SubscribeStreamResponse response) {
                if (response.hasBlockItems()) {
                    currentBlockItems.addAll(response.blockItems().blockItems());
                } else if (response.hasEndOfBlock()) {
                    if (!currentBlockItems.isEmpty()) {
                        onBlock.accept(new Block(List.copyOf(currentBlockItems)));
                        currentBlockItems.clear();
                    }
                }
            }

            @Override
            public void onError(final Throwable throwable) {
                log.warn("Live block subscription from {}:{} errored", host, port, throwable);
                onTerminated.run();
            }

            @Override
            public void onComplete() {
                log.info("Live block subscription from {}:{} completed", host, port);
                onTerminated.run();
            }
        });
        return () -> {
            cancelSubscription(subscriptionRef);
            client.close();
        };
    }

    @Override
    public void close() {
        // No persistent resources to close; clients are created per-call
    }

    private static void cancelSubscription(@NonNull final AtomicReference<Flow.Subscription> ref) {
        final var subscription = ref.get();
        if (subscription != null) {
            subscription.cancel();
        }
    }

    private BlockStreamSubscribeServiceClient createSubscribeClient() {
        final var pbjClient = buildPbjClient();
        return new BlockStreamSubscribeServiceClient(pbjClient, new DefaultRequestOptions());
    }

    private BlockStreamSubscribeServiceClient createStreamingSubscribeClient() {
        return new BlockStreamSubscribeServiceClient(buildPbjClient(STREAM_TIMEOUT), new DefaultRequestOptions());
    }

    private org.hiero.block.api.BlockNodeServiceInterface.BlockNodeServiceClient createServiceClient() {
        final var pbjClient = buildPbjClient();
        return new org.hiero.block.api.BlockNodeServiceInterface.BlockNodeServiceClient(
                pbjClient, new DefaultRequestOptions());
    }

    private PbjGrpcClient buildPbjClient() {
        return buildPbjClient(DEFAULT_TIMEOUT);
    }

    private PbjGrpcClient buildPbjClient(final Duration timeout) {
        final Tls tls = Tls.builder().enabled(false).build();
        final PbjGrpcClientConfig pbjConfig = clientConfig(tls, timeout);
        final WebClient webClient = WebClient.builder()
                .baseUri("http://" + host + ":" + port)
                .tls(tls)
                .connectTimeout(timeout)
                .build();
        return new PbjGrpcClient(webClient, pbjConfig);
    }

    static PbjGrpcClientConfig clientConfig(final Tls tls) {
        return clientConfig(tls, DEFAULT_TIMEOUT);
    }

    static PbjGrpcClientConfig clientConfig(final Tls tls, final Duration timeout) {
        // A response batches block items, including node-generated proof transactions up to
        // MAX_PBJ_RECORD_SIZE. Allow room for the surrounding items and protobuf envelopes.
        final int maxResponseSize = 2 * MAX_PBJ_RECORD_SIZE;
        return new PbjGrpcClientConfig(
                timeout,
                tls,
                Optional.of(""),
                "application/grpc",
                GrpcCompression.IDENTITY,
                GrpcCompression.getDecompressorNames(),
                maxResponseSize,
                5 * maxResponseSize);
    }

    private static class DefaultRequestOptions implements ServiceInterface.RequestOptions {
        @Override
        public @NonNull Optional<String> authority() {
            return Optional.empty();
        }

        @Override
        public @NonNull String contentType() {
            return RequestOptions.APPLICATION_GRPC;
        }
    }
}
