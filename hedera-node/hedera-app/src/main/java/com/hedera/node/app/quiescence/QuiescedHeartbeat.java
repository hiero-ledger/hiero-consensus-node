// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.quiescence;

import static java.util.Objects.requireNonNull;
import static org.hiero.consensus.model.quiescence.QuiescenceCommand.DONT_QUIESCE;
import static org.hiero.consensus.model.quiescence.QuiescenceCommand.QUIESCE;

import com.hedera.node.app.blocks.impl.BlockStreamManagerImpl;
import com.swirlds.metrics.api.Counter;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.state.State;
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
 * {@link QuiescenceCommand#QUIESCE} inside the heartbeat. Transitions out of {@code QUIESCE} are routed through
 * {@link QuiescenceCommands#update(QuiescenceCommand)} so the manager-side {@code lastCommand} stays in sync —
 * fixing the bug from issue #25140 where the heartbeat emitted to the platform directly and left the manager
 * holding a stale {@code QUIESCE}.
 *
 * <p>See <a href="../../../../../../../../../docs/design/app/quiescence-analysis.md">hedera-node/docs/design/app/quiescence-analysis.md</a> for context.
 */
@Singleton
public class QuiescedHeartbeat {
    private static final Logger log = LogManager.getLogger(QuiescedHeartbeat.class);

    private final QuiescenceCommands quiescenceCommands;
    private final QuiescenceController controller;
    private final ScheduledExecutorService scheduler;
    private final Counter heartbeatErrors;

    @Nullable
    private volatile ScheduledFuture<?> heartbeatFuture;

    @Inject
    public QuiescedHeartbeat(
            @NonNull final QuiescenceController controller,
            @NonNull final QuiescenceCommands quiescenceCommands,
            @NonNull final Metrics metrics) {
        this(
                quiescenceCommands,
                controller,
                Executors.newSingleThreadScheduledExecutor(r -> {
                    final var thread = new Thread(r, "quiesced-heartbeat");
                    thread.setDaemon(true);
                    return thread;
                }),
                metrics);
    }

    /**
     * Package-private constructor for testing that allows injection of a custom scheduler.
     */
    QuiescedHeartbeat(
            @NonNull final QuiescenceCommands quiescenceCommands,
            @NonNull final QuiescenceController controller,
            @NonNull final ScheduledExecutorService scheduler,
            @NonNull final Metrics metrics) {
        this.quiescenceCommands = requireNonNull(quiescenceCommands);
        this.controller = requireNonNull(controller);
        this.scheduler = requireNonNull(scheduler);
        this.heartbeatErrors = requireNonNull(metrics)
                .getOrCreate(new Counter.Config("quiescence", "heartbeatErrors")
                        .withDescription("Number of unhandled exceptions thrown inside the quiesced heartbeat tick"));
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

        // Schedule the heartbeat task
        heartbeatFuture = scheduler.scheduleAtFixedRate(
                () -> {
                    try {
                        heartbeat(probe);
                    } catch (final Exception e) {
                        // heartbeat() already incremented the error counter, routed DONT_QUIESCE, and cancelled
                        // this task via stop() before rethrowing. We catch-and-log here only so the failure is
                        // visible, instead of being silently suppressed by the executor when a periodic task throws.
                        log.warn("Unhandled exception in quiesced heartbeat", e);
                    }
                },
                0,
                heartbeatInterval.toMillis(),
                TimeUnit.MILLISECONDS);
        log.info("Started quiesced heartbeat at interval {}", heartbeatInterval);
    }

    /**
     * Re-evaluates the node's quiescence status and routes any change through the shared
     * {@link QuiescenceCommands#update(QuiescenceCommand)}; when this poll observes a real transition into
     * {@link QuiescenceCommand#QUIESCE} it (re)starts the heartbeat with a fresh {@link TctProbe}. This is the single
     * poll-and-maybe-start site shared by the block-record (RECORDS mode) and block-stream (BLOCKS mode) managers, so
     * the two stream modes cannot drift apart (issue #25140).
     *
     * @param heartbeatInterval                    the interval at which the heartbeat ticks once started
     * @param maxConsecutiveScheduleSecondsToProbe the probe window for upcoming scheduled-transaction seconds
     * @param stakePeriodMins                      the staking period in minutes ({@code <= 0} means no stake-period TCT)
     * @param state                                the state to probe for the next target consensus time
     */
    public void pollAndMaybeStart(
            @NonNull final Duration heartbeatInterval,
            final int maxConsecutiveScheduleSecondsToProbe,
            final long stakePeriodMins,
            @NonNull final State state) {
        requireNonNull(heartbeatInterval);
        requireNonNull(state);
        final var commandNow = controller.getQuiescenceStatus();
        if (quiescenceCommands.update(commandNow) && commandNow == QUIESCE) {
            start(heartbeatInterval, new TctProbe(maxConsecutiveScheduleSecondsToProbe, stakePeriodMins, state));
        }
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
     */
    private void heartbeat(@NonNull final TctProbe probe) {
        try {
            final var tct = probe.findTct();
            if (tct != null) {
                controller.setNextTargetConsensusTime(tct);
            }
            final var commandNow = controller.getQuiescenceStatus();
            if (commandNow != QUIESCE) {
                log.info("Stopping quiescence heartbeat ({})", commandNow);
                quiescenceCommands.update(commandNow);
                stop();
            }
        } catch (final Exception e) {
            heartbeatErrors.increment();
            // End quiescence and stop the heartbeat to avoid log spam from repeated failures.
            // update() is a no-op when DONT_QUIESCE is already the recorded last command, so we don't
            // generate churn on platforms that were never reported as QUIESCE.
            quiescenceCommands.update(DONT_QUIESCE);
            stop();
            throw e;
        }
    }
}
