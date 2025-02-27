// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.wires.input;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * An object that can be bound to a handler.
 *
 * @param <IN>  the type of data that enters
 * @param <OUT> the type of data that is permitted to be passed out (non-null values are forwarded to the primary output
 *              wire)
 */
public interface Bindable<IN, OUT> {

    /**
     * Bind this object to a handler. For things that don't send data to the output wire.
     *
     * @param handler the handler to bind to this input wire
     * @throws IllegalStateException if a handler is already bound and this method is called a second time
     */
    void bindConsumer(@NonNull Consumer<IN> handler);

    /**
     * Bind this object to a handler.
     *
     * @param handler the handler to bind to this input task scheduler, values returned are passed to the primary output
     *                wire of the associated scheduler.
     * @throws IllegalStateException if a handler is already bound and this method is called a second time
     */
    void bind(@NonNull final Function<IN, OUT> handler);
}
