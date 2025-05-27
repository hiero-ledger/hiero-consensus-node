// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.schedulers.helpers;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.Consumer;

/**
 * A Consumer that marks the number of times it was executed and allows to wait util a specified number of these marks
 * have been collected. It also allows blocking the execution of the task until a gate is released.
 *
 * @param <H> the type of the handler
 */
public final class ConsumerWithCompleteMarks<H> extends AbstractWithCompleteMarks implements Consumer<H> {
    private final Consumer<H> handler;

    ConsumerWithCompleteMarks(@NonNull final Consumer<H> handler, @NonNull final Gate gate) {
        super(gate);
        this.handler = handler;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void accept(final H h) {
        gate.nock();
        try {
            handler.accept(h);
        } finally {
            mark();
        }
    }

    /**
     * Creates a Consumer that automatically marks its completion and allows to wait for that completion to be marked.
     * The consumer will be blocked until the gate is released.
     *
     * @param handler the handler to wrap
     * @param <H>     the type of the handler
     * @return the new {@link ConsumerWithCompleteMarks}
     */
    public static <H> ConsumerWithCompleteMarks<H> blocked(@NonNull final Consumer<H> handler) {
        return new ConsumerWithCompleteMarks<>(handler, Gate.closedGate());
    }

    /**
     * Creates a Consumer that automatically marks its completion and allows to wait for that completion to be marked.
     * The consumer will not be blocked. Calling unblock will not produce results.
     *
     * @param handler the handler to wrap
     * @param <H>     the type of the handler
     * @return the new {@link ConsumerWithCompleteMarks}
     */
    public static <H> ConsumerWithCompleteMarks<H> unBlocked(@NonNull final Consumer<H> handler) {
        return new ConsumerWithCompleteMarks<>(handler, Gate.openGate());
    }
}
