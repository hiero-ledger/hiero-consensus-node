// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.Block;
import com.hedera.services.bdd.junit.hedera.BlockNodeMode;
import com.hedera.services.bdd.junit.hedera.BlockNodeNetwork;
import com.hedera.services.bdd.junit.hedera.containers.BlockNodeContainer;
import com.hedera.services.bdd.junit.hedera.containers.BlockNodeSubscribeClient;
import com.hedera.services.bdd.junit.hedera.simulator.SimulatedBlockNodeServer;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A {@link BlockSource} that reads blocks from the active block node over gRPC. This is used under
 * {@code blockStream.writerMode=GRPC}, where no {@code .blk} files are written to disk and the only
 * source of truth is the block node (a real container or a simulator).
 *
 * <p>The block node exposes a <em>range</em> fetch ({@code subscribeBlocks(start, end)}), not a live
 * push stream, so this source runs a background polling thread. Each poll it computes the latest
 * available block number, and if it has advanced past what was already delivered, fetches the new
 * range and pushes each block to the listener in ascending order. Transient gRPC errors and
 * block-finalization lag are tolerated: a failed or empty poll simply retries on the next interval.
 *
 * @see StreamValidationOp readBlocksFromBlockNodes (the analogous one-shot fetch recipe)
 */
public class BlockNodeBlockSource implements BlockSource {
    private static final Logger log = LogManager.getLogger(BlockNodeBlockSource.class);

    /** How long to sleep between polls of the block node for newly available blocks. */
    private static final Duration POLL_INTERVAL = Duration.ofMillis(250);

    private final BlockNodeNetwork blockNodeNetwork;

    /**
     * Creates a block-node-backed source reading from the given network.
     *
     * @param blockNodeNetwork the active block node network
     */
    public BlockNodeBlockSource(@NonNull final BlockNodeNetwork blockNodeNetwork) {
        this.blockNodeNetwork = requireNonNull(blockNodeNetwork);
    }

