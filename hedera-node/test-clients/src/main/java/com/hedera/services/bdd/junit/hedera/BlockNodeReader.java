// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.hedera;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.Block;
import com.hedera.services.bdd.junit.extensions.NetworkTargetingExtension;
import com.hedera.services.bdd.junit.hedera.containers.BlockNodeSubscribeClient;
import com.hedera.services.bdd.junit.hedera.simulator.SimulatedBlockNodeServer;
import com.hedera.services.bdd.spec.HapiSpec;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The single place that reads blocks from a {@link BlockNodeNetwork} in tests, hiding the
 * {@link BlockNodeMode#REAL REAL}-vs-{@link BlockNodeMode#SIMULATOR SIMULATOR} split and the per-block-node
 * client loop behind a small set of primitives. It also owns the canonical "active block node network"
 * resolution ({@link HapiSpec#TARGET_BLOCK_NODE_NETWORK spec-scoped target} first, else the
 * {@link NetworkTargetingExtension#SHARED_BLOCK_NODE_NETWORK shared network}).
 *
 * <p>Consumers that need delivery semantics on top of these reads (a background poll/push loop, retry,
 * freeze-block merging, dump-to-disk) layer those on; this class only owns the read I/O and resolution
 * that were previously copy-pasted across {@code BlockNodeBlockSource}, {@code BlockSourceFactory},
 * {@code HapiSpecWaitUntilNextBlock}, {@code StreamValidationOp}, {@code SidecarWatcher} and
 * {@code BlockNodeOp}.
 */
public final class BlockNodeReader {
    private static final Logger log = LogManager.getLogger(BlockNodeReader.class);

    private final BlockNodeNetwork network;

    private BlockNodeReader(@NonNull final BlockNodeNetwork network) {
        this.network = requireNonNull(network);
    }

    /**
     * Returns a reader over the given network.
     *
     * @param network the block node network to read from
     * @return a reader over that network
     */
    public static BlockNodeReader of(@NonNull final BlockNodeNetwork network) {
        return new BlockNodeReader(network);
    }

    /**
     * Resolves the block node network in effect for the current spec: the spec-scoped target if one is set,
     * otherwise the shared network, or {@code null} if neither is active.
     *
     * @return the active block node network, or {@code null} if none
     */
    @Nullable
    public static BlockNodeNetwork activeNetwork() {
        var network = HapiSpec.TARGET_BLOCK_NODE_NETWORK.get();
        if (network == null) {
            network = NetworkTargetingExtension.SHARED_BLOCK_NODE_NETWORK.get();
        }
        return network;
    }

    /**
     * Returns a reader over the {@link #activeNetwork() active network}, or empty if none is active.
     *
     * @return an optional reader over the active network
     */
    public static Optional<BlockNodeReader> forActiveNetwork() {
        return Optional.ofNullable(activeNetwork()).map(BlockNodeReader::of);
    }

    /**
     * Returns the highest block number currently available across the block node(s) in the network, or
     * {@code -1} if none are available yet.
     *
     * @return the latest available block number, or {@code -1} if none
     */
    public long latestAvailableBlock() {
        final var mode = mode();
        if (mode == BlockNodeMode.SIMULATOR) {
            return network.getSimulatedBlockNodeById().values().stream()
                    .mapToLong(SimulatedBlockNodeServer::getLastVerifiedBlockNumber)
                    .max()
                    .orElse(-1L);
        } else if (mode == BlockNodeMode.REAL) {
            long best = -1L;
            for (final var entry : network.getBlockNodeContainerById().entrySet()) {
                final var container = entry.getValue();
                try (final var client = new BlockNodeSubscribeClient(container.getHost(), container.getPort())) {
                    best = Math.max(best, client.getLastAvailableBlock());
                } catch (final Exception e) {
                    log.warn("Failed to query real block node {} for latest block", entry.getKey(), e);
                }
            }
            return best;
        }
        return -1L;
    }

    /**
     * Returns the blocks in the inclusive range {@code [startIncl, endIncl]}, in ascending block-number
     * order, or an empty list if none are available.
     *
     * @param startIncl the first block number (inclusive)
     * @param endIncl the last block number (inclusive)
     * @return the blocks in range, ascending, or empty
     */
    @NonNull
    public List<Block> blocks(final long startIncl, final long endIncl) {
        final var mode = mode();
        if (mode == BlockNodeMode.SIMULATOR) {
            return simulatorBlocks(startIncl, endIncl);
        } else if (mode == BlockNodeMode.REAL) {
            return realContainerBlocks(startIncl, endIncl);
        }
        return List.of();
    }

    /**
     * Returns every available block, from block {@code 0} up to the {@link #latestAvailableBlock() latest},
     * in ascending order, or an empty list if none are available.
     *
     * @return all available blocks {@code [0, latest]} ascending, or empty
     */
    @NonNull
    public List<Block> allBlocks() {
        final long latest = latestAvailableBlock();
        if (latest < 0) {
            return List.of();
        }
        return blocks(0L, latest);
    }

    /**
     * Returns whether the given block number is available on any block node in the network. For simulators
     * this checks block receipt; for real nodes it checks the reported last-available block.
     *
     * @param number the block number
     * @return true if the block is available on some block node
     */
    public boolean hasBlock(final long number) {
        final var mode = mode();
        if (mode == BlockNodeMode.SIMULATOR) {
            return network.getSimulatedBlockNodeById().values().stream().anyMatch(sim -> sim.hasReceivedBlock(number));
        } else if (mode == BlockNodeMode.REAL) {
            for (final var entry : network.getBlockNodeContainerById().entrySet()) {
                final var container = entry.getValue();
                try (final var client = new BlockNodeSubscribeClient(container.getHost(), container.getPort())) {
                    if (client.getLastAvailableBlock() >= number) {
                        return true;
                    }
                } catch (final Exception e) {
                    log.warn("Failed to query real block node {} for block {}", entry.getKey(), number, e);
                }
            }
        }
        return false;
    }

    /**
     * Returns the block number from a block's header, or {@link Long#MAX_VALUE} if it has no header (so a
     * header-less block sorts last and can be excluded from watermark advancement by callers).
     *
     * @param block the block
     * @return the header block number, or {@link Long#MAX_VALUE} if there is no header
     */
    public static long blockNumberOf(@NonNull final Block block) {
        if (!block.items().isEmpty() && block.items().getFirst().hasBlockHeader()) {
            return block.items().getFirst().blockHeader().number();
        }
        return Long.MAX_VALUE;
    }

    private BlockNodeMode mode() {
        return network.getBlockNodeModeById().values().stream().findFirst().orElse(BlockNodeMode.NONE);
    }

    @NonNull
    private List<Block> simulatorBlocks(final long start, final long end) {
        for (final var entry : network.getSimulatedBlockNodeById().entrySet()) {
            try {
                final var inRange = entry.getValue().getAllVerifiedBlocks().stream()
                        .filter(block -> {
                            final long number = blockNumberOf(block);
                            return number >= start && number <= end;
                        })
                        .sorted(Comparator.comparingLong(BlockNodeReader::blockNumberOf))
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
    private List<Block> realContainerBlocks(final long start, final long end) {
        for (final var entry : network.getBlockNodeContainerById().entrySet()) {
            final var container = entry.getValue();
            try (final var client = new BlockNodeSubscribeClient(container.getHost(), container.getPort())) {
                final var blocks = new ArrayList<>(client.subscribeBlocks(start, end));
                blocks.sort(Comparator.comparingLong(BlockNodeReader::blockNumberOf));
                if (!blocks.isEmpty()) {
                    return blocks;
                }
            } catch (final Exception e) {
                log.warn("Failed to read blocks {}-{} from real block node {}", start, end, entry.getKey(), e);
            }
        }
        return List.of();
    }
}
