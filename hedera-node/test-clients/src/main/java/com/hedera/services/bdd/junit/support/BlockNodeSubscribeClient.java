// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.node.app.blocks.impl.streaming.BlockNodeClientFactory;
import com.hedera.node.app.blocks.impl.streaming.config.BlockNodeConfiguration;
import com.hedera.node.app.blocks.impl.streaming.config.BlockNodeHelidonGrpcConfiguration;
import com.hedera.node.app.blocks.impl.streaming.config.BlockNodeHelidonHttpConfiguration;
import com.hedera.pbj.runtime.grpc.Pipeline;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.hiero.block.api.BlockItemSet;
import org.hiero.block.api.BlockNodeServiceInterface.BlockNodeServiceClient;
import org.hiero.block.api.BlockStreamSubscribeServiceInterface.BlockStreamSubscribeServiceClient;
import org.hiero.block.api.ServerStatusRequest;
import org.hiero.block.api.SubscribeStreamRequest;
import org.hiero.block.api.SubscribeStreamResponse;

/**
 * Retrieves block streams from a block node via the subscribe API.
 */
public class BlockNodeSubscribeClient {
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration DEFAULT_WAIT = Duration.ofSeconds(30);

    /**
     * Fetches blocks from block #0 up to {@code endBlockNumber}.
     *
     * @param host block node host
     * @param port block node port
     * @param endBlockNumber terminal block number to request
     * @return blocks in ascending order
     */
    public @NonNull List<Block> fetchBlocks(
            @NonNull final String host, final int port, final long endBlockNumber, @NonNull final Duration timeout) {
        requireNonNull(host);
        requireNonNull(timeout);
        final var config = blockNodeConfig(host, port);
        final var factory = new BlockNodeClientFactory();
        try (final BlockNodeServiceClient serviceClient = factory.createServiceClient(config, timeout);
                final BlockStreamSubscribeServiceClient subscribeClient = factory.createSubscribeClient(config, timeout)) {
            final var status = serviceClient.serverStatus(new ServerStatusRequest());
            if (status.lastAvailableBlock() < endBlockNumber) {
                throw new IllegalStateException(
                        "Requested end block " + endBlockNumber + " is above block node availability "
                                + status.lastAvailableBlock());
            }
            final var request = SubscribeStreamRequest.newBuilder()
                    .startBlockNumber(0)
                    .endBlockNumber(endBlockNumber)
                    .build();
            return invokeSubscribe(subscribeClient, request);
        }
    }

    public @NonNull List<Block> fetchBlocks(@NonNull final String host, final int port, final long endBlockNumber) {
        return fetchBlocks(host, port, endBlockNumber, DEFAULT_TIMEOUT);
    }

    private @NonNull List<Block> invokeSubscribe(
            @NonNull final BlockStreamSubscribeServiceClient client, @NonNull final SubscribeStreamRequest request) {
        requireNonNull(client);
        requireNonNull(request);
        final Method method = findSubscribeMethod(client.getClass());
        try {
            if (method.getParameterCount() == 1) {
                final Object result = method.invoke(client, request);
                if (result instanceof Iterable<?> iterable) {
                    return toBlocks(iterable);
                }
                throw new IllegalStateException("Unsupported subscribe response type: " + result);
            }
            if (method.getParameterCount() == 2) {
                final var collector = new ResponseCollector();
                method.invoke(client, request, collector);
                if (!collector.await(DEFAULT_WAIT)) {
                    throw new IllegalStateException("Timed out waiting for subscribe stream completion");
                }
                return toBlocks(collector.responses());
            }
            throw new IllegalStateException("Unsupported subscribe method signature: " + method);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed invoking subscribe method", e);
        }
    }

    private @NonNull Method findSubscribeMethod(@NonNull final Class<?> type) {
        return java.util.Arrays.stream(type.getMethods())
                .filter(m -> m.getName().equals("subscribeBlockStream"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("subscribeBlockStream method not found on " + type));
    }

    private @NonNull List<Block> toBlocks(@NonNull final Iterable<?> responses) {
        final Map<Long, List<BlockItem>> itemsByBlock = new TreeMap<>();
        SubscribeStreamResponse.Code terminalStatus = null;
        long currentBlock = -1;
        for (final var item : responses) {
            if (!(item instanceof SubscribeStreamResponse response)) {
                continue;
            }
            if (response.hasStatus()) {
                terminalStatus = response.statusOrThrow();
                continue;
            }
            if (response.hasBlockItems()) {
                final BlockItemSet set = response.blockItemsOrThrow();
                for (final var blockItem : set.blockItems()) {
                    if (blockItem.hasBlockHeader()) {
                        currentBlock = blockItem.blockHeaderOrThrow().number();
                    }
                    if (currentBlock < 0) {
                        throw new IllegalStateException("Block stream received items before a block header");
                    }
                    itemsByBlock.computeIfAbsent(currentBlock, ignored -> new ArrayList<>()).add(blockItem);
                }
            }
        }
        if (terminalStatus != SubscribeStreamResponse.Code.SUCCESS) {
            throw new IllegalStateException("Subscribe stream did not terminate successfully: " + terminalStatus);
        }
        return itemsByBlock.values().stream().map(Block::new).toList();
    }

    private BlockNodeConfiguration blockNodeConfig(final String host, final int port) {
        final var http = BlockNodeHelidonHttpConfiguration.newBuilder()
                .name("bdd-subscribe-http")
                .build();
        final var grpc = BlockNodeHelidonGrpcConfiguration.newBuilder()
                .name("bdd-subscribe-grpc")
                .build();
        return BlockNodeConfiguration.newBuilder()
                .address(host)
                .servicePort(port)
                .streamingPort(port)
                .priority(0)
                .messageSizeSoftLimitBytes(BlockNodeConfiguration.DEFAULT_MESSAGE_SOFT_LIMIT_BYTES)
                .messageSizeHardLimitBytes(BlockNodeConfiguration.DEFAULT_MESSAGE_HARD_LIMIT_BYTES)
                .clientHttpConfig(http)
                .clientGrpcConfig(grpc)
                .build();
    }

    private static final class ResponseCollector implements Pipeline<SubscribeStreamResponse> {
        private final CountDownLatch done = new CountDownLatch(1);
        private final List<SubscribeStreamResponse> responses = new ArrayList<>();
        private volatile Throwable error;

        @Override
        public void onNext(final SubscribeStreamResponse response) {
            responses.add(response);
        }

        @Override
        public void onError(final Throwable throwable) {
            error = throwable;
            done.countDown();
        }

        @Override
        public void onComplete() {
            done.countDown();
        }

        @Override
        public void onSubscribe(final java.util.concurrent.Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        boolean await(@NonNull final Duration timeout) {
            try {
                final boolean completed = done.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
                if (error != null) {
                    throw new IllegalStateException("Subscribe stream failed", error);
                }
                return completed;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        List<SubscribeStreamResponse> responses() {
            return List.copyOf(responses);
        }
    }
}
