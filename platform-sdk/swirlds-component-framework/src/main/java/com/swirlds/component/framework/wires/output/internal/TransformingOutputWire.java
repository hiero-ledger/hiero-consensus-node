// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.wires.output.internal;

import com.swirlds.component.framework.model.TraceableWiringModel;
import com.swirlds.component.framework.wires.SolderType;
import com.swirlds.component.framework.wires.input.InputWire;
import com.swirlds.component.framework.wires.output.OutputWire;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.lang.Thread.UncaughtExceptionHandler;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * An output wire that transforms data that flows across it. For advanced use cases where
 * {@link OutputWire#buildTransformer(String, String, Function)} semantics are insufficient.
 *
 * @param <IN>  the type of data passed to the forwarding method
 * @param <OUT> the type of data forwarded to things soldered to this wire
 */
public class TransformingOutputWire<IN, OUT> extends ForwardingOutputWire<IN, OUT> {
    private final List<Consumer<OUT>> forwardingDestinations = new ArrayList<>();

    private final Function<IN, OUT> transform;
    private final Consumer<IN> inputCleanup;
    private final Consumer<OUT> outputCleanup;

    /**
     * Constructor.
     *
     * @param model                    the wiring model containing this output wire
     * @param name                     the name of the output wire
     * @param uncaughtExceptionHandler handler for uncaught exceptions
     * @param transformer              the function to transform the data from the input type to the output type. Is
     *                                 called once per output per data item. If this method returns null then the data
     *                                 is not forwarded.
     * @param inputCleanup             an optional method that is called on input data after the data is forwarded to
     *                                 all destinations. The original data is passed to this method. Ignored if null.
     * @param outputCleanup            an optional method that is called on output data if it is rejected by a
     *                                 destination. This is possible if offer soldering is used and the destination
     *                                 declines to take the data.
     */
    public TransformingOutputWire(
            @NonNull final TraceableWiringModel model,
            @NonNull final String name,
            @NonNull final UncaughtExceptionHandler uncaughtExceptionHandler,
            @NonNull final Function<IN, OUT> transformer,
            @Nullable final Consumer<IN> inputCleanup,
            @Nullable final Consumer<OUT> outputCleanup) {
        super(model, name, uncaughtExceptionHandler);

        this.transform = Objects.requireNonNull(transformer);
        this.inputCleanup = inputCleanup == null ? (data) -> {} : inputCleanup;
        this.outputCleanup = outputCleanup == null ? (data) -> {} : outputCleanup;
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
    public void forward(@NonNull final IN data) {
        for (final Consumer<OUT> destination : forwardingDestinations) {
            try {
                final OUT transformed = transform.apply(data);
                if (transformed == null) {
                    // Do not forward null values.
                    return;
                }
                destination.accept(transformed);
            } catch (final Exception e) {
                getUncaughtExceptionHandler().uncaughtException(Thread.currentThread(), e);
            }
        }
        inputCleanup.accept(data);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void solderTo(@NonNull final InputWire<OUT> inputWire, @NonNull final SolderType solderType) {
        getModel().registerEdge(getName(), inputWire.getTaskSchedulerName(), inputWire.getName(), solderType);

        switch (solderType) {
            case PUT -> addForwardingDestination(inputWire::put);
            case INJECT -> addForwardingDestination(inputWire::inject);
            case OFFER ->
                addForwardingDestination(x -> {
                    if (!inputWire.offer(x)) {
                        outputCleanup.accept(x);
                    }
                });
            default -> throw new IllegalArgumentException("Unknown solder type: " + solderType);
        }
    }
}
