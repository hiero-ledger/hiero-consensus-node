// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.quiescence;

import static java.util.Objects.requireNonNull;
import static org.hiero.consensus.model.quiescence.QuiescenceCommand.DONT_QUIESCE;

import com.swirlds.platform.system.Platform;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.atomic.AtomicReference;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.hiero.consensus.model.quiescence.QuiescenceCommand;

/**
 * Owns dispatch of {@link QuiescenceCommand}s to the {@link Platform} and tracks the last command sent. Centralizing
 * this here guarantees the tracked state never drifts from what was actually sent: every dispatch path goes through
 * {@link #send(QuiescenceCommand)} or {@link #sendIfChanged(QuiescenceCommand)}.
 */
@Singleton
public class QuiescenceCommands {

    private final Platform platform;
    private final AtomicReference<QuiescenceCommand> lastSent = new AtomicReference<>(DONT_QUIESCE);

    @Inject
    public QuiescenceCommands(@NonNull final Platform platform) {
        this.platform = requireNonNull(platform);
    }

    /**
     * @return the most recent command that was successfully sent to the platform
     */
    @NonNull
    public QuiescenceCommand lastSent() {
        return lastSent.get();
    }

    /**
     * If {@code command} differs from the last command sent, atomically updates the tracked state and dispatches it to
     * the platform. Concurrent callers observing the same transition will only dispatch once.
     *
     * @return {@code true} if this call dispatched the command, {@code false} if the tracked state already matched
     */
    public boolean sendIfChanged(@NonNull final QuiescenceCommand command) {
        requireNonNull(command);
        final var previous = lastSent.get();
        if (previous == command) {
            return false;
        }
        if (!lastSent.compareAndSet(previous, command)) {
            return false;
        }
        platform.quiescenceCommand(command);
        return true;
    }

    /**
     * Unconditionally dispatches {@code command} to the platform and updates the tracked state. Use this from paths
     * that must force a dispatch regardless of the previously tracked state (e.g. an exception path forcing
     * {@link QuiescenceCommand#DONT_QUIESCE}).
     */
    public void send(@NonNull final QuiescenceCommand command) {
        requireNonNull(command);
        lastSent.set(command);
        platform.quiescenceCommand(command);
    }
}
