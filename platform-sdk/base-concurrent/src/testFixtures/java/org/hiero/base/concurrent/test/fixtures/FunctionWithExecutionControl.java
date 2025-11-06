// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.concurrent.test.fixtures;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.Function;

/**
 * A Function that marks the number of times it was executed and allows to wait util a specified number of these marks
 * have been collected. It also allows blocking the execution of the task until a gate is released.
 *
 * @param <V> the type of the parameter
 * @param <R> the type of the return value
 */
public class FunctionWithExecutionControl<V, R> implements Function<V, R> {

    private final Function<V, R> function;
    private final ExecutionControl executionControl;

    FunctionWithExecutionControl(@NonNull final Function<V, R> function, @NonNull final Gate gate) {
        this.executionControl = new ExecutionControl(gate);
        this.function = function;
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
    public R apply(final V v) {
        executionControl.knock();
        try {
            return function.apply(v);
        } finally {
            executionControl.mark();
        }
    }

    /**
     * Creates a Function that automatically marks its completion and allows to wait for that completion to be marked.
     * The consumer will be blocked until the gate is released.
     *
     * @param handler the handler to wrap
     * @param <V>     the type of the parameter
     * @param <R>     the type of the return value
     * @return the new {@link FunctionWithExecutionControl}
     */
    public static <V, R> FunctionWithExecutionControl<V, R> blocked(@NonNull final Function<V, R> handler) {
        return new FunctionWithExecutionControl<>(handler, Gate.closedGate());
    }

    /**
     * Creates a Function that automatically marks its completion and allows to wait for that completion to be marked.
     * The consumer will not be blocked. Calling unblock will not produce results.
     *
     * @param handler the handler to wrap
     * @param <V>     the type of the parameter
     * @param <R>     the type of the return value
     * @return the new {@link ConsumerWithCompletionControl}
     */
    public static <V, R> FunctionWithExecutionControl<V, R> unBlocked(@NonNull final Function<V, R> handler) {
        return new FunctionWithExecutionControl<>(handler, Gate.openGate());
    }
}
