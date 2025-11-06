// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.wires.output;

import com.swirlds.component.framework.model.TraceableWiringModel;
import com.swirlds.component.framework.wires.output.internal.ForwardingOutputWire;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.lang.Thread.UncaughtExceptionHandler;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * An output wire that will take data and forward it to its outputs. Output type is the same as the input type.
 *
 * @param <OUT> the type of data passed to the forwarding method
 */
public class StandardOutputWire<OUT> extends ForwardingOutputWire<OUT, OUT> {
    private final List<Consumer<OUT>> forwardingDestinations = new ArrayList<>();

    /**
     * Constructor.
     *
     * @param model                    the wiring model containing this output wire
     * @param name                     the name of the output wire
     * @param uncaughtExceptionHandler handler for uncaught exceptions that occur while processing data on this output
     *                                 wire
     */
    public StandardOutputWire(
            @NonNull final TraceableWiringModel model,
            @NonNull final String name,
            @NonNull final UncaughtExceptionHandler uncaughtExceptionHandler) {
        super(model, name, uncaughtExceptionHandler);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void addForwardingDestination(@NonNull final Consumer<OUT> destination) {
        Objects.requireNonNull(destination);
        forwardingDestinations.add(destination);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void forward(@NonNull final OUT data) {
        for (final Consumer<OUT> destination : forwardingDestinations) {
            try {
                destination.accept(data);
            } catch (final Exception e) {
                getUncaughtExceptionHandler().uncaughtException(Thread.currentThread(), e);
            }
        }
    }
}
