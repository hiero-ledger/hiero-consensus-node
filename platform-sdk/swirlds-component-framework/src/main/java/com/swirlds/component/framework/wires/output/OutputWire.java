// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.wires.output;

import static com.swirlds.component.framework.schedulers.builders.TaskSchedulerType.NO_OP;

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
import com.swirlds.component.framework.wires.output.internal.TransformingOutputWire;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.lang.Thread.UncaughtExceptionHandler;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Describes the output of a task scheduler. Can be soldered to wire inputs or lambdas.
 *
 * @param <OUT> the output type of the object
 */
public abstract class OutputWire<OUT> {

    private final TraceableWiringModel model;
    private final String name;
    private final UncaughtExceptionHandler uncaughtExceptionHandler;

    /**
     * Constructor.
     *
     * @param model                    the wiring model containing this output wire
     * @param name                     the name of the output wire
     * @param uncaughtExceptionHandler handler for uncaught exceptions that occur while processing data on this output
     *                                 wire
     */
    public OutputWire(
            @NonNull final TraceableWiringModel model,
            @NonNull final String name,
            @NonNull final UncaughtExceptionHandler uncaughtExceptionHandler) {
        this.model = Objects.requireNonNull(model);
        this.name = Objects.requireNonNull(name);
        this.uncaughtExceptionHandler = Objects.requireNonNull(uncaughtExceptionHandler);
    }

    /**
     * Get the name of this output wire. If this object is a task scheduler, this is the same as the name of the task
     * scheduler.
     *
     * @return the name
     */
    @NonNull
    public String getName() {
        return name;
    }

    /**
     * Get the wiring model that contains this output wire.
     *
     * @return the wiring model
     */
    @NonNull
    protected TraceableWiringModel getModel() {
        return model;
    }

    /**
     * Get the uncaught exception handler for this output wire. This handler is called when an uncaught exception
     * occurs while processing data on this output wire.
     *
     * @return the uncaught exception handler
     */
    @NonNull
    protected UncaughtExceptionHandler getUncaughtExceptionHandler() {
        return uncaughtExceptionHandler;
    }

    /**
     * Specify an input wire where output data should be passed. This forwarding operation respects back pressure.
     * Equivalent to calling {@link #solderTo(InputWire, SolderType)} with {@link SolderType#PUT}.
     *
     * <p>
     * Soldering is the act of connecting two wires together, usually by melting a metal alloy between them. See
     * <a href="https://en.wikipedia.org/wiki/Soldering">wikipedia's entry on soldering</a>.
     *
     * <p>
     * Forwarding should be fully configured prior to data being inserted into the system. Adding forwarding
     * destinations after data has been inserted into the system is not thread safe and has undefined behavior.
     *
     * @param inputWire the input wire to forward output data to
     */
    public void solderTo(@NonNull final InputWire<OUT> inputWire) {
        solderTo(inputWire, SolderType.PUT);
    }

    /**
     * A convenience method that should be used iff the order in which the {@code inputWires} are soldered is important.
     * Using this method reduces the chance of inadvertent reordering when code is modified or reorganized. All
     * invocations of this method should carefully document why the provided ordering is important.
     * <p>
     * Since this method is specifically for input wires that require a certain order, at least two input wires must be
     * provided.
     *
     * @param inputWires – an ordered list of the input wire to forward output data to
     * @throws IllegalArgumentException if the size of {@code inputWires} is less than 2
     * @see #solderTo(InputWire)
     */
    public void orderedSolderTo(@NonNull final List<InputWire<OUT>> inputWires) {
        if (inputWires.size() < 2) {
            throw new IllegalArgumentException("List must contain at least 2 input wires.");
        }
        inputWires.forEach(this::solderTo);
    }

