// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.Block;
import com.hedera.services.bdd.junit.hedera.BlockNodeNetwork;
import com.hedera.services.bdd.junit.hedera.BlockNodeReader;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A {@link BlockSource} that reads blocks from the active block node over gRPC. This is used under
 * {@code blockStream.writerMode=GRPC}, where no {@code .blk} files are written to disk and the only
 * source of truth is the block node (a real container or a simulator).
 *
 * <p>For a REAL block node this opens a <em>single</em> long-lived {@code subscribeBlockStream}
 * subscription that follows the live tip and pushes each block to the listener as it arrives; a
 * supervisor thread re-subscribes from the next unseen block if that stream drops (transient gRPC
 * errors, an idle-connection reap, or a block-node restart are all tolerated this way). This holds
 * exactly one subscriber handler on the block node for the source's lifetime, instead of the hundreds
 * that accumulate when a fresh bounded subscription is opened on every poll. The SIMULATOR path reads
 * from memory (no gRPC handlers to leak) and keeps the simple background poll loop below.
 *
 * <p>All block-node read I/O (the REAL-vs-SIMULATOR switch, the per-block-node client loop) is delegated
 * to {@link BlockNodeReader}; this class only adds the polling/push delivery on top of those reads.
 */
public class BlockNodeBlockSource implements BlockSource {
    private static final Logger log = LogManager.getLogger(BlockNodeBlockSource.class);

    /** How long to sleep between polls of the block node for newly available blocks (SIMULATOR path). */
    private static final Duration POLL_INTERVAL = Duration.ofMillis(250);

    /** Backoff before re-subscribing after a live stream drops, to avoid a tight reconnect loop. */
    private static final Duration RECONNECT_BACKOFF = Duration.ofSeconds(1);

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
        // REAL block node: one long-lived live subscription (supervised for reconnect), so the block
        // node holds a single subscriber handler for our lifetime, not one accumulating per poll.
        if (reader.isReal()) {
            final var supervisor = new Thread(() -> liveSupervisorLoop(listener, stop), "BlockNodeBlockSource-live");
            supervisor.setDaemon(true);
            supervisor.start();
            return () -> {
                stop.set(true);
                supervisor.interrupt();
                try {
                    supervisor.join(Duration.ofSeconds(5).toMillis());
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            };
        }
        // SIMULATOR: in-memory reads, no gRPC handlers to leak -- keep the simple poll loop.
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
     * Maintains a single live subscription to a REAL block node, delivering blocks to the listener as
     * they arrive and re-subscribing (from the next unseen block) whenever the stream drops, until
     * stopped. At most one subscription is open at a time, so the block node holds exactly one
     * subscriber handler for our lifetime.
     */
    private void liveSupervisorLoop(@NonNull final StreamDataListener listener, @NonNull final AtomicBoolean stop) {
        final var lastDelivered = new AtomicLong(-1L);
        while (!stop.get()) {
            final var streamEnded = new CountDownLatch(1);
            AutoCloseable handle = null;
            try {
                handle = reader.streamLive(
                        lastDelivered.get() + 1,
                        block -> {
                            final long number = BlockNodeReader.blockNumberOf(block);
                            // Skip header-less blocks and any at/below the watermark (a reconnect can
                            // re-deliver the boundary block; the listener also dedupes by consensus time).
                            if (number == Long.MAX_VALUE || number <= lastDelivered.get()) {
                                return;
                            }
                            listener.onNewBlock(block);
                            lastDelivered.set(number);
                        },
                        streamEnded::countDown);
                // Block until the subscription drops (error/completion) or we are interrupted on stop.
                streamEnded.await();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (final Exception e) {
                log.warn("Live block subscription failed to start; will retry", e);
            } finally {
                closeQuietly(handle);
            }
            if (!stop.get()) {
                try {
                    Thread.sleep(RECONNECT_BACKOFF.toMillis());
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private static void closeQuietly(final AutoCloseable handle) {
        if (handle != null) {
            try {
                handle.close();
            } catch (final Exception e) {
                log.warn("Failed to close live block subscription", e);
            }
        }
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
