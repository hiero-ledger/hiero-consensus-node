// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.quiescence;

import static java.util.Objects.requireNonNull;
import static org.hiero.consensus.model.quiescence.QuiescenceCommand.DONT_QUIESCE;
import static org.hiero.consensus.model.quiescence.QuiescenceCommand.QUIESCE;

import com.hedera.node.app.blocks.impl.BlockStreamManagerImpl;
import com.swirlds.platform.system.Platform;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.quiescence.QuiescenceCommand;

/**
 * The {@link BlockStreamManagerImpl} (re)starts the heartbeat when the {@link QuiescenceController} first reports a
 * status of {@link QuiescenceCommand#QUIESCE}. Each heartbeat, invokes the {@link TctProbe#findTct()} to find the
 * earliest target consensus timestamp (TCT) that marks where quiescence should end, no matter if user transactions
 * remain dormant. When a non-null TCT is found, sets it on the controller via
 * {@link QuiescenceController#setNextTargetConsensusTime(Instant)}.
 * <p>
 * The heartbeat is stopped when the {@link QuiescenceController} reports any status other than
 * {@link QuiescenceCommand#QUIESCE} inside the heartbeat.
 */
@Singleton
public class QuiescedHeartbeat {
    private static final Logger log = LogManager.getLogger(QuiescedHeartbeat.class);

    private final Platform platform;
    private final QuiescenceController controller;
    private final ScheduledExecutorService scheduler;

    /** Number of consecutive DONT_QUIESCE ticks before the heartbeat gives up. */
    private static final int MAX_DONT_QUIESCE_TICKS = 3;

    @Nullable
    private ScheduledFuture<?> heartbeatFuture;

    private int consecutiveDontQuiesceTicks;

    @Inject
    public QuiescedHeartbeat(@NonNull final QuiescenceController controller, Platform platform) {
        this(platform, controller, Executors.newSingleThreadScheduledExecutor(r -> {
            final var thread = new Thread(r, "quiesced-heartbeat");
            thread.setDaemon(true);
            return thread;
        }));
    }

    /**
     * Package-private constructor for testing that allows injection of a custom scheduler.
     *
     * @param controller the quiescence controller
     * @param scheduler the scheduled executor service
     */
    QuiescedHeartbeat(
            @NonNull final Platform platform,
            @NonNull final QuiescenceController controller,
            @NonNull final ScheduledExecutorService scheduler) {
        this.platform = requireNonNull(platform);
        this.controller = requireNonNull(controller);
        this.scheduler = requireNonNull(scheduler);
    }

    /**
     * Schedules a heartbeat at the given interval that will last until the {@link QuiescenceController} reports a
     * status other than {@link QuiescenceCommand#QUIESCE}.
     */
    public void start(@NonNull final Duration heartbeatInterval, @NonNull final TctProbe probe) {
        requireNonNull(heartbeatInterval);
        requireNonNull(probe);

        // Cancel any existing heartbeat
        stop();
        consecutiveDontQuiesceTicks = 0;

        // Schedule the heartbeat task
        heartbeatFuture = scheduler.scheduleAtFixedRate(
                () -> {
                    try {
                        heartbeat(probe);
                    } catch (Exception e) {
                        log.warn("Unhandled exception in quiesced heartbeat", e);
                    }
                },
                heartbeatInterval.toMillis(),
                heartbeatInterval.toMillis(),
                TimeUnit.MILLISECONDS);
        log.info("Started quiesced heartbeat at interval {}", heartbeatInterval);
    }

    /**
     * Stops the heartbeat if it is running.
     */
    public void stop() {
        if (heartbeatFuture != null) {
            heartbeatFuture.cancel(false);
            heartbeatFuture = null;
        }
    }

    /**
     * Shuts down the scheduler. This should be called when the heartbeat is no longer needed.
     */
    public void shutdown() {
        log.info("Shutting down quiescence heartbeat");
        stop();
        scheduler.shutdown();
    }

    /**
     * The heartbeat task that probes for the TCT and updates the controller.
     *
     * <p>The heartbeat tolerates transient {@link QuiescenceCommand#DONT_QUIESCE} states caused by
     * the pipeline transaction count being temporarily elevated while blocks await signing. Only
     * {@link QuiescenceCommand#BREAK_QUIESCENCE} (indicating a pending user transaction requires
     * breaking quiescence) causes the heartbeat to stop and notify the platform.
     */
    private void heartbeat(@NonNull final TctProbe probe) {
        try {
            // Probe for the TCT
            final var tct = probe.findTct();
            // If a non-null TCT is found, set it on the controller
            if (tct != null) {
                controller.setNextTargetConsensusTime(tct);
            }
            final var commandNow = controller.getQuiescenceStatus();
            if (commandNow == QUIESCE) {
                // Reset the counter when quiescence is healthy
                consecutiveDontQuiesceTicks = 0;
            } else if (commandNow == DONT_QUIESCE) {
                // DONT_QUIESCE is often transient — the pipeline count may be temporarily elevated
                // while blocks are being signed.  Tolerate a few consecutive ticks before giving up
                // so the heartbeat isn't restarted only by block-signing callbacks.
                consecutiveDontQuiesceTicks++;
                if (consecutiveDontQuiesceTicks >= MAX_DONT_QUIESCE_TICKS) {
                    log.info(
                            "Stopping quiescence heartbeat ({}, {} consecutive ticks)",
                            commandNow,
                            consecutiveDontQuiesceTicks);
                    platform.quiescenceCommand(commandNow);
                    stop();
                }
            } else {
                // BREAK_QUIESCENCE — a pending user transaction requires ending quiescence immediately
                log.info("Stopping quiescence heartbeat ({})", commandNow);
                platform.quiescenceCommand(commandNow);
                stop();
            }
        } catch (final Exception e) {
            // End quiescence and stop the heartbeat to avoid log spam from repeated failures
            platform.quiescenceCommand(DONT_QUIESCE);
            stop();
            throw e;
        }
    }
}
