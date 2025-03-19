// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.model;

import static com.swirlds.component.framework.schedulers.builders.TaskSchedulerBuilder.UNLIMITED_CAPACITY;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.common.metrics.noop.NoOpMetrics;
import com.swirlds.component.framework.schedulers.TaskScheduler;
import com.swirlds.component.framework.wires.input.BindableInputWire;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

public class DeterministicHeartbeatSchedulerTests {

    @Test
    void heartbeatByFrequencyTest() {
        final FakeTime time = new FakeTime();
        final DeterministicWiringModel model = WiringModelBuilder.create(new NoOpMetrics(), time)
                .withDeterministicModeEnabled(true)
                .build();

        final TaskScheduler<Void> scheduler =
                model.<Void>schedulerBuilder("test").build();

        final BindableInputWire<Instant, Void> heartbeatBindable = scheduler.buildInputWire("heartbeat");
        model.buildHeartbeatWire(100).solderTo(heartbeatBindable);

        final AtomicLong counter = new AtomicLong(0);
        heartbeatBindable.bindConsumer((now) -> counter.incrementAndGet());

        model.start();
        int milliseconds = 0;
        while (milliseconds <= 1000) {
            time.tick(Duration.ofMillis(1));
            milliseconds += 1;
            model.tick();
        }
        model.stop();

        assertEquals(100, counter.get());
    }

    @Test
    void heartbeatByPeriodTest() {
        final FakeTime time = new FakeTime();
        final DeterministicWiringModel model = WiringModelBuilder.create(new NoOpMetrics(), time)
                .withDeterministicModeEnabled(true)
                .build();

        final TaskScheduler<Void> scheduler =
                model.<Void>schedulerBuilder("test").build();

        final BindableInputWire<Instant, Void> heartbeatBindable = scheduler.buildInputWire("heartbeat");
        model.buildHeartbeatWire(Duration.ofMillis(10)).solderTo(heartbeatBindable);

        final AtomicLong counter = new AtomicLong(0);
        heartbeatBindable.bindConsumer((now) -> counter.incrementAndGet());

        model.start();
        int milliseconds = 0;
        while (milliseconds <= 1000) {
            time.tick(Duration.ofMillis(1));
            milliseconds += 1;
            model.tick();
        }
        model.stop();

        assertEquals(100, counter.get());
    }

    @Test
    void heartbeatsAtDifferentRates() {
        final FakeTime time = new FakeTime();
        final DeterministicWiringModel model = WiringModelBuilder.create(new NoOpMetrics(), time)
                .withDeterministicModeEnabled(true)
                .build();

        final TaskScheduler<Void> scheduler = model.<Void>schedulerBuilder("test")
                .withUnhandledTaskCapacity(UNLIMITED_CAPACITY)
                .build();

        final BindableInputWire<Instant, Void> heartbeatBindableA = scheduler.buildInputWire("heartbeatA");
        final BindableInputWire<Instant, Void> heartbeatBindableB = scheduler.buildInputWire("heartbeatB");
        final BindableInputWire<Instant, Void> heartbeatBindableC = scheduler.buildInputWire("heartbeatC");
        final BindableInputWire<Instant, Void> heartbeatBindableD = scheduler.buildInputWire("heartbeatD");

        model.buildHeartbeatWire(100).solderTo(heartbeatBindableA);
        model.buildHeartbeatWire(Duration.ofMillis(5)).solderTo(heartbeatBindableB);
        model.buildHeartbeatWire(Duration.ofMillis(50)).solderTo(heartbeatBindableC);
        model.buildHeartbeatWire(Duration.ofMillis(50)).solderTo(heartbeatBindableD);

        final AtomicLong counterA = new AtomicLong(0);
        heartbeatBindableA.bindConsumer((now) -> counterA.incrementAndGet());

        final AtomicLong counterB = new AtomicLong(0);
        heartbeatBindableB.bindConsumer((now) -> counterB.incrementAndGet());

        final AtomicLong counterC = new AtomicLong(0);
        heartbeatBindableC.bindConsumer((now) -> counterC.incrementAndGet());

        final AtomicLong counterD = new AtomicLong(0);
        heartbeatBindableD.bindConsumer((now) -> counterD.incrementAndGet());

        model.start();
        int milliseconds = 0;
        while (milliseconds <= 1000) {
            time.tick(Duration.ofMillis(1));
            milliseconds += 1;
            model.tick();
        }
        model.stop();

        assertEquals(100, counterA.get());
        assertEquals(200, counterB.get());
        assertEquals(20, counterC.get());
        assertEquals(20, counterD.get());
    }
}
