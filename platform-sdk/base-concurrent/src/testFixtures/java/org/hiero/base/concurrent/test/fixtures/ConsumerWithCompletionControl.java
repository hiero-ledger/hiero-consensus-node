// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.concurrent.test.fixtures;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.Consumer;

/**
 * A Consumer that marks the number of times it was executed and allows to wait util a specified number of these marks
 * have been collected. It also allows blocking the execution of the task until a gate is released.
 *
 * @param <H> the type of the handler
 */
public final class ConsumerWithCompletionControl<H> implements Consumer<H> {
    private final Consumer<H> handler;
    private final ExecutionControl executionControl;

    ConsumerWithCompletionControl(@NonNull final Consumer<H> handler, @NonNull final Gate gate) {
        this.executionControl = new ExecutionControl(gate);
        this.handler = handler;
    }

    /**
     * @return the completion control
     */
    public ExecutionControl executionControl() {
        return executionControl;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void accept(final H h) {
        executionControl.knock();
        try {
            handler.accept(h);
        } finally {
            executionControl.mark();
        }
    }

    /**
     * Creates a Consumer that automatically marks its completion and allows to wait for that completion to be marked.
     * The consumer will be blocked until the gate is released.
     *
     * @param handler the handler to wrap
     * @param <H>     the type of the handler
     * @return the new {@link ConsumerWithCompletionControl}
     */
    public static <H> ConsumerWithCompletionControl<H> blocked(@NonNull final Consumer<H> handler) {
        return new ConsumerWithCompletionControl<>(handler, Gate.closedGate());
    }

    /**
     * Creates a Consumer that automatically marks its completion and allows to wait for that completion to be marked.
     * The consumer will not be blocked. Calling unblock will not produce results.
     *
     * @param handler the handler to wrap
     * @param <H>     the type of the handler
     * @return the new {@link ConsumerWithCompletionControl}
     */
    public static <H> ConsumerWithCompletionControl<H> unblocked(@NonNull final Consumer<H> handler) {
        return new ConsumerWithCompletionControl<>(handler, Gate.openGate());
    }
}
