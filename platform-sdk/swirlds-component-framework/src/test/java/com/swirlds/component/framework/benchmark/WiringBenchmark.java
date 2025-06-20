// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.benchmark;

import static java.util.concurrent.ForkJoinPool.defaultForkJoinWorkerThreadFactory;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.base.time.Time;
import com.swirlds.common.metrics.noop.NoOpMetrics;
import com.swirlds.component.framework.counters.BackpressureObjectCounter;
import com.swirlds.component.framework.counters.ObjectCounter;
import com.swirlds.component.framework.model.WiringModel;
import com.swirlds.component.framework.model.WiringModelBuilder;
import com.swirlds.component.framework.schedulers.TaskScheduler;
import com.swirlds.component.framework.schedulers.builders.TaskSchedulerType;
import com.swirlds.component.framework.wires.input.BindableInputWire;
import java.time.Duration;
import java.util.concurrent.ForkJoinPool;

class WiringBenchmark {

    /* Data flow for this benchmark:

    gossip -> event verifier -> orphan buffer
      ^                               |
      |                               |
      ---------------------------------

     */

    static void basicBenchmark() throws InterruptedException {

        // We will use this executor for starting all threads. Maybe we should only use it for temporary threads?
        final ForkJoinPool executor = new ForkJoinPool(
                Runtime.getRuntime().availableProcessors(),
                defaultForkJoinWorkerThreadFactory,
                (t, e) -> {
                    System.out.println("Uncaught exception in thread " + t.getName());
                    e.printStackTrace();
                },
                true);

        final WiringModel model =
                WiringModelBuilder.create(new NoOpMetrics(), Time.getCurrent()).build();

        // Ensures that we have no more than 10,000 events in the pipeline at any given time
        final ObjectCounter backpressure = new BackpressureObjectCounter("backpressure", 10_000, Duration.ZERO);

        final TaskScheduler<WiringBenchmarkEvent> verificationTaskScheduler = model.<WiringBenchmarkEvent>
                        schedulerBuilder("verification")
                .withPool(executor)
                .withType(TaskSchedulerType.CONCURRENT)
                .withOnRamp(backpressure)
                .withExternalBackPressure(true)
                .build();

        final TaskScheduler<WiringBenchmarkEvent> orphanBufferTaskScheduler = model.<WiringBenchmarkEvent>
                        schedulerBuilder("orphanBuffer")
                .withPool(executor)
                .withType(TaskSchedulerType.SEQUENTIAL)
                .withExternalBackPressure(true)
                .build();

        final TaskScheduler<Void> eventPoolTaskScheduler = model.<Void>schedulerBuilder("eventPool")
                .withPool(executor)
                .withType(TaskSchedulerType.SEQUENTIAL)
                .withOffRamp(backpressure)
                .withExternalBackPressure(true)
                .build();

        final BindableInputWire<WiringBenchmarkEvent, WiringBenchmarkEvent> eventsToOrphanBuffer =
                orphanBufferTaskScheduler.buildInputWire("unordered events");

        final BindableInputWire<WiringBenchmarkEvent, WiringBenchmarkEvent> eventsToBeVerified =
                verificationTaskScheduler.buildInputWire("unverified events");

        final BindableInputWire<WiringBenchmarkEvent, Void> eventsToInsertBackIntoEventPool =
                eventPoolTaskScheduler.buildInputWire("verified events");

        verificationTaskScheduler.getOutputWire().solderTo(eventsToOrphanBuffer);
        orphanBufferTaskScheduler.getOutputWire().solderTo(eventsToInsertBackIntoEventPool);

        final WiringBenchmarkEventPool eventPool = new WiringBenchmarkEventPool();
        final WiringBenchmarkTopologicalEventSorter orphanBuffer = new WiringBenchmarkTopologicalEventSorter();
        final WiringBenchmarkEventVerifier verifier = new WiringBenchmarkEventVerifier();
        final WiringBenchmarkGossip gossip = new WiringBenchmarkGossip(executor, eventPool, eventsToBeVerified::put);

        eventsToOrphanBuffer.bind(orphanBuffer);
        eventsToBeVerified.bind(verifier);
        eventsToInsertBackIntoEventPool.bindConsumer(eventPool::checkin);

        // Create a user thread for running "gossip". It will continue to generate events until explicitly stopped.
        System.out.println("Starting gossip");
        gossip.start();
        SECONDS.sleep(120);
        gossip.stop();

        // Validate that all events have been seen by orphanBuffer
        final long timeout = System.currentTimeMillis() + 1000;
        boolean success = false;
        while (System.currentTimeMillis() < timeout) {
            if (orphanBuffer.getCheckSum() == gossip.getCheckSum()) {
                success = true;
                break;
            }
        }
        assertTrue(success);
    }

    public static void main(String[] args) {
        try {
            basicBenchmark();
        } catch (final Throwable t) {
            t.printStackTrace();
        }
    }
}
