// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.wires.output.internal;

import static com.swirlds.component.framework.schedulers.builders.TaskSchedulerType.NO_OP;
import static java.util.Objects.requireNonNull;

import com.swirlds.component.framework.model.TraceableWiringModel;
import com.swirlds.component.framework.schedulers.TaskScheduler;
import com.swirlds.component.framework.schedulers.builders.TaskSchedulerType;
import com.swirlds.component.framework.transformers.AdvancedTransformation;
import com.swirlds.component.framework.transformers.WireFilter;
import com.swirlds.component.framework.transformers.WireListSplitter;
import com.swirlds.component.framework.transformers.WireTransformer;
import com.swirlds.component.framework.wires.SolderType;
import com.swirlds.component.framework.wires.input.BindableInputWire;
import com.swirlds.component.framework.wires.input.InputWire;
import com.swirlds.component.framework.wires.output.OutputWire;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.lang.Thread.UncaughtExceptionHandler;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * An output wire that will take data and forward it to its outputs.
 *
 * @param <IN>  the type of data passed to the forwarding method
 * @param <OUT> the type of data forwarded to things soldered to this wire
 */
public abstract class ForwardingOutputWire<IN, OUT> implements OutputWire<OUT> {

    protected final TraceableWiringModel model;
    protected final String name;
    protected final UncaughtExceptionHandler uncaughtExceptionHandler;

    /**
     * Constructor.
     *
     * @param model                    the wiring model containing this output wire
     * @param name                     the name of the output wire
     * @param uncaughtExceptionHandler handler for uncaught exceptions that occur while processing data on this output
     *                                 wire
     */
    protected ForwardingOutputWire(
            @NonNull final TraceableWiringModel model,
            @NonNull final String name,
            @NonNull final UncaughtExceptionHandler uncaughtExceptionHandler) {
        this.model = requireNonNull(model);
        this.name = requireNonNull(name);
        this.uncaughtExceptionHandler = requireNonNull(uncaughtExceptionHandler);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public String getName() {
        return name;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void solderTo(@NonNull final InputWire<OUT> inputWire) {
        solderTo(inputWire, SolderType.PUT);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void orderedSolderTo(@NonNull final List<InputWire<OUT>> inputWires) {
        if (inputWires.size() < 2) {
            throw new IllegalArgumentException("List must contain at least 2 input wires.");
        }
        inputWires.forEach(this::solderTo);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void solderTo(@NonNull final InputWire<OUT> inputWire, @NonNull final SolderType solderType) {
        if (inputWire.getTaskSchedulerType() == NO_OP) {
            return;
        }

        model.registerEdge(name, inputWire.getTaskSchedulerName(), inputWire.getName(), solderType);

        switch (solderType) {
            case PUT -> addForwardingDestination(inputWire::put);
            case INJECT -> addForwardingDestination(inputWire::inject);
            case OFFER -> addForwardingDestination(inputWire::offer);
            default -> throw new IllegalArgumentException("Unknown solder type: " + solderType);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void solderForMonitoring(@NonNull final Consumer<OUT> consumer) {
        addForwardingDestination(consumer);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void solderTo(
            @NonNull final String handlerName,
            @NonNull final String inputWireLabel,
            @NonNull final Consumer<OUT> handler) {

        final TaskScheduler<Void> directScheduler = model.<Void>schedulerBuilder(handlerName)
                .withType(TaskSchedulerType.DIRECT)
                .build();

        final BindableInputWire<OUT, Void> directSchedulerInputWire = directScheduler.buildInputWire(inputWireLabel);
        directSchedulerInputWire.bindConsumer(handler);

        this.solderTo(directSchedulerInputWire);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public OutputWire<OUT> buildFilter(
            @NonNull final String filterName,
            @NonNull final String filterInputName,
            @NonNull final Predicate<OUT> predicate) {

        requireNonNull(filterName);
        requireNonNull(filterInputName);
        requireNonNull(predicate);

        final WireFilter<OUT> filter = new WireFilter<>(model, filterName, filterInputName, predicate);
        solderTo(filter.getInputWire());
        return filter.getOutputWire();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    @SuppressWarnings("unchecked")
    public <ELEMENT> OutputWire<ELEMENT> buildSplitter(
            @NonNull final String splitterName, @NonNull final String splitterInputName) {

        requireNonNull(splitterName);
        requireNonNull(splitterInputName);

        final WireListSplitter<ELEMENT> splitter = new WireListSplitter<>(model, splitterName, splitterInputName);
        solderTo((InputWire<OUT>) splitter.getInputWire());
        return splitter.getOutputWire();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public <NEW_OUT> OutputWire<NEW_OUT> buildTransformer(
            @NonNull final String transformerName,
            @NonNull final String transformerInputName,
            @NonNull final Function<OUT, NEW_OUT> transformer) {

        requireNonNull(transformerName);
        requireNonNull(transformerInputName);
        requireNonNull(transformer);

        final WireTransformer<OUT, NEW_OUT> wireTransformer =
                new WireTransformer<>(model, transformerName, transformerInputName, transformer);
        solderTo(wireTransformer.getInputWire());
        return wireTransformer.getOutputWire();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public <NEW_OUT> OutputWire<NEW_OUT> buildAdvancedTransformer(
            @NonNull final AdvancedTransformation<OUT, NEW_OUT> transformer) {

        final TransformingOutputWire<OUT, NEW_OUT> outputWire = new TransformingOutputWire<>(
                model,
                transformer.getTransformerName(),
                uncaughtExceptionHandler,
                transformer::transform,
                transformer::inputCleanup,
                transformer::outputCleanup);

        solderTo(transformer.getTransformerName(), transformer.getTransformerInputName(), outputWire::forward);

        return outputWire;
    }

    /**
     * Forward output data to any wires/consumers that are listening for it.
     * <p>
     * Although it will technically work, it is a violation of convention to directly put data into this output wire
     * except from within code being executed by the task scheduler that owns this output wire. Don't do it.
     *
     * @param data the output data to forward
     */
    public abstract void forward(@NonNull final IN data);

    /**
     * Creates a new forwarding destination.
     *
     * @param destination the destination to forward data to
     */
    protected abstract void addForwardingDestination(@NonNull final Consumer<OUT> destination);
}
