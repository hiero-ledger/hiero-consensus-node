// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.hedera.containers;

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
import java.util.concurrent.CopyOnWriteArrayList;
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
    private static final int MAX_MESSAGE_SIZE_BYTES = 4 * 1024 * 1024;
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
     * Subscribes to the block stream and retrieves all blocks in the given range.
     * Blocks until the stream completes or the timeout expires.
     *
     * @param startBlock the first block number to retrieve (inclusive)
     * @param endBlock the last block number to retrieve (inclusive)
     * @return list of blocks in ascending order
     */
    @NonNull
    public List<Block> subscribeBlocks(final long startBlock, final long endBlock) {
        final var request = SubscribeStreamRequest.newBuilder()
                .startBlockNumber(startBlock)
                .endBlockNumber(endBlock)
                .build();

        // CopyOnWriteArrayList: safe for concurrent add (callback thread) + read (calling thread)
        // in the window between subscription.cancel() and the final in-flight onNext completing
        final List<Block> blocks = new CopyOnWriteArrayList<>();
        // Only accessed from the callback thread (Reactive Streams guarantees serial onNext)
        final List<BlockItem> currentBlockItems = new ArrayList<>();
        final var latch = new CountDownLatch(1);
        final var subscriptionRef = new AtomicReference<Flow.Subscription>();

        try (final var client = createSubscribeClient()) {
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
                        // Block boundary -- finalize current block
                        if (!currentBlockItems.isEmpty()) {
                            blocks.add(new Block(List.copyOf(currentBlockItems)));
                            currentBlockItems.clear();
                        }
                    } else if (response.hasStatus()) {
                        log.info("Subscribe stream status {} after {} blocks", response.status(), blocks.size());
                    }
                }

                @Override
                public void onError(final Throwable throwable) {
                    log.error("Error subscribing to blocks from {}:{}", host, port, throwable);
                    latch.countDown();
                }

                @Override
                public void onComplete() {
                    // Finalize any remaining items
                    if (!currentBlockItems.isEmpty()) {
                        blocks.add(new Block(List.copyOf(currentBlockItems)));
                        currentBlockItems.clear();
                    }
                    log.info("Subscribe stream completed with {} blocks from {}:{}", blocks.size(), host, port);
                    latch.countDown();
                }
            });

            // Wait for the async stream to complete
            if (!latch.await(DEFAULT_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                cancelSubscription(subscriptionRef);
                log.warn(
                        "Timed out waiting for subscribe stream from {}:{} after {}s (got {} blocks so far)",
                        host,
                        port,
                        DEFAULT_TIMEOUT.toSeconds(),
                        blocks.size());
            }
        } catch (final InterruptedException e) {
            cancelSubscription(subscriptionRef);
            Thread.currentThread().interrupt();
            log.error("Interrupted while subscribing to blocks from {}:{}", host, port, e);
        } catch (final Exception e) {
            cancelSubscription(subscriptionRef);
            log.error("Failed to subscribe to blocks from {}:{}", host, port, e);
        }

        return List.copyOf(blocks);
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
        final PbjGrpcClientConfig pbjConfig = new PbjGrpcClientConfig(
                timeout,
                tls,
                Optional.of(""),
                "application/grpc",
                GrpcCompression.IDENTITY,
                GrpcCompression.getDecompressorNames(),
                MAX_MESSAGE_SIZE_BYTES,
                MAX_MESSAGE_SIZE_BYTES * 5);
        final WebClient webClient = WebClient.builder()
                .baseUri("http://" + host + ":" + port)
                .tls(tls)
                .connectTimeout(timeout)
                .build();
        return new PbjGrpcClient(webClient, pbjConfig);
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
