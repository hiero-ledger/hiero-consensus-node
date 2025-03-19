// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.component;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.swirlds.base.time.Time;
import com.swirlds.common.metrics.noop.NoOpMetrics;
import com.swirlds.component.framework.model.WiringModel;
import com.swirlds.component.framework.model.WiringModelBuilder;
import com.swirlds.component.framework.schedulers.builders.TaskSchedulerConfiguration;
import com.swirlds.component.framework.wires.input.InputWire;
import com.swirlds.component.framework.wires.output.OutputWire;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class ComponentWiringTests {

    private interface FooBarBaz {
        @NonNull
        Long handleFoo(@NonNull Integer foo);

        @InputWireLabel("bar")
        @NonNull
        Long handleBar(@NonNull Boolean bar);

        void handleBaz(@NonNull String baz);

        @InputWireLabel("trigger")
        @NonNull
        Long triggerQux();

        void triggerCorge();

        @InputWireLabel("data to be transformed")
        @SchedulerLabel("transformer")
        @NonNull
        default String transformer(@NonNull final Long baseOutput) {
            handleBar(true);
            return "" + baseOutput;
        }

        @InputWireLabel("data to be filtered")
        @SchedulerLabel("filter")
        default Boolean filter(@NonNull final Long baseOutput) {
            return baseOutput % 2 == 0;
        }
    }

    private static class FooBarBazImpl implements FooBarBaz {
        private long runningValue = 0;

        @Override
        @NonNull
        public Long handleFoo(@NonNull final Integer foo) {
            runningValue += foo;
            return runningValue;
        }

        @Override
        @NonNull
        public Long handleBar(@NonNull final Boolean bar) {
            runningValue *= bar ? 1 : -1;
            return runningValue;
        }

        @Override
        public void handleBaz(@NonNull final String baz) {
            runningValue *= baz.hashCode();
        }

        @Override
        @NonNull
        public Long triggerQux() {
            runningValue -= 1;
            return runningValue;
        }

        @Override
        public void triggerCorge() {
            runningValue *= 1.5;
        }

        public long getRunningValue() {
            return runningValue;
        }
    }

    @SchedulerLabel("actuallyCallThisSomethingDifferent")
    private interface ComponentWithListOutput {
        @NonNull
        List<String> handleInputA(@NonNull String s);

        @NonNull
        List<String> handleInputB(@NonNull Long l);

        @NonNull
        default Boolean filter(@NonNull final String baseOutput) {
            return baseOutput.hashCode() % 2 == 0;
        }

        @NonNull
        default String transformer(@NonNull final String baseOutput) {
            return "(" + baseOutput + ")";
        }
    }

    private static class ComponentWithListOutputImpl implements ComponentWithListOutput {

        @NonNull
        @Override
        public List<String> handleInputA(@NonNull final String s) {
            return List.of(s.split(""));
        }

        @NonNull
        @Override
        public List<String> handleInputB(@NonNull final Long l) {
            final String s = l.toString();
            // return a list of characters
            return List.of(s.split(""));
        }
    }

    /**
     * The framework should not permit methods that aren't on the component to be wired.
     */
    @Test
    void methodNotOnComponentTest() {
        final WiringModel wiringModel =
                WiringModelBuilder.create(new NoOpMetrics(), Time.getCurrent()).build();

        final TaskSchedulerConfiguration schedulerConfiguration = TaskSchedulerConfiguration.parse("DIRECT");

        final ComponentWiring<FooBarBaz, Long> fooBarBazWiring =
                new ComponentWiring<>(wiringModel, FooBarBaz.class, schedulerConfiguration);
        assertEquals("FooBarBaz", fooBarBazWiring.getSchedulerName());

        assertThrows(IllegalArgumentException.class, () -> fooBarBazWiring.getInputWire((x, y) -> 0L));
        assertThrows(IllegalArgumentException.class, () -> fooBarBazWiring.getInputWire((x, y) -> {}));
        assertThrows(IllegalArgumentException.class, () -> fooBarBazWiring.getInputWire((x) -> 1L));
        assertThrows(IllegalArgumentException.class, () -> fooBarBazWiring.getInputWire((x) -> {}));
        assertThrows(IllegalArgumentException.class, () -> fooBarBazWiring.getTransformedOutput((x, y) -> 0L));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void simpleComponentTest(final int bindLocation) {

        final WiringModel wiringModel =
                WiringModelBuilder.create(new NoOpMetrics(), Time.getCurrent()).build();

        final TaskSchedulerConfiguration schedulerConfiguration = TaskSchedulerConfiguration.parse("DIRECT");

        final ComponentWiring<FooBarBaz, Long> fooBarBazWiring =
                new ComponentWiring<>(wiringModel, FooBarBaz.class, schedulerConfiguration);
        assertEquals("FooBarBaz", fooBarBazWiring.getSchedulerName());

        final FooBarBazImpl fooBarBazImpl = new FooBarBazImpl();

        if (bindLocation == 0) {
            fooBarBazWiring.bind(fooBarBazImpl);
        }

        final InputWire<Integer> fooInput = fooBarBazWiring.getInputWire(FooBarBaz::handleFoo);
        assertEquals("handleFoo", fooInput.getName());
        final InputWire<Boolean> barInput = fooBarBazWiring.getInputWire(FooBarBaz::handleBar);
        assertEquals("bar", barInput.getName());

        if (bindLocation == 1) {
            fooBarBazWiring.bind(fooBarBazImpl);
        }

        final InputWire<String> bazInput = fooBarBazWiring.getInputWire(FooBarBaz::handleBaz);
        assertEquals("handleBaz", bazInput.getName());
        final InputWire<Void> triggerQux = fooBarBazWiring.getInputWire(FooBarBaz::triggerQux);
        assertEquals("trigger", triggerQux.getName());
        final InputWire<Void> triggerCorge = fooBarBazWiring.getInputWire(FooBarBaz::triggerCorge);
        assertEquals("triggerCorge", triggerCorge.getName());

        final OutputWire<Long> output = fooBarBazWiring.getOutputWire();

        if (bindLocation == 2) {
            fooBarBazWiring.bind(fooBarBazImpl);
        }

        final AtomicLong outputValue = new AtomicLong();
        output.solderTo("outputHandler", "output", outputValue::set);

        // Getting the same input wire multiple times should yield the same instance
        assertSame(fooInput, fooBarBazWiring.getInputWire(FooBarBaz::handleFoo));
        assertSame(barInput, fooBarBazWiring.getInputWire(FooBarBaz::handleBar));
        assertSame(bazInput, fooBarBazWiring.getInputWire(FooBarBaz::handleBaz));
        assertSame(triggerQux, fooBarBazWiring.getInputWire(FooBarBaz::triggerQux));
        assertSame(triggerCorge, fooBarBazWiring.getInputWire(FooBarBaz::triggerCorge));

        // Getting the output wire multiple times should yield the same instance
        assertSame(output, fooBarBazWiring.getOutputWire());

        if (bindLocation == 3) {
            fooBarBazWiring.bind(fooBarBazImpl);
        }

        long expectedRunningValue = 0;
        for (int i = 0; i < 1000; i++) {
            if (i % 5 == 0) {
                expectedRunningValue += i;
                fooInput.put(i);
                assertEquals(expectedRunningValue, fooBarBazImpl.getRunningValue());
                assertEquals(expectedRunningValue, outputValue.get());
            } else if (i % 5 == 1) {
                final boolean choice = i % 7 == 0;
                expectedRunningValue *= choice ? 1 : -1;
                barInput.put(choice);
                assertEquals(expectedRunningValue, fooBarBazImpl.getRunningValue());
                assertEquals(expectedRunningValue, outputValue.get());
            } else if (i % 5 == 2) {
                final String value = "value" + i;
                expectedRunningValue *= value.hashCode();
                bazInput.put(value);
                assertEquals(expectedRunningValue, fooBarBazImpl.getRunningValue());
            } else if (i % 5 == 3) {
                expectedRunningValue -= 1;
                triggerQux.put(null);
                assertEquals(expectedRunningValue, fooBarBazImpl.getRunningValue());
            } else {
                expectedRunningValue *= 1.5;
                triggerCorge.put(null);
                assertEquals(expectedRunningValue, fooBarBazImpl.getRunningValue());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void transformerTest(final int bindLocation) {

        final WiringModel wiringModel =
                WiringModelBuilder.create(new NoOpMetrics(), Time.getCurrent()).build();

        final TaskSchedulerConfiguration schedulerConfiguration = TaskSchedulerConfiguration.parse("DIRECT");

        final FooBarBazImpl fooBarBazImpl = new FooBarBazImpl();

        final ComponentWiring<FooBarBaz, Long> fooBarBazWiring =
                new ComponentWiring<>(wiringModel, FooBarBaz.class, schedulerConfiguration);
        assertEquals("FooBarBaz", fooBarBazWiring.getSchedulerName());

        if (bindLocation == 0) {
            fooBarBazWiring.bind(fooBarBazImpl);
        }

        final InputWire<Integer> fooInput = fooBarBazWiring.getInputWire(FooBarBaz::handleFoo);
        final InputWire<Boolean> barInput = fooBarBazWiring.getInputWire(FooBarBaz::handleBar);
        final InputWire<String> bazInput = fooBarBazWiring.getInputWire(FooBarBaz::handleBaz);
        final InputWire<Void> triggerQux = fooBarBazWiring.getInputWire(FooBarBaz::triggerQux);
        final InputWire<Void> triggerCorge = fooBarBazWiring.getInputWire(FooBarBaz::triggerCorge);

        final OutputWire<String> output = fooBarBazWiring.getTransformedOutput(FooBarBaz::transformer);

        // Getting the same transformer multiple times should yield the same instance
        assertSame(output, fooBarBazWiring.getTransformedOutput(FooBarBaz::transformer));

        if (bindLocation == 1) {
            fooBarBazWiring.bind(fooBarBazImpl);
        }

        final AtomicReference<String> outputValue = new AtomicReference<>("0");
        output.solderTo("outputHandler", "output", outputValue::set);

        long expectedRunningValue = 0;
        for (int i = 0; i < 1000; i++) {
            if (i % 5 == 0) {
                expectedRunningValue += i;
                fooInput.put(i);
                assertEquals(expectedRunningValue, fooBarBazImpl.getRunningValue());
                assertEquals("" + expectedRunningValue, outputValue.get());
            } else if (i % 5 == 1) {
                final boolean choice = i % 7 == 0;
                expectedRunningValue *= choice ? 1 : -1;
                barInput.put(choice);
                assertEquals(expectedRunningValue, fooBarBazImpl.getRunningValue());
                assertEquals("" + expectedRunningValue, outputValue.get());
            } else if (i % 5 == 2) {
                final String value = "value" + i;
                expectedRunningValue *= value.hashCode();
                bazInput.put(value);
                assertEquals(expectedRunningValue, fooBarBazImpl.getRunningValue());
            } else if (i % 5 == 3) {
                expectedRunningValue -= 1;
                triggerQux.put(null);
                assertEquals(expectedRunningValue, fooBarBazImpl.getRunningValue());
                assertEquals("" + expectedRunningValue, outputValue.get());
            } else {
                expectedRunningValue *= 1.5;
                triggerCorge.put(null);
                assertEquals(expectedRunningValue, fooBarBazImpl.getRunningValue());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void filterTest(final int bindLocation) {

        final WiringModel wiringModel =
                WiringModelBuilder.create(new NoOpMetrics(), Time.getCurrent()).build();

        final TaskSchedulerConfiguration schedulerConfiguration = TaskSchedulerConfiguration.parse("DIRECT");

        final FooBarBazImpl fooBarBazImpl = new FooBarBazImpl();

        final ComponentWiring<FooBarBaz, Long> fooBarBazWiring =
                new ComponentWiring<>(wiringModel, FooBarBaz.class, schedulerConfiguration);
        assertEquals("FooBarBaz", fooBarBazWiring.getSchedulerName());

        if (bindLocation == 0) {
            fooBarBazWiring.bind(fooBarBazImpl);
        }

        final InputWire<Integer> fooInput = fooBarBazWiring.getInputWire(FooBarBaz::handleFoo);
        final InputWire<Boolean> barInput = fooBarBazWiring.getInputWire(FooBarBaz::handleBar);
        final InputWire<String> bazInput = fooBarBazWiring.getInputWire(FooBarBaz::handleBaz);
        final InputWire<Void> triggerQux = fooBarBazWiring.getInputWire(FooBarBaz::triggerQux);
        final InputWire<Void> triggerCorge = fooBarBazWiring.getInputWire(FooBarBaz::triggerCorge);

        final OutputWire<Long> output = fooBarBazWiring.getFilteredOutput(FooBarBaz::filter);

        // Getting the same filter multiple times should yield the same instance
        assertSame(output, fooBarBazWiring.getFilteredOutput(FooBarBaz::filter));

        if (bindLocation == 1) {
            fooBarBazWiring.bind(fooBarBazImpl);
        }

        final AtomicReference<Long> outputValue = new AtomicReference<>();
        output.solderTo("outputHandler", "output", outputValue::set);

        long expectedRunningValue = 0;
        for (int i = 0; i < 1000; i++) {
            outputValue.set(null);
            if (i % 5 == 0) {
                expectedRunningValue += i;
                fooInput.put(i);
                assertEquals(expectedRunningValue, fooBarBazImpl.getRunningValue());
                final Long expectedValue = expectedRunningValue % 2 == 0 ? expectedRunningValue : null;
                assertEquals(expectedValue, outputValue.get());
            } else if (i % 5 == 1) {
                final boolean choice = i % 7 == 0;
                expectedRunningValue *= choice ? 1 : -1;
                barInput.put(choice);
                final Long expectedValue = expectedRunningValue % 2 == 0 ? expectedRunningValue : null;
                assertEquals(expectedValue, outputValue.get());
            } else if (i % 5 == 2) {
                final String value = "value" + i;
                expectedRunningValue *= value.hashCode();
                bazInput.put(value);
                assertEquals(expectedRunningValue, fooBarBazImpl.getRunningValue());
            } else if (i % 5 == 3) {
                expectedRunningValue -= 1;
                triggerQux.put(null);
                final Long expectedValue = expectedRunningValue % 2 == 0 ? expectedRunningValue : null;
                assertEquals(expectedValue, outputValue.get());
            } else {
                expectedRunningValue *= 1.5;
                triggerCorge.put(null);
                assertEquals(expectedRunningValue, fooBarBazImpl.getRunningValue());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void splitterTest(final int bindLocation) {

        final WiringModel wiringModel =
                WiringModelBuilder.create(new NoOpMetrics(), Time.getCurrent()).build();

        final TaskSchedulerConfiguration schedulerConfiguration = TaskSchedulerConfiguration.parse("DIRECT");

        final ComponentWiring<ComponentWithListOutput, List<String>> componentWiring =
                new ComponentWiring<>(wiringModel, ComponentWithListOutput.class, schedulerConfiguration);
        assertEquals("actuallyCallThisSomethingDifferent", componentWiring.getSchedulerName());

        if (bindLocation == 0) {
            componentWiring.bind(new ComponentWithListOutputImpl());
        }

        final OutputWire<String> splitOutput = componentWiring.getSplitOutput();
        assertSame(splitOutput, componentWiring.getSplitOutput());

        final List<String> outputData = new ArrayList<>();
        splitOutput.solderTo("addToOutputData", "split data", outputData::add);

        final List<String> expectedOutputData = new ArrayList<>();

        if (bindLocation == 1) {
            componentWiring.bind(new ComponentWithListOutputImpl());
        }

        componentWiring.getInputWire(ComponentWithListOutput::handleInputA).put("hello world");
        expectedOutputData.addAll(List.of("h", "e", "l", "l", "o", " ", "w", "o", "r", "l", "d"));

        componentWiring.getInputWire(ComponentWithListOutput::handleInputB).put(123L);
        expectedOutputData.addAll(List.of("1", "2", "3"));

        assertEquals(expectedOutputData, outputData);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void filteredSplitterTest(final int bindLocation) {

        final WiringModel wiringModel =
                WiringModelBuilder.create(new NoOpMetrics(), Time.getCurrent()).build();

        final TaskSchedulerConfiguration schedulerConfiguration = TaskSchedulerConfiguration.parse("DIRECT");

        final ComponentWiring<ComponentWithListOutput, List<String>> componentWiring =
                new ComponentWiring<>(wiringModel, ComponentWithListOutput.class, schedulerConfiguration);
        assertEquals("actuallyCallThisSomethingDifferent", componentWiring.getSchedulerName());

        if (bindLocation == 0) {
            componentWiring.bind(new ComponentWithListOutputImpl());
        }

        final OutputWire<String> filteredOutput =
                componentWiring.getSplitAndFilteredOutput(ComponentWithListOutput::filter);
        assertSame(filteredOutput, componentWiring.getSplitAndFilteredOutput(ComponentWithListOutput::filter));

        final List<String> outputData = new ArrayList<>();
        filteredOutput.solderTo("addToOutputData", "split data", outputData::add);

        final List<String> expectedOutputData = new ArrayList<>();

        if (bindLocation == 1) {
            componentWiring.bind(new ComponentWithListOutputImpl());
        }

        componentWiring.getInputWire(ComponentWithListOutput::handleInputA).put("hello world");
        for (final String s : "hello world".split("")) {
            if (s.hashCode() % 2 == 0) {
                expectedOutputData.add(s);
            }
        }

        componentWiring.getInputWire(ComponentWithListOutput::handleInputB).put(123L);
        for (final String s : "123".split("")) {
            if (s.hashCode() % 2 == 0) {
                expectedOutputData.add(s);
            }
        }

        assertEquals(expectedOutputData, outputData);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void transformedSplitterTest(final int bindLocation) {

        final WiringModel wiringModel =
                WiringModelBuilder.create(new NoOpMetrics(), Time.getCurrent()).build();

        final TaskSchedulerConfiguration schedulerConfiguration = TaskSchedulerConfiguration.parse("DIRECT");

        final ComponentWiring<ComponentWithListOutput, List<String>> componentWiring =
                new ComponentWiring<>(wiringModel, ComponentWithListOutput.class, schedulerConfiguration);
        assertEquals("actuallyCallThisSomethingDifferent", componentWiring.getSchedulerName());

        if (bindLocation == 0) {
            componentWiring.bind(new ComponentWithListOutputImpl());
        }

        final OutputWire<String> transformedOutput =
                componentWiring.getSplitAndTransformedOutput(ComponentWithListOutput::transformer);
        assertSame(
                transformedOutput, componentWiring.getSplitAndTransformedOutput(ComponentWithListOutput::transformer));

        final List<String> outputData = new ArrayList<>();
        transformedOutput.solderTo("addToOutputData", "split data", outputData::add);

        final List<String> expectedOutputData = new ArrayList<>();

        if (bindLocation == 1) {
            componentWiring.bind(new ComponentWithListOutputImpl());
        }

        componentWiring.getInputWire(ComponentWithListOutput::handleInputA).put("hello world");
        for (final String s : "hello world".split("")) {
            expectedOutputData.add("(" + s + ")");
        }

        componentWiring.getInputWire(ComponentWithListOutput::handleInputB).put(123L);
        for (final String s : "123".split("")) {
            expectedOutputData.add("(" + s + ")");
        }

        assertEquals(expectedOutputData, outputData);
    }
}