    @NonNull
    @Override
    public Runnable subscribe(@NonNull final StreamDataListener listener) {
        requireNonNull(listener);
        final var mode = blockNodeNetwork.getBlockNodeModeById().values().stream()
                .findFirst()
                .orElse(BlockNodeMode.NONE);
        final var stop = new AtomicBoolean(false);
        final var poller = new Thread(() -> pollLoop(listener, mode, stop), "BlockNodeBlockSource-poller");
        poller.setDaemon(true);
        poller.start();
        return () -> {
            stop.set(true);
            try {
                poller.join(Duration.ofSeconds(5).toMillis());
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
    }

    /**
     * Fetches every block currently available on the given network, from block {@code 0} up to the
     * latest available block, in ascending block-number order. This is the one-shot pull analogue of
     * the instance polling behavior, used as the synchronous final-rescan fallback in block-stream
     * assertions under {@code blockStream.writerMode=GRPC}.
     *
     * @param network the active block node network
     * @return all available blocks {@code [0, latest]} in ascending order, or an empty list if none
     */
    @NonNull
    public static List<Block> fetchAllBlocks(@NonNull final BlockNodeNetwork network) {
        requireNonNull(network);
        final var mode =
                network.getBlockNodeModeById().values().stream().findFirst().orElse(BlockNodeMode.NONE);
        final long latest = latestAvailableBlock(network, mode);
        if (latest < 0) {
            return List.of();
        }
        return fetchBlocks(network, mode, 0L, latest);
    }

    private void pollLoop(
            @NonNull final StreamDataListener listener,
            @NonNull final BlockNodeMode mode,
            @NonNull final AtomicBoolean stop) {
        long lastDelivered = -1L;
        while (!stop.get()) {
            try {
                final long latest = latestAvailableBlock(blockNodeNetwork, mode);
                if (latest > lastDelivered) {
                    final var blocks = fetchBlocks(blockNodeNetwork, mode, lastDelivered + 1, latest);
                    for (final var block : blocks) {
                        listener.onNewBlock(block);
                        final long number = blockNumberOf(block);
                        // Only advance the watermark past blocks we actually delivered, so any
                        // blocks the node reported as available but did not yet return (finalization
                        // lag, partial fetch) are retried on the next poll rather than skipped.
                        if (number != Long.MAX_VALUE && number > lastDelivered) {
                            lastDelivered = number;
                        }
                    }
                }
            } catch (final Exception e) {
                // Tolerate transient gRPC errors and finalization lag; retry on the next poll
                log.warn("Poll of block node for new blocks failed; will retry", e);
            }
            try {
                Thread.sleep(POLL_INTERVAL.toMillis());
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * Computes the highest block number currently available across the block node(s) in the network,
     * or {@code -1} if none are available yet.
     */
    private static long latestAvailableBlock(
            @NonNull final BlockNodeNetwork blockNodeNetwork, @NonNull final BlockNodeMode mode) {
        if (mode == BlockNodeMode.SIMULATOR) {
            return blockNodeNetwork.getSimulatedBlockNodeById().values().stream()
                    .mapToLong(SimulatedBlockNodeServer::getLastVerifiedBlockNumber)
                    .max()
                    .orElse(-1L);
        } else if (mode == BlockNodeMode.REAL) {
            long best = -1L;
            for (final var entry : blockNodeNetwork.getBlockNodeContainerById().entrySet()) {
                final var container = entry.getValue();
                try (final var client = new BlockNodeSubscribeClient(container.getHost(), container.getPort())) {
                    final long last = client.getLastAvailableBlock();
                    if (last > best) {
                        best = last;
                    }
                } catch (final Exception e) {
                    log.warn("Failed to query real block node {} for latest block", entry.getKey(), e);
                }
            }
            return best;
        }
        return -1L;
    }

    /**
     * Fetches the blocks in the inclusive range {@code [start, end]} from the block node, in
     * ascending block-number order.
     */
    @NonNull
    private static List<Block> fetchBlocks(
            @NonNull final BlockNodeNetwork blockNodeNetwork,
            @NonNull final BlockNodeMode mode,
            final long start,
            final long end) {
        if (mode == BlockNodeMode.SIMULATOR) {
            return fetchFromSimulators(blockNodeNetwork, start, end);
        } else if (mode == BlockNodeMode.REAL) {
            return fetchFromRealContainers(blockNodeNetwork, start, end);
        }
        return List.of();
    }

    @NonNull
    private static List<Block> fetchFromSimulators(
            @NonNull final BlockNodeNetwork blockNodeNetwork, final long start, final long end) {
        for (final Map.Entry<Long, SimulatedBlockNodeServer> entry :
                blockNodeNetwork.getSimulatedBlockNodeById().entrySet()) {
            try {
                final var inRange = entry.getValue().getAllVerifiedBlocks().stream()
                        .filter(block -> {
                            final long number = blockNumberOf(block);
                            return number >= start && number <= end;
                        })
                        .sorted(Comparator.comparingLong(BlockNodeBlockSource::blockNumberOf))
                        .toList();
                if (!inRange.isEmpty()) {
                    return inRange;
                }
            } catch (final Exception e) {
                log.warn("Failed to read blocks {}-{} from simulator block node {}", start, end, entry.getKey(), e);
            }
        }
        return List.of();
    }

    @NonNull
    private static List<Block> fetchFromRealContainers(
            @NonNull final BlockNodeNetwork blockNodeNetwork, final long start, final long end) {
        for (final Map.Entry<Long, BlockNodeContainer> entry :
                blockNodeNetwork.getBlockNodeContainerById().entrySet()) {
            final var container = entry.getValue();
            try (final var client = new BlockNodeSubscribeClient(container.getHost(), container.getPort())) {
                final var blocks = new ArrayList<>(client.subscribeBlocks(start, end));
                blocks.sort(Comparator.comparingLong(BlockNodeBlockSource::blockNumberOf));
                if (!blocks.isEmpty()) {
                    return blocks;
                }
            } catch (final Exception e) {
                log.warn("Failed to read blocks {}-{} from real block node {}", start, end, entry.getKey(), e);
            }
        }
        return List.of();
    }

    private static long blockNumberOf(@NonNull final Block block) {
        if (!block.items().isEmpty() && block.items().getFirst().hasBlockHeader()) {
            return block.items().getFirst().blockHeader().number();
        }
        return Long.MAX_VALUE;
    }
}
