// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.schedulers.helpers;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.Function;

/**
 * A Function that marks the number of times it was executed and allows to wait util a specified number of these marks
 * have been collected. It also allows blocking the execution of the task until a gate is released.
 *
 * @param <V> the type of the parameter
 * @param <R> the type of the return value
 */
public class FunctionWithCompleteMarks<V, R> extends AbstractWithCompleteMarks implements Function<V, R> {

    private final Function<V, R> function;

    FunctionWithCompleteMarks(@NonNull final Function<V, R> function, @NonNull final Gate gate) {
        super(gate);
        this.function = function;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public R apply(final V v) {
        gate.nock();
        try {
            return function.apply(v);
        } finally {

            mark();
        }
    }

    /**
     * Creates a Function that automatically marks its completion and allows to wait for that completion to be marked.
     * The consumer will be blocked until the gate is released.
     *
     * @param handler the handler to wrap
     * @param <V>     the type of the parameter
     * @param <R>     the type of the return value
     * @return the new {@link FunctionWithCompleteMarks}
     */
    public static <V, R> FunctionWithCompleteMarks<V, R> blocked(@NonNull final Function<V, R> handler) {
        return new FunctionWithCompleteMarks<>(handler, Gate.closedGate());
    }

    /**
     * Creates a Function that automatically marks its completion and allows to wait for that completion to be marked.
     * The consumer will not be blocked. Calling unblock will not produce results.
     *
     * @param handler the handler to wrap
     * @param <V>     the type of the parameter
     * @param <R>     the type of the return value
     * @return the new {@link ConsumerWithCompleteMarks}
     */
    public static <V, R> FunctionWithCompleteMarks<V, R> unBlocked(@NonNull final Function<V, R> handler) {
        return new FunctionWithCompleteMarks<>(handler, Gate.openGate());
    }
}
