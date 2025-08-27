// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.component;

import com.swirlds.base.time.Time;
import com.swirlds.common.metrics.noop.NoOpMetrics;
import com.swirlds.component.framework.model.WiringModel;
import com.swirlds.component.framework.model.WiringModelBuilder;
import com.swirlds.component.framework.schedulers.TaskScheduler;
import com.swirlds.component.framework.schedulers.builders.TaskSchedulerType;
import com.swirlds.component.framework.wires.input.BindableInputWire;
import com.swirlds.component.framework.wires.input.InputWire;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

@Disabled // Do not merge with this class enabled
class WiringComponentPerformanceTests {

    private interface SimpleComponent {
        void handleInput(@NonNull Long input);
    }

    private static class SimpleComponentImpl implements SimpleComponent {
        private long runningValue = 0;

        @Override
        public void handleInput(@NonNull final Long input) {
            runningValue += input;
        }

        public long getRunningValue() {
            return runningValue;
        }
    }

    @NonNull
    private InputWire<Long> buildOldStyleComponent(@NonNull final SimpleComponent component) {
        final WiringModel model =
                WiringModelBuilder.create(new NoOpMetrics(), Time.getCurrent()).build();

        final TaskScheduler scheduler = model.schedulerBuilder("test")
                .withType(TaskSchedulerType.DIRECT)
                .build();

        final BindableInputWire<Long, Void> inputWire = scheduler.buildInputWire("input");
        inputWire.bindConsumer(component::handleInput);

        return inputWire;
    }

    @NonNull
    private InputWire<Long> buildAutomaticComponent(@NonNull final SimpleComponent component) {

        final WiringModel model =
                WiringModelBuilder.create(new NoOpMetrics(), Time.getCurrent()).build();

        final TaskScheduler<Void> scheduler = model.<Void>schedulerBuilder("test")
                .withType(TaskSchedulerType.DIRECT)
                .build();

        final ComponentWiring<SimpleComponent, Void> componentWiring =
                new ComponentWiring<>(model, SimpleComponent.class, scheduler);
        final InputWire<Long> inputWire = componentWiring.getInputWire(SimpleComponent::handleInput);
        componentWiring.bind(component);

        return inputWire;
    }

    // When testing locally on my macbook (m1), the old style component took 0.76s to run 100,000,000 iterations,
    // and the automatic component took 0.79s to run 100,000,000 iterations.

    @Test
    void oldStylePerformanceTest() {
        final long iterations = 100_000_000;

        final SimpleComponentImpl component = new SimpleComponentImpl();
        final InputWire<Long> inputWire = buildOldStyleComponent(component);

        final Instant start = Instant.now();

        for (long i = 0; i < iterations; i++) {
            inputWire.put(i);
        }

        final Instant end = Instant.now();
        final Duration duration = Duration.between(start, end);
        System.out.println("Time required: " + duration.toMillis() + "ms");

        // Just in case the compiler wants to get cheeky and avoid doing computation
        System.out.println("value = " + component.getRunningValue());
    }

    @Test
    void automaticComponentPerformanceTest() {
        final long iterations = 100_000_000;

        final SimpleComponentImpl component = new SimpleComponentImpl();
        final InputWire<Long> inputWire = buildAutomaticComponent(component);

        final Instant start = Instant.now();

        for (long i = 0; i < iterations; i++) {
            inputWire.put(i);
        }

        final Instant end = Instant.now();
        final Duration duration = Duration.between(start, end);
        System.out.println("Time required: " + duration.toMillis() + "ms");

        // Just in case the compiler wants to get cheeky and avoid doing computation
        System.out.println("value = " + component.getRunningValue());
    }
}
