// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.component;

import static com.swirlds.component.framework.schedulers.builders.TaskSchedulerConfiguration.DIRECT_CONFIGURATION;
import static org.hiero.consensus.utility.test.fixtures.RandomUtils.getRandomPrintSeed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.swirlds.base.time.Time;
import com.swirlds.common.metrics.noop.NoOpMetrics;
import com.swirlds.component.framework.model.WiringModel;
import com.swirlds.component.framework.model.WiringModelBuilder;
import com.swirlds.component.framework.transformers.RoutableData;
import com.swirlds.component.framework.wires.output.OutputWire;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ComponentWiringRouterTests {

    private enum TestDataType {
        FOO, // Long values
        BAR, // Long values
        BAZ; // Boolean values

        /**
         * Create a new {@link RoutableData} object with the given data.
         *
         * @param data the data
         * @return the new {@link RoutableData} object
         */
        @NonNull
        public RoutableData<TestDataType> of(@NonNull final Object data) {
            return new RoutableData<>(this, data);
        }
    }

    private enum TestDataType2 {
        FOO, // Long values
        BAR, // Long values
        BAZ; // Boolean values

        /**
         * Create a new {@link RoutableData} object with the given data.
         *
         * @param data the data
         * @return the new {@link RoutableData} object
         */
        @NonNull
        public RoutableData<TestDataType2> of(@NonNull final Object data) {
            return new RoutableData<>(this, data);
        }
    }

    private interface TestComponent {
        @NonNull
        RoutableData<TestDataType> doWork(@NonNull Integer input);
    }

    private static class TestComponentImpl implements TestComponent {
        @NonNull
        @Override
        public RoutableData<TestDataType> doWork(@NonNull final Integer input) {
            if (input % 3 == 0) {
                return TestDataType.FOO.of(input.longValue());
            } else if (input % 3 == 1) {
                return TestDataType.BAR.of(input.longValue());
            } else {
                return TestDataType.BAZ.of(input % 2 == 0);
            }
        }
    }

    private interface TestListComponent {
        @NonNull
        List<RoutableData<TestDataType>> doWork(@NonNull Integer input);
    }

    private static class TestListComponentImpl implements TestListComponent {
        @NonNull
        @Override
        public List<RoutableData<TestDataType>> doWork(@NonNull final Integer input) {

            final List<RoutableData<TestDataType>> output = new ArrayList<>();

            if (input % 3 == 0) {
                output.add(TestDataType.FOO.of(input.longValue()));
            } else if (input % 3 == 1) {
                output.add(TestDataType.BAR.of(input.longValue()));
            } else {
                output.add(TestDataType.BAZ.of(input % 2 == 0));
            }

            if (input % 2 == 0) {
                output.add(TestDataType.FOO.of(input.longValue() / 2));
            } else {
                output.add(TestDataType.BAR.of(input.longValue() / 2));
            }

            if (input % 5 == 0) {
                output.add(TestDataType.FOO.of(input.longValue() / 5));
            }

            return output;
        }
    }

    @Test
    void basicBehaviorTest() {
        final Random random = getRandomPrintSeed();

        final WiringModel model =
                WiringModelBuilder.create(new NoOpMetrics(), Time.getCurrent()).build();

        final ComponentWiring<TestComponent, RoutableData<TestDataType>> wiring =
                new ComponentWiring<>(model, TestComponent.class, DIRECT_CONFIGURATION);

        wiring.bind(new TestComponentImpl());

        final AtomicLong latestFoo = new AtomicLong();
        final AtomicLong latestBar = new AtomicLong();
        final AtomicBoolean latestBaz = new AtomicBoolean();

        final OutputWire<Long> fooOutput = wiring.getRoutedOutput(TestDataType.FOO);
        final OutputWire<Long> barOutput = wiring.getRoutedOutput(TestDataType.BAR);
        final OutputWire<Boolean> bazOutput = wiring.getRoutedOutput(TestDataType.BAZ);

        fooOutput.solderTo("fooHandler", "fooInput", latestFoo::set);
        barOutput.solderTo("barHandler", "barInput", latestBar::set);
        bazOutput.solderTo("bazHandler", "bazInput", latestBaz::set);

        long expectedFoo = 0;
        long expectedBar = 0;
        boolean expectedBaz = false;

        // Intentional: we have to create all wires prior to starting the model
        wiring.getInputWire(TestComponent::doWork);

        model.start();

        for (int i = 0; i < 1000; i++) {
            final int value = random.nextInt();
            if (value % 3 == 0) {
                expectedFoo = value;
            } else if (value % 3 == 1) {
                expectedBar = value;
            } else {
                expectedBaz = value % 2 == 0;
            }
            wiring.getInputWire(TestComponent::doWork).put(value);

            assertEquals(expectedFoo, latestFoo.get());
            assertEquals(expectedBar, latestBar.get());
            assertEquals(expectedBaz, latestBaz.get());
        }
    }

    @Test
    void basicSplitBehaviorTest() {
        final Random random = getRandomPrintSeed();

        final WiringModel model =
                WiringModelBuilder.create(new NoOpMetrics(), Time.getCurrent()).build();

        final ComponentWiring<TestListComponent, List<RoutableData<TestDataType>>> wiring =
                new ComponentWiring<>(model, TestListComponent.class, DIRECT_CONFIGURATION);

        wiring.bind(new TestListComponentImpl());

        final AtomicLong latestFoo = new AtomicLong();
        final AtomicLong latestBar = new AtomicLong();
        final AtomicBoolean latestBaz = new AtomicBoolean();

        final OutputWire<Long> fooOutput = wiring.getSplitAndRoutedOutput(TestDataType.FOO);
        final OutputWire<Long> barOutput = wiring.getSplitAndRoutedOutput(TestDataType.BAR);
        final OutputWire<Boolean> bazOutput = wiring.getSplitAndRoutedOutput(TestDataType.BAZ);

        fooOutput.solderTo("fooHandler", "fooInput", latestFoo::getAndAdd);
        barOutput.solderTo("barHandler", "barInput", latestBar::getAndAdd);
        bazOutput.solderTo("bazHandler", "bazInput", latestBaz::set);

        long expectedFoo = 0;
        long expectedBar = 0;
        boolean expectedBaz = false;

        // Intentional: we have to create all wires prior to starting the model
        wiring.getInputWire(TestListComponent::doWork);

        model.start();

        for (int i = 0; i < 1000; i++) {
            final int value = random.nextInt();

            if (value % 3 == 0) {
                expectedFoo += value;
            } else if (value % 3 == 1) {
                expectedBar += value;
            } else {
                expectedBaz = value % 2 == 0;
            }

            if (value % 2 == 0) {
                expectedFoo += value / 2;
            } else {
                expectedBar += value / 2;
            }

            if (value % 5 == 0) {
                expectedFoo += value / 5;
            }

            wiring.getInputWire(TestListComponent::doWork).put(value);

            assertEquals(expectedFoo, latestFoo.get());
            assertEquals(expectedBar, latestBar.get());
            assertEquals(expectedBaz, latestBaz.get());
        }
    }

    /**
     * It is not allowed to create multiple routers with different enum types from the same component.
     */
    @Test
    void multipleRoutersForbiddenTest() {
        final WiringModel model =
                WiringModelBuilder.create(new NoOpMetrics(), Time.getCurrent()).build();

        final ComponentWiring<TestComponent, RoutableData<TestDataType>> wiring =
                new ComponentWiring<>(model, TestComponent.class, DIRECT_CONFIGURATION);

        wiring.getRoutedOutput(TestDataType.FOO);

        assertThrows(IllegalArgumentException.class, () -> wiring.getRoutedOutput(TestDataType2.FOO));
    }

    /**
     * It is not allowed to create multiple routers with different enum types from the same component.
     */
    @Test
    void multipleSplitRoutersForbiddenTest() {
        final WiringModel model =
                WiringModelBuilder.create(new NoOpMetrics(), Time.getCurrent()).build();

        final ComponentWiring<TestListComponent, RoutableData<TestDataType>> wiring =
                new ComponentWiring<>(model, TestListComponent.class, DIRECT_CONFIGURATION);

        wiring.getRoutedOutput(TestDataType.FOO);

        assertThrows(IllegalArgumentException.class, () -> wiring.getRoutedOutput(TestDataType2.FOO));
    }

    /**
     * We shouldn't be able to create a router that uses unsplit data, followed by creating a router that uses split
     * data.
     */
    @Test
    void unsplitThenSplitRoutersForbiddenTest() {
        final WiringModel model =
                WiringModelBuilder.create(new NoOpMetrics(), Time.getCurrent()).build();

        final ComponentWiring<TestComponent, RoutableData<TestDataType>> wiring =
                new ComponentWiring<>(model, TestComponent.class, DIRECT_CONFIGURATION);

        wiring.getRoutedOutput(TestDataType.FOO);

        assertThrows(IllegalStateException.class, () -> wiring.getSplitAndRoutedOutput(TestDataType2.FOO));
    }

    /**
     * We shouldn't be able to create a router that uses split data, followed by creating a router that uses unsplit
     * data.
     */
    @Test
    void splitThenUnsplitRoutersForbiddenTest() {
        final WiringModel model =
                WiringModelBuilder.create(new NoOpMetrics(), Time.getCurrent()).build();

        final ComponentWiring<TestListComponent, RoutableData<TestDataType>> wiring =
                new ComponentWiring<>(model, TestListComponent.class, DIRECT_CONFIGURATION);

        wiring.getSplitAndRoutedOutput(TestDataType.FOO);

        assertThrows(IllegalStateException.class, () -> wiring.getRoutedOutput(TestDataType2.FOO));
    }
}
