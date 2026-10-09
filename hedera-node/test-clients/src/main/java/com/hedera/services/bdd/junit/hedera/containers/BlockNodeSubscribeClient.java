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

    @Override
    public void close() {
        // No persistent resources to close; clients are created per-call
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
        final PbjGrpcClientConfig pbjConfig = clientConfig(tls);
        final WebClient webClient = WebClient.builder()
                .baseUri("http://" + host + ":" + port)
                .tls(tls)
                .connectTimeout(DEFAULT_TIMEOUT)
                .build();
        return new PbjGrpcClient(webClient, pbjConfig);
    }

    static PbjGrpcClientConfig clientConfig(final Tls tls) {
        // A response batches block items, including node-generated proof transactions up to
        // MAX_PBJ_RECORD_SIZE. Allow room for the surrounding items and protobuf envelopes.
        final int maxResponseSize = 2 * MAX_PBJ_RECORD_SIZE;
        return new PbjGrpcClientConfig(
                DEFAULT_TIMEOUT,
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
