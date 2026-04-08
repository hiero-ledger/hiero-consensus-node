// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.hedera.containers;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.pbj.grpc.client.helidon.PbjGrpcClient;
import com.hedera.pbj.grpc.client.helidon.PbjGrpcClientConfig;
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

    private final String host;
    private final int port;

    public BlockNodeSubscribeClient(@NonNull final String host, final int port) {
        this.host = requireNonNull(host, "host must not be null");
        this.port = port;
    }

    /**
     * Queries the block node's server status and returns the last available block number.
     *
     * @return the last available block number, or -1 if the status cannot be retrieved
     */
    public long getLastAvailableBlock() {
        try (final var serviceClient = createServiceClient()) {
            final var response = serviceClient.serverStatus(ServerStatusRequest.DEFAULT);
            log.info(
                    "Block node {}:{} server status: lastAvailableBlock={}", host, port, response.lastAvailableBlock());
            return response.lastAvailableBlock();
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

    private org.hiero.block.api.BlockNodeServiceInterface.BlockNodeServiceClient createServiceClient() {
        final var pbjClient = buildPbjClient();
        return new org.hiero.block.api.BlockNodeServiceInterface.BlockNodeServiceClient(
                pbjClient, new DefaultRequestOptions());
    }

    private PbjGrpcClient buildPbjClient() {
        final Tls tls = Tls.builder().enabled(false).build();
        final PbjGrpcClientConfig pbjConfig =
                new PbjGrpcClientConfig(DEFAULT_TIMEOUT, tls, Optional.of(""), "application/grpc");
        final WebClient webClient = WebClient.builder()
                .baseUri("http://" + host + ":" + port)
                .tls(tls)
                .connectTimeout(DEFAULT_TIMEOUT)
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
