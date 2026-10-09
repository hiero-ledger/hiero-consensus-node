// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.quiescence;

import static java.util.Objects.requireNonNull;
import static org.hiero.consensus.model.quiescence.QuiescenceCommand.DONT_QUIESCE;

import com.swirlds.metrics.api.IntegerGauge;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.system.Platform;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.atomic.AtomicReference;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.quiescence.QuiescenceCommand;

/**
 * Centralizes dispatch of {@link QuiescenceCommand}s to the {@link Platform} and owns the canonical
 * "last command sent" state. All call sites that previously held their own {@code lastQuiescenceCommand}
 * (the block-stream / block-record managers and the {@link QuiescedHeartbeat}) route their transitions
 * through {@link #update(QuiescenceCommand)} so that heartbeat-driven transitions stay in sync with
 * manager-driven transitions.
 *
 * <p>See <a href="../../../../../../../../../docs/design/app/quiescence-analysis.md">hedera-node/docs/design/app/quiescence-analysis.md</a>
 */
@Singleton
public class QuiescenceCommands {
    private static final Logger logger = LogManager.getLogger(QuiescenceCommands.class);

    private final Platform platform;
    private final AtomicReference<QuiescenceCommand> lastCommand = new AtomicReference<>(DONT_QUIESCE);
    private final IntegerGauge commandGauge;

    @Inject
    public QuiescenceCommands(@NonNull final Platform platform, @NonNull final Metrics metrics) {
        this.platform = requireNonNull(platform);
        this.commandGauge = requireNonNull(metrics)
                .getOrCreate(new IntegerGauge.Config("quiescence", "command")
                        .withDescription(
                                "Current QuiescenceCommand (ordinal: QUIESCE=0, BREAK_QUIESCENCE=1, DONT_QUIESCE=2)")
                        .withInitialValue(DONT_QUIESCE.ordinal()));
    }

    /**
     * Atomically records the latest quiescence command. If it differs from the previously recorded
     * command, dispatches it to the platform and updates the metric. Concurrent callers that lose the
     * CAS race see {@code false} returned without re-dispatching.
     *
     * @param commandNow the command to dispatch
     * @return {@code true} iff this call observed a real transition and emitted the command to the
     *     platform; {@code false} if the command was already the latest, or another thread won the
     *     race
     */
    public boolean update(@NonNull final QuiescenceCommand commandNow) {
        requireNonNull(commandNow);
        final QuiescenceCommand previous = lastCommand.get();
        if (commandNow == previous) {
            return false;
        }
        if (!lastCommand.compareAndSet(previous, commandNow)) {
            return false;
        }
        logger.info("Updating quiescence command from {} to {}", previous, commandNow);
        commandGauge.set(commandNow.ordinal());
        platform.quiescenceCommand(commandNow);
        return true;
    }

    /**
     * Resets the recorded last command back to {@link QuiescenceCommand#DONT_QUIESCE}. Intended to be
     * called when {@link QuiescenceController#platformStatusUpdate} sees {@code RECONNECT_COMPLETE}:
     * the controller clears its own counters, and the recorded last command must be cleared too so
     * the next poll sees a clean transition. Does not emit anything to the platform.
     */
    public void resetForReconnect() {
        lastCommand.set(DONT_QUIESCE);
        commandGauge.set(DONT_QUIESCE.ordinal());
    }

    /**
     * Returns the most recently dispatched command.
     */
    @NonNull
    public QuiescenceCommand current() {
        return lastCommand.get();
    }
}
