// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.wiring.framework.wires;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.swirlds.base.time.Time;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import org.hiero.consensus.fakes.noop.NoOpMetrics;
import org.hiero.consensus.wiring.framework.model.WiringModel;
import org.hiero.consensus.wiring.framework.model.WiringModelBuilder;
import org.hiero.consensus.wiring.framework.schedulers.TaskScheduler;
import org.hiero.consensus.wiring.framework.schedulers.builders.TaskSchedulerType;
import org.hiero.consensus.wiring.framework.transformers.AdvancedTransformation;
import org.hiero.consensus.wiring.framework.wires.input.BindableInputWire;
import org.hiero.consensus.wiring.framework.wires.input.InputWire;
import org.hiero.consensus.wiring.framework.wires.output.OutputWire;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests the functionality of output wires
 */
public class OutputWireTests {

    /**
     * Test that the ordered solder to method forwards data in the proper order.
     *
     * @param count the number of data to send through the wires
     */
    @ParameterizedTest()
    @ValueSource(ints = {10_000})
    void orderedSolderToTest(final int count) {
        final WiringModel model =
                WiringModelBuilder.create(new NoOpMetrics(), Time.getCurrent()).build();

        final TaskScheduler<Integer> intForwarder = model.<Integer>schedulerBuilder("intForwarder")
                .withType(TaskSchedulerType.DIRECT)
                .build();
        final TaskScheduler<Void> firstComponent = model.<Void>schedulerBuilder("firstComponent")
                .withType(TaskSchedulerType.DIRECT)
                .build();
        final TaskScheduler<Void> secondComponent = model.<Void>schedulerBuilder("secondComponent")
                .withType(TaskSchedulerType.DIRECT)
                .build();

        final BindableInputWire<Integer, Integer> intInput = intForwarder.buildInputWire("intInput");
        final BindableInputWire<Integer, Void> firstComponentInput = firstComponent.buildInputWire("ints");
        final BindableInputWire<Integer, Void> secondComponentInput = secondComponent.buildInputWire("ints");

        // Send integers to the first component before the second component
        final List<InputWire<Integer>> inputList = List.of(firstComponentInput, secondComponentInput);
        intForwarder.getOutputWire().orderedSolderTo(inputList);

        intInput.bind((i -> i));

        final AtomicInteger firstCompRecNum = new AtomicInteger();
        final AtomicInteger secondCompRecNum = new AtomicInteger();
        final AtomicInteger firstCompErrorCount = new AtomicInteger();
        final AtomicInteger secondCompErrorCount = new AtomicInteger();

        firstComponentInput.bindConsumer(i -> {
            if (firstCompRecNum.incrementAndGet() <= secondCompRecNum.get()) {
                firstCompErrorCount.incrementAndGet();
            }
        });
        secondComponentInput.bindConsumer(i -> {
            if (firstCompRecNum.get() != secondCompRecNum.incrementAndGet()) {
                secondCompErrorCount.incrementAndGet();
            }
        });

        for (int i = 0; i < count; i++) {
            intInput.put(i);
        }

        assertEquals(0, firstCompErrorCount.get(), "The first component should always receive data first");
        assertEquals(0, secondCompErrorCount.get(), "The second component should always receive data second");
    }

    /**
     * Test that the expected exceptions are thrown
     */
    @Test
    void orderedSolderToThrows() {
        final WiringModel model =
                WiringModelBuilder.create(new NoOpMetrics(), Time.getCurrent()).build();

        final TaskScheduler<Integer> schedulerA = model.<Integer>schedulerBuilder("schedulerA")
                .withType(TaskSchedulerType.DIRECT)
                .build();
        final TaskScheduler<Integer> schedulerB = model.<Integer>schedulerBuilder("schedulerB")
                .withType(TaskSchedulerType.DIRECT)
                .build();

        InputWire<Integer> inputWire = schedulerB.buildInputWire("inputWire");
        assertThrows(
                IllegalArgumentException.class,
                () -> schedulerA.getOutputWire().orderedSolderTo(List.of(inputWire)),
                "Method should throw when provided less than two input wires.");
    }

    /**
     * A transformer that returns null must not prevent the input cleanup method from running.
     */
    @Test
    void nullTransformationStillRunsInputCleanup() {
        final WiringModel model =
                WiringModelBuilder.create(new NoOpMetrics(), Time.getCurrent()).build();

        final TaskScheduler<Integer> producer = model.<Integer>schedulerBuilder("producer")
                .withType(TaskSchedulerType.DIRECT)
                .build();
        final BindableInputWire<Integer, Integer> producerInput = producer.buildInputWire("ints");
        producerInput.bind(i -> i);

        final List<Integer> cleanedUpInputs = new ArrayList<>();
        final List<Integer> cleanedUpOutputs = new ArrayList<>();

        // Odd values are transformed into null and must not be forwarded
        final OutputWire<Integer> evenOnly = producer.getOutputWire()
                .buildAdvancedTransformer(new AdvancedTransformationHelper<>(
                        "evenOnly", i -> i % 2 == 0 ? i : null, cleanedUpInputs::add, cleanedUpOutputs::add));

        final List<Integer> received = new ArrayList<>();
        evenOnly.solderTo("consumer", "ints", received::add);

        for (int i = 0; i < 6; i++) {
            producerInput.put(i);
        }

        assertEquals(List.of(0, 2, 4), received, "null transformations should not be forwarded");
        assertEquals(
                List.of(0, 1, 2, 3, 4, 5),
                cleanedUpInputs,
                "input cleanup should run for every data item, including those transformed into null");
        assertEquals(List.of(), cleanedUpOutputs, "output cleanup should not run for data that was never forwarded");
    }

