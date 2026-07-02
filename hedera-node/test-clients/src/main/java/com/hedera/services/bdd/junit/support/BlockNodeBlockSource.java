// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.Block;
import com.hedera.services.bdd.junit.hedera.BlockNodeNetwork;
import com.hedera.services.bdd.junit.hedera.BlockNodeReader;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.List;
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
 * <p>All block-node read I/O (the REAL-vs-SIMULATOR switch, the per-block-node client loop) is delegated
 * to {@link BlockNodeReader}; this class only adds the polling/push delivery on top of those reads.
 */
public class BlockNodeBlockSource implements BlockSource {
    private static final Logger log = LogManager.getLogger(BlockNodeBlockSource.class);

    /** How long to sleep between polls of the block node for newly available blocks. */
    private static final Duration POLL_INTERVAL = Duration.ofMillis(250);

    private final BlockNodeReader reader;

    /**
     * Creates a block-node-backed source reading from the given network.
     *
     * @param blockNodeNetwork the active block node network
     */
    public BlockNodeBlockSource(@NonNull final BlockNodeNetwork blockNodeNetwork) {
        this.reader = BlockNodeReader.of(requireNonNull(blockNodeNetwork));
    }

    @NonNull
    @Override
    public Runnable subscribe(@NonNull final StreamDataListener listener) {
        requireNonNull(listener);
        final var stop = new AtomicBoolean(false);
        final var poller = new Thread(() -> pollLoop(listener, stop), "BlockNodeBlockSource-poller");
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
        return BlockNodeReader.of(requireNonNull(network)).allBlocks();
    }

    private void pollLoop(@NonNull final StreamDataListener listener, @NonNull final AtomicBoolean stop) {
        long lastDelivered = -1L;
        while (!stop.get()) {
            try {
                final long latest = reader.latestAvailableBlock();
                if (latest > lastDelivered) {
                    final var blocks = reader.blocks(lastDelivered + 1, latest);
                    for (final var block : blocks) {
                        final long number = BlockNodeReader.blockNumberOf(block);
                        // Skip header-less blocks and any block at or below the watermark. A range fetch can
                        // re-deliver an already-seen block, and delivering one twice corrupts stateful
                        // listeners (e.g. SidecarWatcher's translator re-applies a FileAppend, doubling a file
                        // page). Advancing the watermark only past blocks we actually deliver also lets a
                        // partial fetch retry the remainder on the next poll.
                        if (number == Long.MAX_VALUE || number <= lastDelivered) {
                            continue;
                        }
                        listener.onNewBlock(block);
                        lastDelivered = number;
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
}