    /**
     * Specify an input wire where output data should be passed. This forwarding operation respects back pressure.
     *
     * <p>
     * Soldering is the act of connecting two wires together, usually by melting a metal alloy between them. See
     * <a href="https://en.wikipedia.org/wiki/Soldering">wikipedia's entry on soldering</a>.
     *
     * <p>
     * Forwarding should be fully configured prior to data being inserted into the system. Adding forwarding
     * destinations after data has been inserted into the system is not thread safe and has undefined behavior.
     *
     * @param inputWire  the input wire to forward output data to
     * @param solderType the semantics of the soldering operation
     */
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
     * Specify a consumer where output data should be forwarded. This method creates a direct task scheduler under the
     * hood and forwards output data to it.
     *
     * <p>
     * Soldering is the act of connecting two wires together, usually by melting a metal alloy between them. See
     * <a href="https://en.wikipedia.org/wiki/Soldering">wikipedia's entry on soldering</a>.
     *
     * <p>
     * Forwarding should be fully configured prior to data being inserted into the system. Adding forwarding
     * destinations after data has been inserted into the system is not thread safe and has undefined behavior.
     *
     * @param handlerName    the name of the consumer
     * @param inputWireLabel the label for the input wire going into the consumer
     * @param handler        the consumer to forward output data to
     */
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
     * Build a {@link WireFilter}. The input wire to the filter is automatically soldered to this output wire (i.e. all
     * data that comes out of the wire will be inserted into the filter). The output wire of the filter is returned by
     * this method.
     *
     * @param filterName      the name of the filter
     * @param filterInputName the label for the input wire going into the filter
     * @param predicate       the predicate that filters the output of this wire
     * @return the output wire of the filter
     */
    @NonNull
    public OutputWire<OUT> buildFilter(
            @NonNull final String filterName,
            @NonNull final String filterInputName,
            @NonNull final Predicate<OUT> predicate) {

        Objects.requireNonNull(filterName);
        Objects.requireNonNull(filterInputName);
        Objects.requireNonNull(predicate);

        final WireFilter<OUT> filter = new WireFilter<>(model, filterName, filterInputName, predicate);
        solderTo(filter.getInputWire());
        return filter.getOutputWire();
    }

    /**
     * Build a {@link WireListSplitter}. Creating a splitter for wires without a list output type will cause runtime
     * exceptions. The input wire to the splitter is automatically soldered to this output wire (i.e. all data that
     * comes out of the wire will be inserted into the splitter). The output wire of the splitter is returned by this
     * method.
     *
     * @param <ELEMENT> the type of the list elements
     * @return output wire of the splitter
     */
    @SuppressWarnings("unchecked")
    @NonNull
    public <ELEMENT> OutputWire<ELEMENT> buildSplitter(
            @NonNull final String splitterName, @NonNull final String splitterInputName) {

        Objects.requireNonNull(splitterName);
        Objects.requireNonNull(splitterInputName);

        final WireListSplitter<ELEMENT> splitter = new WireListSplitter<>(model, splitterName, splitterInputName);
        solderTo((InputWire<OUT>) splitter.getInputWire());
        return splitter.getOutputWire();
    }

    /**
     * Build a {@link WireTransformer}. The input wire to the transformer is automatically soldered to this output wire
     * (i.e. all data that comes out of the wire will be inserted into the transformer). The output wire of the
     * transformer is returned by this method.
     *
     * @param transformerName      the name of the transformer
     * @param transformerInputName the label for the input wire going into the transformer
     * @param transformer          the function that transforms the output of this wire into the output of the
     *                             transformer. Called once per data item. Null data returned by this method is not
     *                             forwarded.
     * @param <NEW_OUT>            the output type of the transformer
     * @return the output wire of the transformer
     */
    @NonNull
    public <NEW_OUT> OutputWire<NEW_OUT> buildTransformer(
            @NonNull final String transformerName,
            @NonNull final String transformerInputName,
            @NonNull final Function<OUT, NEW_OUT> transformer) {

        Objects.requireNonNull(transformerName);
        Objects.requireNonNull(transformerInputName);
        Objects.requireNonNull(transformer);

        final WireTransformer<OUT, NEW_OUT> wireTransformer =
                new WireTransformer<>(model, transformerName, transformerInputName, transformer);
        solderTo(wireTransformer.getInputWire());
        return wireTransformer.getOutputWire();
    }

    /**
     * Build a transformation wire with cleanup functionality.
     * <p>
     * The input wire to the transformer is automatically soldered to this output wire (i.e. all data that comes out of
     * the wire will be inserted into the transformer). The output wire of the transformer is returned by this method.
     * Similar to {@link #buildTransformer(String, String, Function)}, but instead of the transformer method being
     * called once per data item, it is called once per output per data item.
     *
     * @param transformer an object that manages the transformation
     * @param <NEW_OUT>   the output type of the transformer
     * @return the output wire of the transformer
     */
    @NonNull
    public <NEW_OUT> OutputWire<NEW_OUT> buildAdvancedTransformer(
            @NonNull final AdvancedTransformation<OUT, NEW_OUT> transformer) {

        final TransformingOutputWire<OUT, NEW_OUT> outputWire = new TransformingOutputWire<>(
                model,
                transformer.getTransformerName(),
                getUncaughtExceptionHandler(),
                transformer::transform,
                transformer::inputCleanup,
                transformer::outputCleanup);

        solderTo(transformer.getTransformerName(), transformer.getTransformerInputName(), outputWire::forward);

        return outputWire;
    }

    /**
     * Creates a new forwarding destination.
     *
     * @param destination the destination to forward data to
     */
    protected abstract void addForwardingDestination(@NonNull final Consumer<OUT> destination);
}