    /**
     * The transformer is invoked once per destination per data item. If it returns null for one destination, the
     * remaining destinations must still receive their data.
     */
    @Test
    void nullTransformationDoesNotSkipRemainingDestinations() {
        final WiringModel model =
                WiringModelBuilder.create(new NoOpMetrics(), Time.getCurrent()).build();

        final TaskScheduler<Integer> producer = model.<Integer>schedulerBuilder("producer")
                .withType(TaskSchedulerType.DIRECT)
                .build();
        final BindableInputWire<Integer, Integer> producerInput = producer.buildInputWire("ints");
        producerInput.bind(i -> i);

        // Two destinations, so the transformer runs twice per data item: null for the first, the data for the second
        final AtomicInteger transformCount = new AtomicInteger();
        final List<Integer> cleanedUpInputs = new ArrayList<>();
        final OutputWire<Integer> transformer = producer.getOutputWire()
                .buildAdvancedTransformer(new AdvancedTransformationHelper<>(
                        "nullForFirstDestination",
                        i -> transformCount.getAndIncrement() % 2 == 0 ? null : i,
                        cleanedUpInputs::add,
                        null));

        final List<Integer> firstDestination = new ArrayList<>();
        final List<Integer> secondDestination = new ArrayList<>();
        transformer.solderTo("firstConsumer", "ints", firstDestination::add);
        transformer.solderTo("secondConsumer", "ints", secondDestination::add);

        for (int i = 0; i < 5; i++) {
            producerInput.put(i);
        }

        assertEquals(List.of(), firstDestination, "the first destination should never receive data");
        assertEquals(
                List.of(0, 1, 2, 3, 4),
                secondDestination,
                "a null transformation for one destination must not stop later destinations from receiving data");
        assertEquals(List.of(0, 1, 2, 3, 4), cleanedUpInputs, "input cleanup should run for every data item");
    }

    /**
     * Input cleanup happens once per data item, not once per destination, even when every destination's transformation
     * produces null.
     */
    @Test
    void inputCleanupRunsOncePerDataItemWhenAllTransformationsAreNull() {
        final WiringModel model =
                WiringModelBuilder.create(new NoOpMetrics(), Time.getCurrent()).build();

        final TaskScheduler<Integer> producer = model.<Integer>schedulerBuilder("producer")
                .withType(TaskSchedulerType.DIRECT)
                .build();
        final BindableInputWire<Integer, Integer> producerInput = producer.buildInputWire("ints");
        producerInput.bind(i -> i);

        final AtomicInteger inputCleanupCount = new AtomicInteger();
        final OutputWire<Integer> alwaysNull = producer.getOutputWire()
                .buildAdvancedTransformer(new AdvancedTransformationHelper<>(
                        "alwaysNull", i -> null, i -> inputCleanupCount.incrementAndGet(), null));

        final AtomicInteger receivedCount = new AtomicInteger();
        alwaysNull.solderTo("firstConsumer", "ints", i -> receivedCount.incrementAndGet());
        alwaysNull.solderTo("secondConsumer", "ints", i -> receivedCount.incrementAndGet());

        for (int i = 0; i < 10; i++) {
            producerInput.put(i);
        }

        assertEquals(0, receivedCount.get(), "no data should be forwarded when all transformations return null");
        assertEquals(10, inputCleanupCount.get(), "input cleanup should run exactly once per data item");
    }

    /**
     * Helper for building an {@link AdvancedTransformation}.
     *
     * @param name          the name of the transformer
     * @param transform     the transformation, called once per destination per data item. Null return values are not
     *                      forwarded.
     * @param inputCleanup  called on the original data after it has been forwarded to all destinations. Ignored if
     *                      null.
     * @param outputCleanup called on transformed data rejected by a destination. Ignored if null.
     * @param <A>           the input type of the transformer
     * @param <B>           the output type of the transformer
     */
    private record AdvancedTransformationHelper<A, B>(
            @NonNull String name,
            @NonNull Function<A, B> transform,
            @Nullable Consumer<A> inputCleanup,
            @Nullable Consumer<B> outputCleanup)
            implements AdvancedTransformation<A, B> {

        @Nullable
        @Override
        public B transform(@NonNull final A a) {
            return transform.apply(a);
        }

        @Override
        public void inputCleanup(@NonNull final A a) {
            if (inputCleanup != null) {
                inputCleanup.accept(a);
            }
        }

        @Override
        public void outputCleanup(@NonNull final B b) {
            if (outputCleanup != null) {
                outputCleanup.accept(b);
            }
        }

        @NonNull
        @Override
        public String getTransformerName() {
            return name;
        }

        @NonNull
        @Override
        public String getTransformerInputName() {
            return "transformer input";
        }
    }
}
