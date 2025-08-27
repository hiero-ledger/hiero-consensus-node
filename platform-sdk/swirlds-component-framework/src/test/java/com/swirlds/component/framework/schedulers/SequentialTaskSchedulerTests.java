// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.schedulers;

import static com.swirlds.component.framework.schedulers.builders.TaskSchedulerBuilder.UNLIMITED_CAPACITY;
import static org.hiero.base.utility.NonCryptographicHashing.hash32;
import static org.hiero.base.utility.test.fixtures.RandomUtils.getRandomPrintSeed;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.swirlds.base.time.Time;
import com.swirlds.common.metrics.noop.NoOpMetrics;
import com.swirlds.common.test.fixtures.AssertionUtils;
import com.swirlds.component.framework.TestWiringModelBuilder;
import com.swirlds.component.framework.counters.BackpressureObjectCounter;
import com.swirlds.component.framework.counters.ObjectCounter;
import com.swirlds.component.framework.model.WiringModel;
import com.swirlds.component.framework.model.WiringModelBuilder;
import com.swirlds.component.framework.schedulers.builders.TaskSchedulerType;
import com.swirlds.component.framework.wires.SolderType;
import com.swirlds.component.framework.wires.input.BindableInputWire;
import com.swirlds.component.framework.wires.output.StandardOutputWire;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.lang.Thread.State;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import org.hiero.base.concurrent.test.fixtures.ConsumerWithCompletionControl;
import org.hiero.base.concurrent.test.fixtures.FunctionWithExecutionControl;
import org.hiero.base.concurrent.test.fixtures.Gate;
import org.hiero.base.concurrent.test.fixtures.RunnableCompletionControl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SequentialTaskSchedulerTests implements SequentialTaskSchedulerAliveThreadCleanup {

    private static final NoOpMetrics NO_OP_METRICS = new NoOpMetrics();
    private static final int DEFAULT_OPERATIONS = 100;
    public static final Duration AWAIT_MAX_DURATION = Duration.ofSeconds(10);
    // Each test sets the model so it can be clean up in the teardown phase
    private WiringModel model;

    @Test
    void illegalNamesTest() {
        this.model = TestWiringModelBuilder.create();

        assertThrows(NullPointerException.class, () -> model.schedulerBuilder(null));
        assertThrows(IllegalArgumentException.class, () -> model.schedulerBuilder(""));
        assertThrows(IllegalArgumentException.class, () -> model.schedulerBuilder(" "));
        assertThrows(IllegalArgumentException.class, () -> model.schedulerBuilder("foo bar"));
        assertThrows(IllegalArgumentException.class, () -> model.schedulerBuilder("foo?bar"));
        assertThrows(IllegalArgumentException.class, () -> model.schedulerBuilder("foo:bar"));
        assertThrows(IllegalArgumentException.class, () -> model.schedulerBuilder("foo*bar"));
        assertThrows(IllegalArgumentException.class, () -> model.schedulerBuilder("foo/bar"));
        assertThrows(IllegalArgumentException.class, () -> model.schedulerBuilder("foo\\bar"));
        assertThrows(IllegalArgumentException.class, () -> model.schedulerBuilder("foo-bar"));

        // legal names that should not throw
        model.schedulerBuilder("x");
        model.schedulerBuilder("fooBar");
        model.schedulerBuilder("foo_bar");
        model.schedulerBuilder("foo_bar123");
        model.schedulerBuilder("123");
    }

    /**
     * Add values to the task scheduler, ensure that each value was processed in the correct order.
     */
    @ParameterizedTest
    @ValueSource(strings = {"SEQUENTIAL", "SEQUENTIAL_THREAD"})
    void orderOfOperationsTest(final String typeString) {
        this.model = TestWiringModelBuilder.create();

        final TaskSchedulerType type = TaskSchedulerType.valueOf(typeString);

        final AtomicInteger wireValue = new AtomicInteger();
        final ConsumerWithCompletionControl<Integer> handler =
                ConsumerWithCompletionControl.unblocked(x -> wireValue.set(hash32(wireValue.get(), x)));

        final TaskScheduler<Void> taskScheduler = model.<Void>schedulerBuilder("test")
                .withType(type)
                .withUnhandledTaskCapacity(UNLIMITED_CAPACITY)
                .build();
        final BindableInputWire<Integer, Void> channel = taskScheduler.buildInputWire("channel");
        channel.bindConsumer(handler);
        assertEquals(-1, taskScheduler.getUnprocessedTaskCount());
        assertEquals("test", taskScheduler.getName());

        model.start();
        int value = 0;
        for (int i = 0; i < DEFAULT_OPERATIONS; i++) {
            channel.put(i);
            value = hash32(value, i);
        }

        handler.executionControl().await(DEFAULT_OPERATIONS, AWAIT_MAX_DURATION);
        assertEquals(
                value, wireValue.get(), "Wire sum did not match expected sum: " + value + " vs " + wireValue.get());
        model.stop();
    }

    /**
     * Multiple threads adding work to the task scheduler shouldn't cause problems. Also, work should always be handled
     * sequentially regardless of the number of threads adding work.
     */
    @ParameterizedTest
    @ValueSource(strings = {"SEQUENTIAL", "SEQUENTIAL_THREAD"})
    void multipleChannelsTest(final String typeString) {
        this.model = TestWiringModelBuilder.create();
        final TaskSchedulerType type = TaskSchedulerType.valueOf(typeString);

        final AtomicLong wireValue = new AtomicLong();
        // concurrent hash set
        final int ops = 1_000;
        final List<Integer> expectedArguments = IntStream.range(0, ops).boxed().toList();
        // Do not use a thread safe data structure on purpose, work should be done sequentially
        final Set<Integer> nonProcessedInput = new HashSet<>(expectedArguments);
        // Compute the values we expect to be computed by the wire
        final long expectedValue =
                IntStream.range(0, ops).mapToLong(i -> (long) i).sum();
        // This will result in a deterministic value
        final ConsumerWithCompletionControl<Long> handler = ConsumerWithCompletionControl.unblocked(x -> {
            nonProcessedInput.remove(x.intValue());
            // This will result in a deterministic value
            wireValue.addAndGet(x);
        });

        final TaskScheduler<Void> taskScheduler = model.<Void>schedulerBuilder("test")
                .withType(type)
                .withUnhandledTaskCapacity(UNLIMITED_CAPACITY)
                .build();
        final BindableInputWire<Long, Void> channel = taskScheduler.buildInputWire("channel");
        channel.bindConsumer(handler);
        assertEquals(-1, taskScheduler.getUnprocessedTaskCount());
        assertEquals("test", taskScheduler.getName());

        model.start();

        expectedArguments.stream().parallel().forEach(i -> channel.put(i.longValue()));

        handler.executionControl().await(ops, AWAIT_MAX_DURATION);
        assertEquals(expectedValue, wireValue.get(), "Wire sum did not match expected sum");
        assertTrue(nonProcessedInput.isEmpty(), "We should have process all arguments");
        model.stop();
    }

    /**
     * Ensure that the work happening on the task scheduler is not happening on the caller's thread.
     */
    @ParameterizedTest
    @ValueSource(strings = {"SEQUENTIAL", "SEQUENTIAL_THREAD"})
    void wireDoesNotBlockCallingThreadTest(final String typeString) {
        this.model = TestWiringModelBuilder.create();
        final TaskSchedulerType type = TaskSchedulerType.valueOf(typeString);

        final AtomicInteger wireValue = new AtomicInteger();
        final Gate gate = Gate.closedGate();
        final ConsumerWithCompletionControl<Integer> handler = ConsumerWithCompletionControl.unblocked(x -> {
            wireValue.set(hash32(wireValue.get(), x));
            if (x == 50) {
                gate.knock(AWAIT_MAX_DURATION.toMillis());
            }
        });

        final TaskScheduler<Void> taskScheduler = model.<Void>schedulerBuilder("test")
                .withType(type)
                .withUnhandledTaskCapacity(UNLIMITED_CAPACITY)
                .build();
        final BindableInputWire<Integer, Void> channel = taskScheduler.buildInputWire("channel");
        channel.bindConsumer(handler);
        assertEquals(-1, taskScheduler.getUnprocessedTaskCount());
        assertEquals("test", taskScheduler.getName());

        model.start();

        // The wire will stop processing at 50, but this should not block the calling thread.
        final AtomicInteger value = new AtomicInteger();
        final RunnableCompletionControl producer = RunnableCompletionControl.unblocked(() -> {
            for (int i = 0; i < DEFAULT_OPERATIONS; i++) {
                channel.put(i);
                value.set(hash32(value.get(), i));
            }
        });
        producer.start();
        producer.waitIsFinished(AWAIT_MAX_DURATION);
        // Release the gate and allow the wire to finish
        gate.open();

        handler.executionControl().await(DEFAULT_OPERATIONS, AWAIT_MAX_DURATION);
        assertEquals(value.get(), wireValue.get(), "Wire sum did not match expected sum");

        model.stop();
    }

    /**
     * Sanity checks on the unprocessed event count.
     */
    @ParameterizedTest
    @ValueSource(strings = {"SEQUENTIAL", "SEQUENTIAL_THREAD"})
    void unprocessedEventCountTest(final String typeString) {
        this.model = TestWiringModelBuilder.create();
        final TaskSchedulerType type = TaskSchedulerType.valueOf(typeString);

        final AtomicInteger wireValue = new AtomicInteger();
        final Gate gate50 = Gate.closedGate();
        final Gate gate98 = Gate.closedGate();
        final ConsumerWithCompletionControl<Integer> handler = ConsumerWithCompletionControl.blocked(x -> {
            if (x == 50) {
                gate50.knock(AWAIT_MAX_DURATION.toMillis());
            } else if (x == 98) {
                gate98.knock(AWAIT_MAX_DURATION.toMillis());
            }
            wireValue.set(hash32(wireValue.get(), x));
        });

        final TaskScheduler<Void> taskScheduler = model.<Void>schedulerBuilder("test")
                .withType(type)
                .withUnhandledTaskMetricEnabled(true)
                .withUnhandledTaskCapacity(UNLIMITED_CAPACITY)
                .build();
        final BindableInputWire<Integer, Void> channel = taskScheduler.buildInputWire("channel");
        channel.bindConsumer(handler);
        assertEquals(0, taskScheduler.getUnprocessedTaskCount());
        assertEquals("test", taskScheduler.getName());

        model.start();

        int value = 0;
        for (int i = 0; i < DEFAULT_OPERATIONS; i++) {
            channel.put(i);
            value = hash32(value, i);
        }

        assertUnprocessedTasksValueIs(taskScheduler, 100L);

        handler.executionControl().unblock();
        handler.executionControl().await(50, AWAIT_MAX_DURATION);

        assertUnprocessedTasksValueIs(taskScheduler, 50L);

        gate50.open();
        handler.executionControl().await(48, AWAIT_MAX_DURATION);
        assertUnprocessedTasksValueIs(taskScheduler, 2L);

        gate98.open();
        handler.executionControl().await(2, AWAIT_MAX_DURATION);

        assertEquals(value, wireValue.get(), "Wire sum did not match expected sum");
        assertUnprocessedTasksValueIs(taskScheduler, 0L);

        model.stop();
    }

    /**
     * Make sure backpressure works.
     */
    @ParameterizedTest
    @ValueSource(strings = {"SEQUENTIAL", "SEQUENTIAL_THREAD"})
    void backpressureTest(final String typeString) {

        this.model = WiringModelBuilder.create(NO_OP_METRICS, Time.getCurrent())
                .withHardBackpressureEnabled(true)
                .build();
        final TaskSchedulerType type = TaskSchedulerType.valueOf(typeString);
        final int capacity = 11;
        final AtomicInteger wireValue = new AtomicInteger();
        final ConsumerWithCompletionControl<Integer> handler =
                ConsumerWithCompletionControl.blocked(x -> wireValue.set(hash32(wireValue.get(), x)));

        final TaskScheduler<Void> taskScheduler = model.<Void>schedulerBuilder("test")
                .withType(type)
                .withUnhandledTaskCapacity(capacity)
                .withSleepDuration(Duration.ofMillis(1))
                .build();
        final BindableInputWire<Integer, Void> channel = taskScheduler.buildInputWire("channel");
        channel.bindConsumer(handler);
        assertEquals(0, taskScheduler.getUnprocessedTaskCount());
        assertEquals("test", taskScheduler.getName());

        model.start();

        final AtomicInteger value = new AtomicInteger();
        // The task scheduler will get blocked processing task 0,
        // It will have the capacity for 10 more, for a total of 11 tasks in flight
        // So the following thread will not block and after its execution, the task scheduler should be full
        final RunnableCompletionControl producer = RunnableCompletionControl.unblocked(() -> {
            for (int i1 = 0; i1 < capacity; i1++) {
                channel.put(i1);
                value.set(hash32(value.get(), i1));
            }
        });
        producer.start();
        producer.waitIsFinished(AWAIT_MAX_DURATION);
        long currentUnprocessedTaskCount = taskScheduler.getUnprocessedTaskCount();
        assertTrue(currentUnprocessedTaskCount == capacity || currentUnprocessedTaskCount == capacity + 1);

        // Try to enqueue work on another thread.
        // Because of backpressure, this thread should block when adding to a full task scheduler.
        final AtomicBoolean allWorkAdded = new AtomicBoolean(false);
        final RunnableCompletionControl secondProducer = RunnableCompletionControl.unblocked(() -> {
            for (int i = capacity; i < DEFAULT_OPERATIONS; i++) {
                channel.put(i);
                value.set(hash32(value.get(), i));
            }
            allWorkAdded.set(true);
        });

        final Thread secondProducerThread = secondProducer.start();
        assertEventuallyEquals(State.TIMED_WAITING, secondProducerThread::getState, "Producer thread was not blocked");
        assertFalse(allWorkAdded.get());
        currentUnprocessedTaskCount = taskScheduler.getUnprocessedTaskCount();
        assertTrue(currentUnprocessedTaskCount == capacity || currentUnprocessedTaskCount == capacity + 1);

        // Even if the wire has no capacity, neither offer() nor inject() should not block.
        final RunnableCompletionControl thirdProducer = RunnableCompletionControl.unblocked(() -> {
            assertFalse(channel.offer(1234));
            assertFalse(channel.offer(4321));
            assertFalse(channel.offer(-1));
            channel.inject(42);
            value.set(hash32(value.get(), 42));
        });
        thirdProducer.start();
        thirdProducer.waitIsFinished(AWAIT_MAX_DURATION);

        // Release the lock
        handler.executionControl().unblock();
        secondProducer.waitIsFinished(AWAIT_MAX_DURATION);
        handler.executionControl().await(DEFAULT_OPERATIONS + 1, AWAIT_MAX_DURATION);

        assertTrue(allWorkAdded.get(), "unable to add all work");
        assertEquals(value.get(), wireValue.get(), "Wire sum did not match expected sum");
        assertUnprocessedTasksValueIs(taskScheduler, 0L);
        assertEventuallyEquals(State.TERMINATED, secondProducerThread::getState, "Producer thread was not terminated");

        model.stop();
    }

    /**
     * Test that when the task scheduler is (because of backpressure) blocked in the put operation,
     * interrupting the caller thread does not produce the caller thread to do anything.
     */
    @ParameterizedTest
    @ValueSource(strings = {"SEQUENTIAL", "SEQUENTIAL_THREAD"})
    void uninterruptableTest(final String typeString) {

        this.model = WiringModelBuilder.create(NO_OP_METRICS, Time.getCurrent())
                .withHardBackpressureEnabled(true)
                .build();

        final TaskSchedulerType type = TaskSchedulerType.valueOf(typeString);

        final int capacity = 11;
        final AtomicInteger wireValue = new AtomicInteger();
        final ConsumerWithCompletionControl<Integer> handler =
                ConsumerWithCompletionControl.blocked(x -> wireValue.set(hash32(wireValue.get(), x)));

        final TaskScheduler<Void> taskScheduler = model.<Void>schedulerBuilder("test")
                .withType(type)
                .withUnhandledTaskCapacity(capacity)
                .build();
        final BindableInputWire<Integer, Void> channel = taskScheduler.buildInputWire("channel");
        channel.bindConsumer(handler);
        assertEquals(0, taskScheduler.getUnprocessedTaskCount());
        assertEquals("test", taskScheduler.getName());

        model.start();

        final AtomicInteger value = new AtomicInteger();

        // We will be stuck handling 0, and we will have the capacity for 10 more, for a total of 11 tasks in flight
        final RunnableCompletionControl producer = RunnableCompletionControl.unblocked(() -> {
            for (int i1 = 0; i1 < capacity; i1++) {
                channel.put(i1);
                value.set(hash32(value.get(), i1));
            }
        });
        producer.start();
        producer.waitIsFinished(AWAIT_MAX_DURATION);
        assertUnprocessedTasksValueIs(taskScheduler, capacity);

        // Try to enqueue work on another thread. It should get stuck and be
        // unable to add anything until we release the handler.
        final AtomicBoolean allWorkAdded = new AtomicBoolean(false);
        final RunnableCompletionControl restOfProducers = RunnableCompletionControl.unblocked(() -> {
            for (int i = capacity; i < DEFAULT_OPERATIONS; i++) {
                channel.put(i);
                value.set(hash32(value.get(), i));
            }
            allWorkAdded.set(true);
        });

        final Thread producerThread = restOfProducers.start();
        // give the thread time to start and block
        assertEventuallyEquals(State.TIMED_WAITING, producerThread::getState, "Producer thread was not blocked");
        // After the thread is blocked, interruptions should not throw
        assertDoesNotThrow(producerThread::interrupt);
        assertFalse(allWorkAdded.get());

        assertTrue((capacity + 1) >= taskScheduler.getUnprocessedTaskCount());
        // Interrupting the thread should have no effect.
        producerThread.interrupt();

        // Release the gate, all work should now be added
        handler.executionControl().unblock();
        restOfProducers.waitIsFinished(AWAIT_MAX_DURATION);
        handler.executionControl().await(DEFAULT_OPERATIONS, AWAIT_MAX_DURATION);
        assertTrue(allWorkAdded.get(), "unable to add all work");
        assertEquals(value.get(), wireValue.get(), "Wire sum did not match expected sum");
        assertUnprocessedTasksValueIs(taskScheduler, 0L);
        assertEventuallyEquals(State.TERMINATED, producerThread::getState, "Producer thread was not terminated");
        model.stop();
    }

    /**
     * Offering tasks is equivalent to calling put() if there is no backpressure.
     */
    @ParameterizedTest
    @ValueSource(strings = {"SEQUENTIAL", "SEQUENTIAL_THREAD"})
    void offerNoBackpressureTest(final String typeString) {
        this.model = TestWiringModelBuilder.create();
        final TaskSchedulerType type = TaskSchedulerType.valueOf(typeString);

        final int totalWork = 11;
        final AtomicInteger wireValue = new AtomicInteger();
        final ConsumerWithCompletionControl<Integer> handler =
                ConsumerWithCompletionControl.unblocked(x -> wireValue.set(hash32(wireValue.get(), x)));

        final TaskScheduler<Void> taskScheduler = model.<Void>schedulerBuilder("test")
                .withType(type)
                .withUnhandledTaskCapacity(UNLIMITED_CAPACITY)
                .build();
        final BindableInputWire<Integer, Void> channel = taskScheduler.buildInputWire("channel");
        channel.bindConsumer(handler);
        assertEquals(-1, taskScheduler.getUnprocessedTaskCount());
        assertEquals("test", taskScheduler.getName());

        model.start();

        int value = 0;
        for (int i = 0; i < totalWork; i++) {
            assertTrue(channel.offer(i));
            value = hash32(value, i);
        }

        handler.executionControl().await(totalWork, AWAIT_MAX_DURATION);
        assertEquals(value, wireValue.get(), "Wire sum did not match expected sum");
        model.stop();
    }

    /**
     * Test a scenario where there is a circular data flow formed by wires.
     * <p>
     * In this test, all data is passed from A to B to C to D. All data that is a multiple of 7 is passed from D to A as
     * a negative value, but is not passed around the loop again.
     *
     * <pre>
     * A -------> B
     * ^          |
     * |          |
     * |          V
     * D <------- C
     * </pre>
     */
    @ParameterizedTest
    @ValueSource(strings = {"SEQUENTIAL", "SEQUENTIAL_THREAD"})
    void circularDataFlowTest(final String typeString) {
        this.model = TestWiringModelBuilder.create();
        final TaskSchedulerType type = TaskSchedulerType.valueOf(typeString);

        final Random random = getRandomPrintSeed();

        final AtomicInteger countA = new AtomicInteger();
        final AtomicInteger negativeCountA = new AtomicInteger();
        final AtomicInteger countB = new AtomicInteger();
        final AtomicInteger countC = new AtomicInteger();
        final AtomicInteger countD = new AtomicInteger();

        final TaskScheduler<Integer> taskSchedulerToA = model.<Integer>schedulerBuilder("wireToA")
                .withType(type)
                .withUnhandledTaskCapacity(UNLIMITED_CAPACITY)
                .build();
        final TaskScheduler<Integer> taskSchedulerToB = model.<Integer>schedulerBuilder("wireToB")
                .withType(type)
                .withUnhandledTaskCapacity(UNLIMITED_CAPACITY)
                .build();
        final TaskScheduler<Integer> taskSchedulerToC = model.<Integer>schedulerBuilder("wireToC")
                .withType(type)
                .withUnhandledTaskCapacity(UNLIMITED_CAPACITY)
                .build();
        final TaskScheduler<Integer> taskSchedulerToD = model.<Integer>schedulerBuilder("wireToD")
                .withType(type)
                .withUnhandledTaskCapacity(UNLIMITED_CAPACITY)
                .build();

        final BindableInputWire<Integer, Integer> channelToA = taskSchedulerToA.buildInputWire("channelToA");
        final BindableInputWire<Integer, Integer> channelToB = taskSchedulerToB.buildInputWire("channelToB");
        final BindableInputWire<Integer, Integer> channelToC = taskSchedulerToC.buildInputWire("channelToC");
        final BindableInputWire<Integer, Integer> channelToD = taskSchedulerToD.buildInputWire("channelToD");

        // negative values are values that have been passed around the loop
        // Don't pass them on again, or else we will get an infinite loop
        final FunctionWithExecutionControl<Integer, Integer> handlerA = FunctionWithExecutionControl.unBlocked(x3 -> {
            if (x3 > 0) {
                countA.set(hash32(x3, countA.get()));
                return x3;
            } else {
                negativeCountA.set(hash32(x3, negativeCountA.get()));
                // negative values are values that have been passed around the loop
                // Don't pass them on again, or else we will get an infinite loop
                return null;
            }
        });

        final FunctionWithExecutionControl<Integer, Integer> handlerB = FunctionWithExecutionControl.unBlocked(x2 -> {
            countB.set(hash32(x2, countB.get()));
            return x2;
        });

        final FunctionWithExecutionControl<Integer, Integer> handlerC = FunctionWithExecutionControl.unBlocked(x1 -> {
            countC.set(hash32(x1, countC.get()));
            return x1;
        });

        final FunctionWithExecutionControl<Integer, Integer> handlerD = FunctionWithExecutionControl.unBlocked(x -> {
            countD.set(hash32(x, countD.get()));
            if (x % 7 == 0) {
                return -x;
            } else {
                return null;
            }
        });

        taskSchedulerToA.getOutputWire().solderTo(channelToB);
        taskSchedulerToB.getOutputWire().solderTo(channelToC);
        taskSchedulerToC.getOutputWire().solderTo(channelToD);
        taskSchedulerToD.getOutputWire().solderTo(channelToA);

        channelToA.bind(handlerA);
        channelToB.bind(handlerB);
        channelToC.bind(handlerC);
        channelToD.bind(handlerD);

        model.start();

        int expectedCountA = 0;
        int expectedNegativeCountA = 0;
        int expectedCountB = 0;
        int expectedCountC = 0;
        int expectedCountD = 0;
        int timesSecondA = 0;
        for (int i = 1; i < 1000; i++) {
            channelToA.put(i);

            expectedCountA = hash32(i, expectedCountA);
            expectedCountB = hash32(i, expectedCountB);
            expectedCountC = hash32(i, expectedCountC);
            expectedCountD = hash32(i, expectedCountD);

            if (i % 7 == 0) {
                expectedNegativeCountA = hash32(-i, expectedNegativeCountA);
                timesSecondA++;
            }

            // Sleep to give data a chance to flow around the loop
            // (as opposed to adding it so quickly that it is all enqueue prior to any processing)
            if (random.nextDouble() < 0.1) {
                sleep(10);
            }
        }

        handlerA.executionControl().await(999, AWAIT_MAX_DURATION);
        handlerB.executionControl().await(999, AWAIT_MAX_DURATION);
        handlerC.executionControl().await(999, AWAIT_MAX_DURATION);
        handlerD.executionControl().await(999, AWAIT_MAX_DURATION);
        handlerA.executionControl().await(timesSecondA, AWAIT_MAX_DURATION);
        assertEquals(expectedCountA, countA.get(), "Wire A sum did not match expected value");
        assertEquals(expectedCountB, countB.get(), "Wire B sum did not match expected value");
        assertEquals(expectedCountC, countC.get(), "Wire C sum did not match expected value");
        assertEquals(expectedCountD, countD.get(), "Wire D sum did not match expected value");
        assertEquals(expectedNegativeCountA, negativeCountA.get(), "Wire A negative sum did not match expected value");
        model.stop();
    }

    /**
     * Validate the behavior when there are multiple channels.
     */
    @ParameterizedTest
    @ValueSource(strings = {"SEQUENTIAL", "SEQUENTIAL_THREAD"})
    void multipleChannelTypesTest(final String typeString) {
        this.model = TestWiringModelBuilder.create();
        final TaskSchedulerType type = TaskSchedulerType.valueOf(typeString);

        final int totalWork = DEFAULT_OPERATIONS;
        final AtomicInteger wireValue = new AtomicInteger();
        final ConsumerWithCompletionControl<Integer> integerHandler =
                ConsumerWithCompletionControl.unblocked(x2 -> wireValue.set(hash32(wireValue.get(), x2)));
        final ConsumerWithCompletionControl<Boolean> booleanHandler =
                ConsumerWithCompletionControl.unblocked(x1 -> wireValue.set((x1 ? -1 : 1) * wireValue.get()));
        final ConsumerWithCompletionControl<String> stringHandler =
                ConsumerWithCompletionControl.unblocked(x -> wireValue.set(hash32(wireValue.get(), x.hashCode())));

        final TaskScheduler<Void> taskScheduler = model.<Void>schedulerBuilder("test")
                .withType(type)
                .withUnhandledTaskCapacity(UNLIMITED_CAPACITY)
                .build();

        final BindableInputWire<Integer, Void> integerChannel = taskScheduler.buildInputWire("integerChannel");
        integerChannel.bindConsumer(integerHandler);
        final BindableInputWire<Boolean, Void> booleanChannel = taskScheduler.buildInputWire("booleanChannel");
        booleanChannel.bindConsumer(booleanHandler);
        final BindableInputWire<String, Void> stringChannel = taskScheduler.buildInputWire("stringChannel");
        stringChannel.bindConsumer(stringHandler);

        assertEquals(-1, taskScheduler.getUnprocessedTaskCount());
        assertEquals("test", taskScheduler.getName());

        model.start();

        int value = 0;
        for (int i = 0; i < totalWork; i++) {
            integerChannel.put(i);
            value = hash32(value, i);

            boolean invert = i % 2 == 0;
            booleanChannel.put(invert);
            value = (invert ? -1 : 1) * value;

            final String string = String.valueOf(i);
            stringChannel.put(string);
            value = hash32(value, string.hashCode());
        }

        integerHandler.executionControl().await(totalWork, AWAIT_MAX_DURATION);
        booleanHandler.executionControl().await(totalWork, AWAIT_MAX_DURATION);
        stringHandler.executionControl().await(totalWork, AWAIT_MAX_DURATION);
        assertEquals(value, wireValue.get(), "Wire value did not match expected value");

        model.stop();
    }

    /**
     * Make sure backpressure works when there are multiple channels.
     */
    @ParameterizedTest
    @ValueSource(strings = {"SEQUENTIAL", "SEQUENTIAL_THREAD"})
    void multipleChannelBackpressureTest(final String typeString) {

        this.model = WiringModelBuilder.create(NO_OP_METRICS, Time.getCurrent())
                .withHardBackpressureEnabled(true)
                .build();

        final TaskSchedulerType type = TaskSchedulerType.valueOf(typeString);
        final int capacity = 11;
        List<Integer> unprocessedArguments =
                new ArrayList<>(IntStream.range(0, DEFAULT_OPERATIONS).boxed().toList());

        final ConsumerWithCompletionControl<Integer> handler1 =
                ConsumerWithCompletionControl.blocked(unprocessedArguments::remove);

        final ConsumerWithCompletionControl<Integer> handler2 =
                ConsumerWithCompletionControl.unblocked(unprocessedArguments::remove);

        final TaskScheduler<Void> taskScheduler = model.<Void>schedulerBuilder("test")
                .withType(type)
                .withUnhandledTaskCapacity(capacity)
                .build();

        final BindableInputWire<Integer, Void> channel1 = taskScheduler.buildInputWire("channel1");
        channel1.bindConsumer(handler1);
        final BindableInputWire<Integer, Void> channel2 = taskScheduler.buildInputWire("channel2");
        channel2.bindConsumer(handler2);

        assertEquals(0, taskScheduler.getUnprocessedTaskCount());
        assertEquals("test", taskScheduler.getName());

        model.start();

        // We will be stuck handling 0, and we will have the capacity for 10 more, for a total of 11 tasks in flight
        final RunnableCompletionControl producer = RunnableCompletionControl.unblocked(() -> {
            for (int i1 = 0; i1 < capacity; i1++) {
                channel1.put(i1);
            }
        });

        producer.start();
        producer.waitIsFinished(AWAIT_MAX_DURATION);
        assertUnprocessedTasksValueIs(taskScheduler, capacity);

        // Try to enqueue work on another thread. It should get stuck and be
        // unable to add anything until we release the signal.
        final AtomicBoolean allWorkAdded = new AtomicBoolean(false);
        final RunnableCompletionControl secondProducer = RunnableCompletionControl.unblocked(() -> {
            for (int i = capacity; i < DEFAULT_OPERATIONS; i++) {
                channel2.put(i);
            }
            allWorkAdded.set(true);
        });
        final Thread secondProducerThread = secondProducer.start();

        // An unblocked wire would have processed all the work added to it really fast.
        // as soon as the thread is blocked the fmw should reflect the unprocessed task count
        assertEventuallyEquals(State.TIMED_WAITING, secondProducerThread::getState, "Producer thread was not blocked");
        assertEquals(capacity, taskScheduler.getUnprocessedTaskCount());
        assertFalse(allWorkAdded.get());

        // Even if the wire has no capacity, neither offer() nor inject() should not block.
        unprocessedArguments.add(100);
        RunnableCompletionControl thirdProducer = RunnableCompletionControl.unblocked(() -> {
            assertFalse(channel1.offer(1234));
            assertFalse(channel1.offer(4321));
            assertFalse(channel1.offer(-1));
            channel1.inject(100);
        });
        thirdProducer.start();
        thirdProducer.waitIsFinished(AWAIT_MAX_DURATION);

        // Release the gate, all work should now be added
        handler1.executionControl().unblock();
        secondProducer.waitIsFinished(AWAIT_MAX_DURATION);
        handler1.executionControl().await(capacity + 1, AWAIT_MAX_DURATION);
        handler2.executionControl().await(DEFAULT_OPERATIONS - capacity, AWAIT_MAX_DURATION);

        assertTrue(allWorkAdded.get(), "unable to add all work");
        assertTrue(unprocessedArguments.isEmpty(), "Wire did not process all work:" + unprocessedArguments);
        assertUnprocessedTasksValueIs(taskScheduler, 0L);
        assertEventuallyEquals(State.TERMINATED, secondProducerThread::getState, "Producer thread was not terminated");
        model.stop();
    }

    /**
     * Make sure backpressure works when a single counter spans multiple wires.
     */
    @ParameterizedTest
    @ValueSource(strings = {"SEQUENTIAL", "SEQUENTIAL_THREAD"})
    void backpressureOverMultipleWiresTest(final String typeString) {
        this.model = TestWiringModelBuilder.create();
        final TaskSchedulerType type = TaskSchedulerType.valueOf(typeString);

        final AtomicInteger wireValueA = new AtomicInteger();
        final AtomicInteger wireValueB = new AtomicInteger();

        final int capacity = 11;
        final ObjectCounter backpressure = new BackpressureObjectCounter("test", capacity, Duration.ofMillis(1));

        final TaskScheduler<Integer> taskSchedulerA = model.<Integer>schedulerBuilder("testA")
                .withType(type)
                .withOnRamp(backpressure)
                .withUnhandledTaskCapacity(UNLIMITED_CAPACITY)
                .build();

        final TaskScheduler<Void> taskSchedulerB = model.<Void>schedulerBuilder("testB")
                .withType(type)
                .withOffRamp(backpressure)
                .withUnhandledTaskCapacity(UNLIMITED_CAPACITY)
                .build();

        final BindableInputWire<Integer, Integer> channelA = taskSchedulerA.buildInputWire("channelA");
        final BindableInputWire<Integer, Void> channelB = taskSchedulerB.buildInputWire("channelB");
        taskSchedulerA.getOutputWire().solderTo(channelB);

        final FunctionWithExecutionControl<Integer, Integer> handlerA = FunctionWithExecutionControl.unBlocked(x1 -> {
            wireValueA.set(hash32(wireValueA.get(), -x1));
            return x1;
        });

        final ConsumerWithCompletionControl<Integer> handlerB =
                ConsumerWithCompletionControl.blocked(x -> wireValueB.set(hash32(wireValueB.get(), x)));

        channelA.bind(handlerA);
        channelB.bindConsumer(handlerB);

        assertEquals(0, backpressure.getCount());
        assertEquals("testA", taskSchedulerA.getName());
        assertEquals("testB", taskSchedulerB.getName());

        final AtomicInteger valueA = new AtomicInteger();
        final AtomicInteger valueB = new AtomicInteger();

        model.start();

        // We will be stuck handling 0, and we will have the capacity for 10 more, for a total of 11 tasks in flight
        RunnableCompletionControl producer = RunnableCompletionControl.unblocked(() -> {
            for (int i1 = 0; i1 < capacity; i1++) {
                channelA.put(i1);
                valueA.set(hash32(valueA.get(), -i1));
                valueB.set(hash32(valueB.get(), i1));
            }
        });

        producer.start();
        producer.waitIsFinished(AWAIT_MAX_DURATION);
        assertEquals(capacity, backpressure.getCount());

        // Try to enqueue work on another thread. It should get stuck and be
        // unable to add anything until we release the gate.
        final AtomicBoolean allWorkAdded = new AtomicBoolean(false);
        final RunnableCompletionControl secondProducer = RunnableCompletionControl.unblocked(() -> {
            for (int i = capacity; i < DEFAULT_OPERATIONS; i++) {
                channelA.put(i);
                valueA.set(hash32(valueA.get(), -i));
                valueB.set(hash32(valueB.get(), i));
            }
            allWorkAdded.set(true);
        });
        secondProducer.start();

        // Adding work to an unblocked wire should be very fast. If we slept for a while, we'd expect that an unblocked
        // wire would have processed all the work added to it.
        sleep(50);
        assertFalse(allWorkAdded.get());
        assertEquals(capacity, backpressure.getCount());

        // Even if the wire has no capacity, neither offer() nor inject() should not block.
        final RunnableCompletionControl thirdProducer = RunnableCompletionControl.unblocked(() -> {
            assertFalse(channelA.offer(1234));
            assertFalse(channelA.offer(4321));
            assertFalse(channelA.offer(-1));
            channelA.inject(42);
            valueA.set(hash32(valueA.get(), -42));
            valueB.set(hash32(valueB.get(), 42));
        });

        thirdProducer.start();
        thirdProducer.waitIsFinished(AWAIT_MAX_DURATION);

        // Release the gate, all work should now be added
        handlerB.executionControl().unblock();
        secondProducer.waitIsFinished(AWAIT_MAX_DURATION);
        handlerA.executionControl().await(DEFAULT_OPERATIONS + 1, AWAIT_MAX_DURATION);
        handlerB.executionControl().await(DEFAULT_OPERATIONS + 1, AWAIT_MAX_DURATION);
        assertTrue(allWorkAdded.get(), "unable to add all work");
        assertEventuallyEquals(0L, backpressure::getCount, "Wire unprocessed task count did not match expected value");
        assertEquals(valueA.get(), wireValueA.get(), "Wire sum did not match expected sum");
        assertEquals(valueB.get(), wireValueB.get(), "Wire sum did not match expected sum");
        model.stop();
    }

    /**
     * Validate the behavior of the flush() method.
     */
    @ParameterizedTest
    @ValueSource(strings = {"SEQUENTIAL", "SEQUENTIAL_THREAD"})
    void flushTest(final String typeString) {

        this.model = WiringModelBuilder.create(NO_OP_METRICS, Time.getCurrent())
                .withHardBackpressureEnabled(true)
                .build();

        final TaskSchedulerType type = TaskSchedulerType.valueOf(typeString);
        final int capacity = 11;
        final AtomicInteger wireValue = new AtomicInteger();
        final ConsumerWithCompletionControl<Integer> handler =
                ConsumerWithCompletionControl.blocked(x -> wireValue.set(hash32(wireValue.get(), x)));

        final TaskScheduler<Void> taskScheduler = model.<Void>schedulerBuilder("test")
                .withType(type)
                .withUnhandledTaskCapacity(capacity)
                .withFlushingEnabled(true)
                .build();
        final BindableInputWire<Integer, Void> channel = taskScheduler.buildInputWire("channel");
        channel.bindConsumer(handler);
        assertEquals(0, taskScheduler.getUnprocessedTaskCount());
        assertEquals("test", taskScheduler.getName());

        final AtomicInteger value = new AtomicInteger();

        model.start();

        // Flushing a wire with nothing in it should return quickly.
        final RunnableCompletionControl fastFlusher = RunnableCompletionControl.unblocked(taskScheduler::flush);
        fastFlusher.start();
        fastFlusher.waitIsFinished(AWAIT_MAX_DURATION);

        // We will be stuck handling 0, and we will have the capacity for 10 more, for a total of 11 tasks in flight
        final RunnableCompletionControl producer = RunnableCompletionControl.unblocked(() -> {
            for (int i1 = 0; i1 < capacity; i1++) {
                channel.put(i1);
                value.set(hash32(value.get(), i1));
            }
        });
        producer.start();
        producer.waitIsFinished(AWAIT_MAX_DURATION);
        assertUnprocessedTasksValueIs(taskScheduler, capacity);

        // Try to enqueue work on another thread. It should get stuck and be
        // unable to add anything until we release the signal.
        final AtomicBoolean allWorkAdded = new AtomicBoolean(false);
        final RunnableCompletionControl secondProducer = RunnableCompletionControl.unblocked(() -> {
            for (int i = capacity; i < DEFAULT_OPERATIONS; i++) {
                channel.put(i);
                value.set(hash32(value.get(), i));
            }
            allWorkAdded.set(true);
        });

        secondProducer.start();

        // On another thread, flush the wire. This should also get stuck.
        final AtomicBoolean flushed = new AtomicBoolean(false);
        final RunnableCompletionControl flusher = RunnableCompletionControl.unblocked(() -> {
            taskScheduler.flush();
            flushed.set(true);
        });
        flusher.start();

        // Adding work to an unblocked wire should be very fast. If we slept for a while, we'd expect that an unblocked
        // wire would have processed all the work.
        sleep(50);
        assertFalse(allWorkAdded.get());
        assertFalse(flushed.get());
        assertUnprocessedTasksValueIs(taskScheduler, capacity);

        // Even if the wire has no capacity, neither offer() nor inject() should not block.
        final RunnableCompletionControl thirdProducer = RunnableCompletionControl.unblocked(() -> {
            assertFalse(channel.offer(1234));
            assertFalse(channel.offer(4321));
            assertFalse(channel.offer(-1));
            channel.inject(42);
            value.set(hash32(value.get(), 42));
        });
        thirdProducer.start();
        thirdProducer.waitIsFinished(AWAIT_MAX_DURATION);
        // Release the gate, all work should now be added
        handler.executionControl().unblock();
        secondProducer.waitIsFinished(AWAIT_MAX_DURATION);
        flusher.waitIsFinished(AWAIT_MAX_DURATION);

        assertTrue(allWorkAdded.get(), "unable to add all work");
        assertTrue(flushed.get(), "unable to flush wire");
        handler.executionControl().await(DEFAULT_OPERATIONS + 1, AWAIT_MAX_DURATION);
        assertEquals(value.get(), wireValue.get(), "Wire sum did not match expected sum");
        assertUnprocessedTasksValueIs(taskScheduler, 0L);

        model.stop();
    }

    @ParameterizedTest
    @ValueSource(strings = {"SEQUENTIAL", "SEQUENTIAL_THREAD"})
    void flushDisabledTest(final String typeString) {
        this.model = TestWiringModelBuilder.create();
        final TaskSchedulerType type = TaskSchedulerType.valueOf(typeString);

        final TaskScheduler<Void> taskScheduler = model.<Void>schedulerBuilder("test")
                .withType(type)
                .withUnhandledTaskCapacity(10)
                .build();

        model.start();

        assertThrows(UnsupportedOperationException.class, taskScheduler::flush, "flush() should not be supported");

        model.stop();
    }

    @ParameterizedTest
    @ValueSource(strings = {"SEQUENTIAL", "SEQUENTIAL_THREAD"})
    void exceptionHandlingTest(String typeString) {
        this.model = TestWiringModelBuilder.create();
        final TaskSchedulerType type = TaskSchedulerType.valueOf(typeString);

        final AtomicInteger wireValue = new AtomicInteger();
        final AtomicInteger lastX = new AtomicInteger();
        final ConsumerWithCompletionControl<Integer> handler = ConsumerWithCompletionControl.unblocked(x -> {
            lastX.set(x);
            if (x == 50) {
                throw new IllegalStateException("intentional");
            }
            wireValue.set(hash32(wireValue.get(), x));
        });

        final AtomicInteger exceptionCount = new AtomicInteger();
        final AtomicBoolean isLastXTheMinValueWhenProcessingException = new AtomicBoolean();
        final RunnableCompletionControl exceptionHandler = RunnableCompletionControl.unblocked(() -> {
            // check that is never called before the task that threw the exception.
            isLastXTheMinValueWhenProcessingException.set(lastX.get() >= 50);
            exceptionCount.incrementAndGet();
        });

        final TaskScheduler<Void> taskScheduler = model.<Void>schedulerBuilder("test")
                .withType(type)
                .withUncaughtExceptionHandler((t, e) -> exceptionHandler.run())
                .withUnhandledTaskCapacity(UNLIMITED_CAPACITY)
                .build();
        final BindableInputWire<Integer, Void> channel = taskScheduler.buildInputWire("channel");
        channel.bindConsumer(handler);
        assertEquals(-1, taskScheduler.getUnprocessedTaskCount());
        assertEquals("test", taskScheduler.getName());

        model.start();

        int value = 0;
        for (int i = 0; i < DEFAULT_OPERATIONS; i++) {
            channel.put(i);
            if (i != 50) {
                value = hash32(value, i);
            }
        }

        handler.executionControl().await(DEFAULT_OPERATIONS, AWAIT_MAX_DURATION);
        assertEquals(value, wireValue.get(), "Wire sum did not match expected sum");
        // Exception will not be processed right after the task that threw the exception.
        // So we check
        // a: that it was at least after that task,
        // b: the handler was executed.
        exceptionHandler.waitIsFinished(AWAIT_MAX_DURATION);
        assertTrue(isLastXTheMinValueWhenProcessingException.get());
        assertEquals(1, exceptionCount.get(), "Exception handler did not update the expected value");
        model.stop();
    }

    /**
     * An early implementation could deadlock in a scenario with backpressure enabled and a thread count that was less
     * than the number of blocking wires.
     */
    @ParameterizedTest
    @ValueSource(strings = {"SEQUENTIAL", "SEQUENTIAL_THREAD"})
    void deadlockTestOneThread(final String typeString) {
        this.model = TestWiringModelBuilder.create();
        final TaskSchedulerType type = TaskSchedulerType.valueOf(typeString);

        final ForkJoinPool pool = new ForkJoinPool(1);

        // create 3 wires with the following bindings:
        // a -> b -> c -> signal
        final TaskScheduler<Void> a = model.<Void>schedulerBuilder("a")
                .withType(type)
                .withUnhandledTaskCapacity(2)
                .withSleepDuration(Duration.ofMillis(1))
                .withPool(pool)
                .build();
        final TaskScheduler<Void> b = model.<Void>schedulerBuilder("b")
                .withType(type)
                .withUnhandledTaskCapacity(2)
                .withSleepDuration(Duration.ofMillis(1))
                .withPool(pool)
                .build();
        final TaskScheduler<Void> c = model.<Void>schedulerBuilder("c")
                .withType(type)
                .withUnhandledTaskCapacity(2)
                .withSleepDuration(Duration.ofMillis(1))
                .withPool(pool)
                .build();

        final BindableInputWire<Object, Void> channelA = a.buildInputWire("channelA");
        final BindableInputWire<Object, Void> channelB = b.buildInputWire("channelB");
        final BindableInputWire<Object, Void> channelC = c.buildInputWire("channelC");

        channelA.bindConsumer(channelB::put);
        channelB.bindConsumer(channelC::put);
        final ConsumerWithCompletionControl<Object> objectConsumer = ConsumerWithCompletionControl.blocked(o -> {});
        channelC.bindConsumer(objectConsumer);

        model.start();

        // each wire has a capacity of 1, so we can have 1 task waiting on each wire
        // insert a task into (C), which will start executing and waiting to be unblocked
        channelC.put(Object.class);
        // fill up the queues for each wire
        channelC.put(Object.class);
        channelA.put(Object.class);
        channelB.put(Object.class);

        RunnableCompletionControl producer = RunnableCompletionControl.unblocked(() -> {
            // release the task that should allow all tasks to complete
            objectConsumer.executionControl().unblock();
            // if tasks are completing, none of the wires should block
            channelA.put(Object.class);
            channelB.put(Object.class);
            channelC.put(Object.class);
        });
        producer.start();

        producer.waitIsFinished(AWAIT_MAX_DURATION);

        pool.shutdown();
        model.stop();
    }

    @ParameterizedTest
    @ValueSource(strings = {"SEQUENTIAL", "SEQUENTIAL_THREAD"})
    void deadlockTestThreeThreads(final String typeString) {
        this.model = TestWiringModelBuilder.create();
        final TaskSchedulerType type = TaskSchedulerType.valueOf(typeString);

        final ForkJoinPool pool = new ForkJoinPool(3);

        // create 3 wires with the following bindings:
        // a -> b -> c -> signal
        final TaskScheduler<Void> a = model.<Void>schedulerBuilder("a")
                .withType(type)
                .withUnhandledTaskCapacity(2)
                .withSleepDuration(Duration.ofMillis(1))
                .withPool(pool)
                .build();
        final TaskScheduler<Void> b = model.<Void>schedulerBuilder("b")
                .withType(type)
                .withUnhandledTaskCapacity(2)
                .withSleepDuration(Duration.ofMillis(1))
                .withPool(pool)
                .build();
        final TaskScheduler<Void> c = model.<Void>schedulerBuilder("c")
                .withType(type)
                .withUnhandledTaskCapacity(2)
                .withSleepDuration(Duration.ofMillis(1))
                .withPool(pool)
                .build();

        final BindableInputWire<Object, Void> channelA = a.buildInputWire("channelA");
        final BindableInputWire<Object, Void> channelB = b.buildInputWire("channelB");
        final BindableInputWire<Object, Void> channelC = c.buildInputWire("channelC");

        channelA.bindConsumer(channelB::put);
        channelB.bindConsumer(channelC::put);
        final ConsumerWithCompletionControl<Object> handlerC = ConsumerWithCompletionControl.blocked(o -> {});
        channelC.bindConsumer(handlerC);

        model.start();

        // each wire has a capacity of 1, so we can have 1 task waiting on each wire
        // insert a task into C, which will start executing and waiting on the signal
        channelC.put(Object.class);
        // fill up the queues for each wire
        channelC.put(Object.class);
        channelA.put(Object.class);
        channelB.put(Object.class);

        final RunnableCompletionControl runnable = RunnableCompletionControl.unblocked(() -> {
            // Unblock the handler. It should allow all tasks to complete
            handlerC.executionControl().unblock();
            // If tasks are completing, none of the wires should block
            channelA.put(Object.class);
            channelB.put(Object.class);
            channelC.put(Object.class);
        });
        runnable.start();
        runnable.waitIsFinished(AWAIT_MAX_DURATION);
        handlerC.executionControl().await(1, AWAIT_MAX_DURATION);
        pool.shutdown();
        model.stop();
    }

    /**
     * Solder together a simple sequence of wires.
     */
    @ParameterizedTest
    @ValueSource(strings = {"SEQUENTIAL", "SEQUENTIAL_THREAD"})
    void simpleSolderingTest(final String typeString) {
        this.model = TestWiringModelBuilder.create();
        final TaskSchedulerType type = TaskSchedulerType.valueOf(typeString);

        final TaskScheduler<Integer> taskSchedulerA =
                model.<Integer>schedulerBuilder("A").withType(type).build();
        final TaskScheduler<Integer> taskSchedulerB =
                model.<Integer>schedulerBuilder("B").withType(type).build();
        final TaskScheduler<Integer> taskSchedulerC =
                model.<Integer>schedulerBuilder("C").withType(type).build();
        final TaskScheduler<Void> taskSchedulerD =
                model.<Void>schedulerBuilder("D").withType(type).build();

        final BindableInputWire<Integer, Integer> inputA = taskSchedulerA.buildInputWire("inputA");
        final BindableInputWire<Integer, Integer> inputB = taskSchedulerB.buildInputWire("inputB");
        final BindableInputWire<Integer, Integer> inputC = taskSchedulerC.buildInputWire("inputC");
        final BindableInputWire<Integer, Void> inputD = taskSchedulerD.buildInputWire("inputD");

        taskSchedulerA.getOutputWire().solderTo(inputB);
        taskSchedulerB.getOutputWire().solderTo(inputC);
        taskSchedulerC.getOutputWire().solderTo(inputD);

        final AtomicInteger countA = new AtomicInteger();
        final AtomicInteger countB = new AtomicInteger();
        final AtomicInteger countC = new AtomicInteger();
        final AtomicInteger countD = new AtomicInteger();

        final FunctionWithExecutionControl<Integer, Integer> handlerA = FunctionWithExecutionControl.unBlocked(x3 -> {
            countA.set(hash32(countA.get(), x3));
            return x3;
        });

        final FunctionWithExecutionControl<Integer, Integer> handlerB = FunctionWithExecutionControl.unBlocked(x2 -> {
            countB.set(hash32(countB.get(), x2));
            return x2;
        });

        final FunctionWithExecutionControl<Integer, Integer> handlerC = FunctionWithExecutionControl.unBlocked(x1 -> {
            countC.set(hash32(countC.get(), x1));
            return x1;
        });

        final ConsumerWithCompletionControl<Integer> handlerD =
                ConsumerWithCompletionControl.unblocked(x -> countD.set(hash32(countD.get(), x)));

        inputA.bind(handlerA);
        inputB.bind(handlerB);
        inputC.bind(handlerC);
        inputD.bindConsumer(handlerD);

        model.start();

        int expectedCount = 0;

        for (int i = 0; i < DEFAULT_OPERATIONS; i++) {
            inputA.put(i);
            expectedCount = hash32(expectedCount, i);
        }

        handlerA.executionControl().await(DEFAULT_OPERATIONS, AWAIT_MAX_DURATION);
        handlerB.executionControl().await(DEFAULT_OPERATIONS, AWAIT_MAX_DURATION);
        handlerC.executionControl().await(DEFAULT_OPERATIONS, AWAIT_MAX_DURATION);
        handlerD.executionControl().await(DEFAULT_OPERATIONS, AWAIT_MAX_DURATION);

        assertEquals(expectedCount, countD.get(), "Wire sum did not match expected sum");
        assertEquals(expectedCount, countA.get());
        assertEquals(expectedCount, countB.get());
        assertEquals(expectedCount, countC.get());

        model.stop();
    }

    /**
     * Test soldering to a lambda function.
     */
    @ParameterizedTest
    @ValueSource(strings = {"SEQUENTIAL", "SEQUENTIAL_THREAD"})
    void lambdaSolderingTest(final String typeString) {
        this.model = TestWiringModelBuilder.create();
        final TaskSchedulerType type = TaskSchedulerType.valueOf(typeString);

        final TaskScheduler<Integer> taskSchedulerA =
                model.<Integer>schedulerBuilder("A").withType(type).build();
        final TaskScheduler<Integer> taskSchedulerB =
                model.<Integer>schedulerBuilder("B").withType(type).build();
        final TaskScheduler<Integer> taskSchedulerC =
                model.<Integer>schedulerBuilder("C").withType(type).build();
        final TaskScheduler<Void> taskSchedulerD =
                model.<Void>schedulerBuilder("D").withType(type).build();

        final BindableInputWire<Integer, Integer> inputA = taskSchedulerA.buildInputWire("inputA");
        final BindableInputWire<Integer, Integer> inputB = taskSchedulerB.buildInputWire("inputB");
        final BindableInputWire<Integer, Integer> inputC = taskSchedulerC.buildInputWire("inputC");
        final BindableInputWire<Integer, Void> inputD = taskSchedulerD.buildInputWire("inputD");

        taskSchedulerA.getOutputWire().solderTo(inputB);
        taskSchedulerB.getOutputWire().solderTo(inputC);
        taskSchedulerC.getOutputWire().solderTo(inputD);

        final AtomicInteger countA = new AtomicInteger();
        final AtomicInteger countB = new AtomicInteger();
        final AtomicInteger countC = new AtomicInteger();
        final AtomicInteger countD = new AtomicInteger();

        final AtomicInteger lambdaSum = new AtomicInteger();
        taskSchedulerB.getOutputWire().solderTo("lambda", "lambda input", lambdaSum::getAndAdd);

        final FunctionWithExecutionControl<Integer, Integer> handlerA = FunctionWithExecutionControl.unBlocked(x3 -> {
            countA.set(hash32(countA.get(), x3));
            return x3;
        });

        final FunctionWithExecutionControl<Integer, Integer> handlerB = FunctionWithExecutionControl.unBlocked(x2 -> {
            countB.set(hash32(countB.get(), x2));
            return x2;
        });

        final FunctionWithExecutionControl<Integer, Integer> handlerC = FunctionWithExecutionControl.unBlocked(x1 -> {
            countC.set(hash32(countC.get(), x1));
            return x1;
        });

        final ConsumerWithCompletionControl<Integer> handlerD =
                ConsumerWithCompletionControl.unblocked(x -> countD.set(hash32(countD.get(), x)));

        inputA.bind(handlerA);
        inputB.bind(handlerB);
        inputC.bind(handlerC);
        inputD.bindConsumer(handlerD);

        model.start();

        int expectedCount = 0;

        int sum = 0;
        for (int i = 0; i < DEFAULT_OPERATIONS; i++) {
            inputA.put(i);
            expectedCount = hash32(expectedCount, i);
            sum += i;
        }

        handlerA.executionControl().await(DEFAULT_OPERATIONS, AWAIT_MAX_DURATION);
        handlerB.executionControl().await(DEFAULT_OPERATIONS, AWAIT_MAX_DURATION);
        handlerC.executionControl().await(DEFAULT_OPERATIONS, AWAIT_MAX_DURATION);
        handlerD.executionControl().await(DEFAULT_OPERATIONS, AWAIT_MAX_DURATION);
        assertEquals(expectedCount, countD.get(), "Wire sum did not match expected sum");
        assertEquals(expectedCount, countA.get());
        assertEquals(expectedCount, countB.get());
        assertEquals(sum, lambdaSum.get());
        assertEquals(expectedCount, countC.get());

        model.stop();
    }

    /**
     * Solder the output of a wire to the inputs of multiple other wires.
     */
    @ParameterizedTest
    @ValueSource(strings = {"SEQUENTIAL", "SEQUENTIAL_THREAD"})
    void multiWireSolderingTest(final String typeString) {
        this.model = TestWiringModelBuilder.create();
        final TaskSchedulerType type = TaskSchedulerType.valueOf(typeString);

        // A passes data to X, Y, and Z
        // X, Y, and Z pass data to B

        final TaskScheduler<Integer> taskSchedulerA =
                model.<Integer>schedulerBuilder("A").withType(type).build();
        final BindableInputWire<Integer, Integer> addNewValueToA = taskSchedulerA.buildInputWire("addNewValueToA");
        final BindableInputWire<Boolean, Integer> setInversionBitInA =
                taskSchedulerA.buildInputWire("setInversionBitInA");

        final TaskScheduler<Integer> taskSchedulerX =
                model.<Integer>schedulerBuilder("X").withType(type).build();
        final BindableInputWire<Integer, Integer> inputX = taskSchedulerX.buildInputWire("inputX");

        final TaskScheduler<Integer> taskSchedulerY =
                model.<Integer>schedulerBuilder("Y").withType(type).build();
        final BindableInputWire<Integer, Integer> inputY = taskSchedulerY.buildInputWire("inputY");

        final TaskScheduler<Integer> taskSchedulerZ =
                model.<Integer>schedulerBuilder("Z").withType(type).build();
        final BindableInputWire<Integer, Integer> inputZ = taskSchedulerZ.buildInputWire("inputZ");

        final TaskScheduler<Void> taskSchedulerB =
                model.<Void>schedulerBuilder("B").withType(type).build();
        final BindableInputWire<Integer, Void> inputB = taskSchedulerB.buildInputWire("inputB");

        taskSchedulerA.getOutputWire().solderTo(inputX);
        taskSchedulerA.getOutputWire().solderTo(inputY);
        taskSchedulerA.getOutputWire().solderTo(inputZ);
        taskSchedulerX.getOutputWire().solderTo(inputB);
        taskSchedulerY.getOutputWire().solderTo(inputB);
        taskSchedulerZ.getOutputWire().solderTo(inputB);

        final AtomicInteger countA = new AtomicInteger();
        final AtomicBoolean invertA = new AtomicBoolean();
        final FunctionWithExecutionControl<Integer, Integer> handlerA = FunctionWithExecutionControl.unBlocked(x4 -> {
            final int possiblyInvertedValue1 = x4 * (invertA.get() ? -1 : 1);
            countA.set(hash32(countA.get(), possiblyInvertedValue1));
            return possiblyInvertedValue1;
        });

        final FunctionWithExecutionControl<Boolean, Integer> handlerInvertA =
                FunctionWithExecutionControl.unBlocked(x3 -> {
                    invertA.set(x3);
                    return null;
                });

        final AtomicInteger countX = new AtomicInteger();
        final FunctionWithExecutionControl<Integer, Integer> handlerX = FunctionWithExecutionControl.unBlocked(x2 -> {
            countX.set(hash32(countX.get(), x2));
            return x2;
        });

        final AtomicInteger countY = new AtomicInteger();
        final FunctionWithExecutionControl<Integer, Integer> handlerY = FunctionWithExecutionControl.unBlocked(x1 -> {
            countY.set(hash32(countY.get(), x1));
            return x1;
        });

        final AtomicInteger countZ = new AtomicInteger();
        final FunctionWithExecutionControl<Integer, Integer> handlerZ = FunctionWithExecutionControl.unBlocked(x -> {
            countZ.set(hash32(countZ.get(), x));
            return x;
        });

        final AtomicInteger sumB = new AtomicInteger();
        final ConsumerWithCompletionControl<Integer> handlerB =
                ConsumerWithCompletionControl.unblocked(sumB::getAndAdd);

        addNewValueToA.bind(handlerA);
        setInversionBitInA.bind(handlerInvertA);
        inputX.bind(handlerX);
        inputY.bind(handlerY);
        inputZ.bind(handlerZ);
        inputB.bindConsumer(handlerB);

        model.start();

        int expectedCount = 0;
        boolean expectedInversionBit = false;
        int expectedSum = 0;

        for (int i = 0; i < DEFAULT_OPERATIONS; i++) {
            if (i % 7 == 0) {
                expectedInversionBit = !expectedInversionBit;
                setInversionBitInA.put(expectedInversionBit);
            }
            addNewValueToA.put(i);

            final int possiblyInvertedValue = i * (expectedInversionBit ? -1 : 1);

            expectedCount = hash32(expectedCount, possiblyInvertedValue);
            expectedSum = expectedSum + 3 * possiblyInvertedValue;
        }

        handlerA.executionControl().await(DEFAULT_OPERATIONS, AWAIT_MAX_DURATION);
        handlerInvertA.executionControl().await(15, AWAIT_MAX_DURATION);
        handlerX.executionControl().await(DEFAULT_OPERATIONS, AWAIT_MAX_DURATION);
        handlerY.executionControl().await(DEFAULT_OPERATIONS, AWAIT_MAX_DURATION);
        handlerZ.executionControl().await(DEFAULT_OPERATIONS, AWAIT_MAX_DURATION);
        handlerB.executionControl().await(DEFAULT_OPERATIONS * 3, AWAIT_MAX_DURATION);

        assertEquals(expectedCount, countA.get());
        assertEquals(expectedCount, countX.get());
        assertEquals(expectedCount, countY.get());
        assertEquals(expectedCount, countZ.get());
        assertEquals(
                expectedSum, sumB.get(), "Wire sum did not match expected sum, " + expectedSum + " vs " + sumB.get());
        assertEquals(expectedInversionBit, invertA.get(), "Wire inversion bit did not match expected value");

        model.stop();
    }

    /**
     * Validate that a wire soldered to another using injection ignores backpressure constraints.
     */
    @ParameterizedTest
    @ValueSource(strings = {"SEQUENTIAL", "SEQUENTIAL_THREAD"})
    void injectionSolderingTest(final String typeString) {

        this.model = WiringModelBuilder.create(NO_OP_METRICS, Time.getCurrent())
                .withHardBackpressureEnabled(true)
                .build();

        final TaskSchedulerType type = TaskSchedulerType.valueOf(typeString);

        // In this test, wires A and B are connected to the input of wire C, which has a maximum capacity.
        // Wire A respects back pressure, but wire B uses injection and can ignore it.

        final int partialWork = 5;
        final TaskScheduler<Integer> taskSchedulerA = model.<Integer>schedulerBuilder("A")
                .withType(type)
                .withUnhandledTaskCapacity(UNLIMITED_CAPACITY)
                .build();
        final BindableInputWire<Integer, Integer> inA = taskSchedulerA.buildInputWire("inA");

        final TaskScheduler<Integer> taskSchedulerB = model.<Integer>schedulerBuilder("B")
                .withType(type)
                .withUnhandledTaskCapacity(UNLIMITED_CAPACITY)
                .build();
        final BindableInputWire<Integer, Integer> inB = taskSchedulerB.buildInputWire("inB");

        final TaskScheduler<Void> taskSchedulerC = model.<Void>schedulerBuilder("C")
                .withType(type)
                .withUnhandledTaskCapacity(10)
                .build();
        final BindableInputWire<Integer, Void> inC = taskSchedulerC.buildInputWire("inC");

        taskSchedulerA.getOutputWire().solderTo(inC); // respects capacity
        taskSchedulerB.getOutputWire().solderTo(inC, SolderType.INJECT); // ignores capacity

        final AtomicInteger countA = new AtomicInteger();
        final FunctionWithExecutionControl<Integer, Integer> handlerA = FunctionWithExecutionControl.unBlocked(x2 -> {
            countA.set(hash32(countA.get(), x2));
            return x2;
        });

        final AtomicInteger countB = new AtomicInteger();
        final FunctionWithExecutionControl<Integer, Integer> handlerB = FunctionWithExecutionControl.unBlocked(x1 -> {
            countB.set(hash32(countB.get(), x1));
            return x1;
        });

        final AtomicInteger sumC = new AtomicInteger();
        final ConsumerWithCompletionControl<Integer> handlerC = ConsumerWithCompletionControl.blocked(sumC::getAndAdd);
        inA.bind(handlerA);
        inB.bind(handlerB);
        inC.bindConsumer(handlerC);

        model.start();

        // Add 5 elements to A and B. This will fill C's capacity.
        int expectedCount = 0;
        int expectedSum = 0;
        for (int i = 0; i < partialWork; i++) {
            inA.put(i);
            inB.put(i);
            expectedCount = hash32(expectedCount, i);
            expectedSum += 2 * i;
        }

        handlerA.executionControl().await(partialWork, AWAIT_MAX_DURATION);
        handlerB.executionControl().await(partialWork, AWAIT_MAX_DURATION);

        // Eventually, C should have 10 things that have not yet been fully processed.
        assertUnprocessedTasksValueIs(taskSchedulerC, partialWork * 2);
        assertEquals(expectedCount, countA.get());
        assertEquals(expectedCount, countB.get());

        // Push some more data into A and B. A will get stuck trying to push it to C.
        inA.put(partialWork);
        inB.put(partialWork);
        expectedCount = hash32(expectedCount, partialWork);
        expectedSum += 2 * partialWork;

        handlerA.executionControl().await(1, AWAIT_MAX_DURATION);
        handlerB.executionControl().await(1, AWAIT_MAX_DURATION);
        assertEquals(expectedCount, countA.get(), "A should have processed task");
        assertEquals(expectedCount, countB.get(), "B should have processed task");

        // If we wait some time, the task from B should have increased C's count to 11, but the task from A
        // should have been unable to increase C's count. We need to do greater equals than since a failed
        // insertion may briefly push the count up to 12 (although it should always fall immediately after).
        assertUnprocessedTasksValueIs(taskSchedulerC, partialWork * 2 + 1);
        // Push some more data into A and B. A will be unable to process it because it's still
        // stuck pushing the previous value.
        inA.put(6);
        inB.put(6);
        final int expectedCountAfterHandling6 = hash32(expectedCount, 6);
        expectedSum += 2 * 6;

        handlerB.executionControl().await(1, AWAIT_MAX_DURATION);
        assertEquals(expectedCountAfterHandling6, countB.get(), "B should have processed task");

        // Even if we wait, (A) should not have been able to process the task.
        sleep(50);
        assertEquals(expectedCount, countA.get());
        assertUnprocessedTasksValueIs(taskSchedulerC, partialWork * 2 + 2);

        // Releasing the signal should allow data to flow through C.
        handlerC.executionControl().unblock();
        handlerA.executionControl().await(1, AWAIT_MAX_DURATION);
        handlerC.executionControl().await(partialWork * 2 + 2 + 2, AWAIT_MAX_DURATION);
        assertEquals(expectedSum, sumC.get());
        assertEquals(expectedCountAfterHandling6, countA.get());

        model.stop();
    }

    /**
     * When a handler returns null, the wire should not forward the null value to the next wire.
     */
    @ParameterizedTest
    @ValueSource(strings = {"SEQUENTIAL", "SEQUENTIAL_THREAD"})
    void discardNullValuesInWiresTest(final String typeString) {
        this.model = TestWiringModelBuilder.create();
        final TaskSchedulerType type = TaskSchedulerType.valueOf(typeString);

        final TaskScheduler<Integer> taskSchedulerA =
                model.<Integer>schedulerBuilder("A").withType(type).build();
        final TaskScheduler<Integer> taskSchedulerB =
                model.<Integer>schedulerBuilder("B").withType(type).build();
        final TaskScheduler<Integer> taskSchedulerC =
                model.<Integer>schedulerBuilder("C").withType(type).build();
        final TaskScheduler<Void> taskSchedulerD =
                model.<Void>schedulerBuilder("D").withType(type).build();

        final BindableInputWire<Integer, Integer> inputA = taskSchedulerA.buildInputWire("inputA");
        final BindableInputWire<Integer, Integer> inputB = taskSchedulerB.buildInputWire("inputB");
        final BindableInputWire<Integer, Integer> inputC = taskSchedulerC.buildInputWire("inputC");
        final BindableInputWire<Integer, Void> inputD = taskSchedulerD.buildInputWire("inputD");

        taskSchedulerA.getOutputWire().solderTo(inputB);
        taskSchedulerB.getOutputWire().solderTo(inputC);
        taskSchedulerC.getOutputWire().solderTo(inputD);

        final AtomicInteger countA = new AtomicInteger();
        final AtomicInteger countB = new AtomicInteger();
        final AtomicInteger countC = new AtomicInteger();
        final AtomicInteger countD = new AtomicInteger();

        final FunctionWithExecutionControl<Integer, Integer> handlerA = FunctionWithExecutionControl.unBlocked(x3 -> {
            countA.set(hash32(countA.get(), x3));
            if (x3 % 3 == 0) {
                return null;
            }
            return x3;
        });

        final FunctionWithExecutionControl<Integer, Integer> handlerB = FunctionWithExecutionControl.unBlocked(x2 -> {
            countB.set(hash32(countB.get(), x2));
            if (x2 % 5 == 0) {
                return null;
            }
            return x2;
        });

        final FunctionWithExecutionControl<Integer, Integer> handlerC = FunctionWithExecutionControl.unBlocked(x1 -> {
            countC.set(hash32(countC.get(), x1));
            if (x1 % 7 == 0) {
                return null;
            }
            return x1;
        });

        final ConsumerWithCompletionControl<Integer> handlerD =
                ConsumerWithCompletionControl.unblocked(x -> countD.set(hash32(countD.get(), x)));
        inputA.bind(handlerA);
        inputB.bind(handlerB);
        inputC.bind(handlerC);
        inputD.bindConsumer(handlerD);

        model.start();

        int expectedCountA = 0;
        int expectedCountB = 0;
        int expectedCountC = 0;
        int expectedCountD = 0;

        int timesA = 0;
        int timesB = 0;
        int timesC = 0;
        int timesD = 0;

        for (int i = 0; i < DEFAULT_OPERATIONS; i++) {
            inputA.put(i);
            expectedCountA = hash32(expectedCountA, i);
            timesA++;
            if (i % 3 == 0) {
                timesB++;
                continue;
            }
            expectedCountB = hash32(expectedCountB, i);
            if (i % 5 == 0) {
                timesC++;
                continue;
            }
            expectedCountC = hash32(expectedCountC, i);
            if (i % 7 == 0) {
                timesD++;
                continue;
            }
            expectedCountD = hash32(expectedCountD, i);
        }

        handlerA.executionControl().await(timesA, AWAIT_MAX_DURATION);
        handlerB.executionControl().await(timesA - timesB, AWAIT_MAX_DURATION);
        handlerC.executionControl().await(timesA - timesB - timesC, AWAIT_MAX_DURATION);
        handlerD.executionControl().await(timesA - timesB - timesC - timesD, AWAIT_MAX_DURATION);
        assertEquals(expectedCountA, countA.get(), "Wire sum did not match expected sum");
        assertEquals(expectedCountB, countB.get(), "Wire sum did not match expected sum");
        assertEquals(expectedCountC, countC.get(), "Wire sum did not match expected sum");
        assertEquals(expectedCountD, countD.get(), "Wire sum did not match expected sum");

        model.stop();
    }

    /**
     * Make sure we don't crash when metrics are enabled. Might be nice to eventually validate the metrics, but right
     * now the metrics framework makes it complex to do so.
     */
    @ParameterizedTest
    @ValueSource(strings = {"SEQUENTIAL", "SEQUENTIAL_THREAD"})
    void metricsEnabledTest(final String typeString) {
        this.model = TestWiringModelBuilder.create();
        final TaskSchedulerType type = TaskSchedulerType.valueOf(typeString);

        final TaskScheduler<Integer> taskSchedulerA = model.<Integer>schedulerBuilder("A")
                .withType(type)
                .withBusyFractionMetricsEnabled(true)
                .withUnhandledTaskMetricEnabled(true)
                .build();
        final TaskScheduler<Integer> taskSchedulerB = model.<Integer>schedulerBuilder("B")
                .withType(type)
                .withBusyFractionMetricsEnabled(true)
                .withUnhandledTaskMetricEnabled(false)
                .build();
        final TaskScheduler<Integer> taskSchedulerC = model.<Integer>schedulerBuilder("C")
                .withType(type)
                .withBusyFractionMetricsEnabled(false)
                .withUnhandledTaskMetricEnabled(true)
                .build();
        final TaskScheduler<Void> taskSchedulerD = model.<Void>schedulerBuilder("D")
                .withType(type)
                .withBusyFractionMetricsEnabled(false)
                .withUnhandledTaskMetricEnabled(false)
                .build();

        final BindableInputWire<Integer, Integer> inputA = taskSchedulerA.buildInputWire("inputA");
        final BindableInputWire<Integer, Integer> inputB = taskSchedulerB.buildInputWire("inputB");
        final BindableInputWire<Integer, Integer> inputC = taskSchedulerC.buildInputWire("inputC");
        final BindableInputWire<Integer, Void> inputD = taskSchedulerD.buildInputWire("inputD");

        taskSchedulerA.getOutputWire().solderTo(inputB);
        taskSchedulerB.getOutputWire().solderTo(inputC);
        taskSchedulerC.getOutputWire().solderTo(inputD);

        final AtomicInteger countA = new AtomicInteger();
        final AtomicInteger countB = new AtomicInteger();
        final AtomicInteger countC = new AtomicInteger();
        final AtomicInteger countD = new AtomicInteger();

        final FunctionWithExecutionControl<Integer, Integer> handlerA = FunctionWithExecutionControl.unBlocked(x3 -> {
            countA.set(hash32(countA.get(), x3));
            return x3;
        });

        final FunctionWithExecutionControl<Integer, Integer> handlerB = FunctionWithExecutionControl.unBlocked(x2 -> {
            countB.set(hash32(countB.get(), x2));
            return x2;
        });

        final FunctionWithExecutionControl<Integer, Integer> handlerC = FunctionWithExecutionControl.unBlocked(x1 -> {
            countC.set(hash32(countC.get(), x1));
            return x1;
        });

        final ConsumerWithCompletionControl<Integer> handlerD =
                ConsumerWithCompletionControl.unblocked(x -> countD.set(hash32(countD.get(), x)));

        inputA.bind(handlerA);
        inputB.bind(handlerB);
        inputC.bind(handlerC);
        inputD.bindConsumer(handlerD);

        model.start();

        int expectedCount = 0;

        for (int i = 0; i < DEFAULT_OPERATIONS; i++) {
            inputA.put(i);
            expectedCount = hash32(expectedCount, i);
        }

        handlerA.executionControl().await(DEFAULT_OPERATIONS, AWAIT_MAX_DURATION);
        handlerB.executionControl().await(DEFAULT_OPERATIONS, AWAIT_MAX_DURATION);
        handlerC.executionControl().await(DEFAULT_OPERATIONS, AWAIT_MAX_DURATION);
        handlerD.executionControl().await(DEFAULT_OPERATIONS, AWAIT_MAX_DURATION);

        assertEquals(expectedCount, countD.get(), "Wire sum did not match expected sum");
        assertEquals(expectedCount, countA.get());
        assertEquals(expectedCount, countB.get());
        assertEquals(expectedCount, countC.get());

        model.stop();
    }

    @ParameterizedTest
    @ValueSource(strings = {"SEQUENTIAL", "SEQUENTIAL_THREAD"})
    void multipleOutputChannelsTest(final String typeString) {
        this.model = TestWiringModelBuilder.create();
        final TaskSchedulerType type = TaskSchedulerType.valueOf(typeString);

        final TaskScheduler<Integer> taskSchedulerA =
                model.<Integer>schedulerBuilder("A").withType(type).build();
        final BindableInputWire<Integer, Integer> aIn = taskSchedulerA.buildInputWire("aIn");
        final StandardOutputWire<Boolean> aOutBoolean = taskSchedulerA.buildSecondaryOutputWire();
        final StandardOutputWire<String> aOutString = taskSchedulerA.buildSecondaryOutputWire();

        final TaskScheduler<Void> taskSchedulerB =
                model.<Void>schedulerBuilder("B").withType(type).build();
        final BindableInputWire<Integer, Void> bInInteger = taskSchedulerB.buildInputWire("bIn1");
        final BindableInputWire<Boolean, Void> bInBoolean = taskSchedulerB.buildInputWire("bIn2");
        final BindableInputWire<String, Void> bInString = taskSchedulerB.buildInputWire("bIn3");

        taskSchedulerA.getOutputWire().solderTo(bInInteger);
        aOutBoolean.solderTo(bInBoolean);
        aOutString.solderTo(bInString);

        final FunctionWithExecutionControl<Integer, Integer> handlerA = FunctionWithExecutionControl.unBlocked(x1 -> {
            if (x1 % 2 == 0) {
                aOutBoolean.forward(x1 % 3 == 0);
            }

            if (x1 % 5 == 0) {
                aOutString.forward(Integer.toString(x1));
            }

            return x1;
        });

        final AtomicInteger count = new AtomicInteger();
        final ConsumerWithCompletionControl<Boolean> handlerBBool =
                ConsumerWithCompletionControl.unblocked(x2 -> count.set(hash32(count.get(), x2 ? 1 : 0)));
        final ConsumerWithCompletionControl<String> handlerBString =
                ConsumerWithCompletionControl.unblocked(x1 -> count.set(hash32(count.get(), x1.hashCode())));
        final ConsumerWithCompletionControl<Integer> handlerBInt =
                ConsumerWithCompletionControl.unblocked(x -> count.set(hash32(count.get(), x)));

        aIn.bind(handlerA);
        bInBoolean.bindConsumer(handlerBBool);
        bInString.bindConsumer(handlerBString);
        bInInteger.bindConsumer(handlerBInt);

        model.start();

        int expectedCount = 0;
        int stringTimes = 0;
        int booleanTimes = 0;
        for (int i = 0; i < DEFAULT_OPERATIONS; i++) {
            aIn.put(i);
            if (i % 2 == 0) {
                expectedCount = hash32(expectedCount, i % 3 == 0 ? 1 : 0);
                booleanTimes++;
            }
            if (i % 5 == 0) {
                expectedCount = hash32(expectedCount, Integer.toString(i).hashCode());
                stringTimes++;
            }
            expectedCount = hash32(expectedCount, i);
        }

        handlerA.executionControl().await(DEFAULT_OPERATIONS, AWAIT_MAX_DURATION);
        handlerBInt.executionControl().await(DEFAULT_OPERATIONS, AWAIT_MAX_DURATION);
        handlerBString.executionControl().await(stringTimes, AWAIT_MAX_DURATION);
        handlerBBool.executionControl().await(booleanTimes, AWAIT_MAX_DURATION);
        assertEquals(expectedCount, count.get(), "Wire count did not match expected");

        model.stop();
    }

    @ParameterizedTest
    @ValueSource(strings = {"SEQUENTIAL", "SEQUENTIAL_THREAD"})
    void externalBackPressureTest(final String typeString) {
        this.model = TestWiringModelBuilder.create();
        final TaskSchedulerType type = TaskSchedulerType.valueOf(typeString);

        // There are three components, A, B, and C.
        // We want to control the number of elements in all three, not individually.

        final ObjectCounter counter = new BackpressureObjectCounter("test", 10, Duration.ofMillis(1));

        final TaskScheduler<Integer> taskSchedulerA = model.<Integer>schedulerBuilder("A")
                .withType(type)
                .withOnRamp(counter)
                .withExternalBackPressure(true)
                .withUnhandledTaskCapacity(UNLIMITED_CAPACITY)
                .build();
        final BindableInputWire<Integer, Integer> aIn = taskSchedulerA.buildInputWire("aIn");

        final TaskScheduler<Integer> taskSchedulerB = model.<Integer>schedulerBuilder("B")
                .withType(type)
                .withExternalBackPressure(true)
                .withUnhandledTaskCapacity(UNLIMITED_CAPACITY)
                .build();
        final BindableInputWire<Integer, Integer> bIn = taskSchedulerB.buildInputWire("bIn");

        final TaskScheduler<Void> taskSchedulerC = model.<Void>schedulerBuilder("C")
                .withType(type)
                .withOffRamp(counter)
                .withExternalBackPressure(true)
                .withUnhandledTaskCapacity(UNLIMITED_CAPACITY)
                .build();
        final BindableInputWire<Integer, Void> cIn = taskSchedulerC.buildInputWire("cIn");

        taskSchedulerA.getOutputWire().solderTo(bIn);
        taskSchedulerB.getOutputWire().solderTo(cIn);

        final AtomicInteger countA = new AtomicInteger();
        final FunctionWithExecutionControl<Integer, Integer> handlerA = FunctionWithExecutionControl.blocked(x -> {
            countA.set(hash32(countA.get(), x));
            return x;
        });

        final AtomicInteger countB = new AtomicInteger();
        final FunctionWithExecutionControl<Integer, Integer> handlerB = FunctionWithExecutionControl.blocked(x -> {
            countB.set(hash32(countB.get(), x));
            return x;
        });

        final AtomicInteger countC = new AtomicInteger();
        final ConsumerWithCompletionControl<Integer> handlerC =
                ConsumerWithCompletionControl.blocked(x -> countC.set(hash32(countC.get(), x)));

        aIn.bind(handlerA);
        bIn.bind(handlerB);
        cIn.bindConsumer(handlerC);

        model.start();

        // Add enough data to fill all available capacity.
        int expectedCount = 0;
        for (int i = 0; i < 10; i++) {
            aIn.put(i);
            expectedCount = hash32(expectedCount, i);
        }

        final AtomicBoolean moreWorkInserted = new AtomicBoolean(false);
        final RunnableCompletionControl producer = RunnableCompletionControl.unblocked(() -> {
            aIn.put(10);
            moreWorkInserted.set(true);
        });

        final Thread producerThread = producer.start();
        expectedCount = hash32(expectedCount, 10);

        assertEquals(10, counter.getCount());

        // Work is currently stuck at A. No matter how much time passes, no new work should be added.
        sleep(50);
        assertFalse(moreWorkInserted.get());
        assertEquals(10, counter.getCount());
        assertEventuallyEquals(State.TIMED_WAITING, producerThread::getState, "Producer thread was not blocked");

        // Unblock A. Work will flow forward and get blocked at B. No matter how much time passes, no new work should
        // be added.
        handlerA.executionControl().unblock();
        handlerA.executionControl().await(10, AWAIT_MAX_DURATION);
        sleep(50);
        assertFalse(moreWorkInserted.get());
        assertEquals(10, counter.getCount());

        // Unblock B. Work will flow forward and get blocked at (C). No matter how much time passes, no new work should
        // be added.
        handlerB.executionControl().unblock();
        handlerB.executionControl().await(10, AWAIT_MAX_DURATION);
        sleep(50);
        assertFalse(moreWorkInserted.get());

        // Unblock (C). For pending work
        handlerC.executionControl().unblock();

        producer.waitIsFinished(AWAIT_MAX_DURATION);
        handlerC.executionControl().await(11, AWAIT_MAX_DURATION);
        assertEventuallyEquals(0L, counter::getCount, "Counter should be empty");
        assertEquals(expectedCount, countA.get(), "A should have processed task");
        assertEquals(expectedCount, countB.get(), "B should have processed task");
        assertEquals(expectedCount, countC.get(), "C should have processed task");
        assertTrue(moreWorkInserted.get());
        assertEventuallyEquals(State.TERMINATED, producerThread::getState, "Producer thread was not terminated");
        model.stop();
    }

    @ParameterizedTest
    @ValueSource(strings = {"SEQUENTIAL", "SEQUENTIAL_THREAD"})
    void multipleCountersInternalBackpressureTest(final String typeString) {

        this.model = WiringModelBuilder.create(NO_OP_METRICS, Time.getCurrent())
                .withHardBackpressureEnabled(true)
                .build();

        final TaskSchedulerType type = TaskSchedulerType.valueOf(typeString);

        // There are three components, A, B, and C.
        // The pipeline as a whole has a capacity of 10. Each step individually has a capacity of 5;

        final ObjectCounter counter = new BackpressureObjectCounter("test", 10, Duration.ofMillis(1));

        final TaskScheduler<Integer> taskSchedulerA = model.<Integer>schedulerBuilder("A")
                .withType(type)
                .withOnRamp(counter)
                .withExternalBackPressure(true)
                .withUnhandledTaskCapacity(5)
                .build();
        final BindableInputWire<Integer, Integer> aIn = taskSchedulerA.buildInputWire("aIn");

        final TaskScheduler<Integer> taskSchedulerB = model.<Integer>schedulerBuilder("B")
                .withType(type)
                .withExternalBackPressure(true)
                .withUnhandledTaskCapacity(5)
                .build();
        final BindableInputWire<Integer, Integer> bIn = taskSchedulerB.buildInputWire("bIn");

        final TaskScheduler<Void> taskSchedulerC = model.<Void>schedulerBuilder("C")
                .withType(type)
                .withOffRamp(counter)
                .withExternalBackPressure(true)
                .withUnhandledTaskCapacity(5)
                .build();
        final BindableInputWire<Integer, Void> cIn = taskSchedulerC.buildInputWire("cIn");

        taskSchedulerA.getOutputWire().solderTo(bIn);
        taskSchedulerB.getOutputWire().solderTo(cIn);

        final AtomicInteger countA = new AtomicInteger();
        final FunctionWithExecutionControl<Integer, Integer> handlerA = FunctionWithExecutionControl.blocked(x -> {
            countA.set(hash32(countA.get(), x));
            return x;
        });

        final AtomicInteger countB = new AtomicInteger();
        final FunctionWithExecutionControl<Integer, Integer> handlerB = FunctionWithExecutionControl.blocked(x -> {
            countB.set(hash32(countB.get(), x));
            return x;
        });

        final AtomicInteger countC = new AtomicInteger();
        final ConsumerWithCompletionControl<Integer> handlerC =
                ConsumerWithCompletionControl.blocked(x -> countC.set(hash32(countC.get(), x)));

        aIn.bind(handlerA);
        bIn.bind(handlerB);
        cIn.bindConsumer(handlerC);

        int expectedCount = 0;
        for (int i = 0; i < 11; i++) {
            expectedCount = hash32(expectedCount, i);
        }

        // This thread wants to add 11 tasks to the pipeline.
        final AtomicBoolean allWorkInserted = new AtomicBoolean(false);
        final RunnableCompletionControl producer = RunnableCompletionControl.unblocked(() -> {
            for (int i = 0; i < 11; i++) {
                aIn.put(i);
            }
            allWorkInserted.set(true);
        });

        model.start();
        final Thread producerThread = producer.start();

        // Work is currently stuck at A. No matter how much time passes, we should not be able to exceed A's capacity.
        assertEventuallyEquals(State.TIMED_WAITING, producerThread::getState, "Producer thread was not blocked");
        assertFalse(allWorkInserted.get());
        assertTrue(5 >= counter.getCount());

        // Unblock (A). Work will flow forward and get blocked at B. A can fit 5 items, B can fit another 5.
        handlerA.executionControl().unblock();
        sleep(50);
        assertFalse(allWorkInserted.get());
        assertEquals(10, counter.getCount()); // This one failed

        // Unblock (B). Work will flow forward and get blocked at C. We shouldn't be able to add additional items
        // since that would violate the global capacity.
        handlerB.executionControl().unblock();
        sleep(50);
        assertFalse(allWorkInserted.get());
        assertEquals(10, counter.getCount());

        // Unblock (C). The entire pipeline is now unblocked, and new things will be added.
        handlerC.executionControl().unblock();

        producer.waitIsFinished(AWAIT_MAX_DURATION);
        assertTrue(allWorkInserted.get(), "All work should have been inserted");
        handlerA.executionControl().await(11, AWAIT_MAX_DURATION);
        handlerB.executionControl().await(11, AWAIT_MAX_DURATION);
        handlerC.executionControl().await(11, AWAIT_MAX_DURATION);
        assertEventuallyEquals(0L, counter::getCount, "Counter should be empty");
        assertEquals(expectedCount, countA.get(), "A should have processed task");
        assertEquals(expectedCount, countB.get(), "B should have processed task");
        assertEquals(expectedCount, countC.get(), "C should have processed task");

        assertEventuallyEquals(State.TERMINATED, producerThread::getState, "Producer thread was not terminated");
        model.stop();
    }

    @ParameterizedTest
    @ValueSource(strings = {"SEQUENTIAL", "SEQUENTIAL_THREAD"})
    void offerSolderingTest(final String typeString) {

        this.model = WiringModelBuilder.create(NO_OP_METRICS, Time.getCurrent())
                .withHardBackpressureEnabled(true)
                .build();

        final TaskSchedulerType type = TaskSchedulerType.valueOf(typeString);

        final int capacity = 10;
        final TaskScheduler<Integer> schedulerA = model.<Integer>schedulerBuilder("A")
                .withType(type)
                .withUnhandledTaskCapacity(capacity)
                .build();
        final BindableInputWire<Integer, Integer> inputA = schedulerA.buildInputWire("inputA");

        final TaskScheduler<Void> schedulerB = model.<Void>schedulerBuilder("B")
                .withType(type)
                .withUnhandledTaskCapacity(capacity)
                .build();
        final BindableInputWire<Integer, Void> inputB = schedulerB.buildInputWire("inputB");

        schedulerA.getOutputWire().solderTo(inputB, SolderType.OFFER);

        final AtomicInteger countA = new AtomicInteger();
        final FunctionWithExecutionControl<Integer, Integer> handlerA = FunctionWithExecutionControl.unBlocked(x1 -> {
            countA.set(hash32(countA.get(), x1));
            return x1;
        });

        final AtomicInteger countB = new AtomicInteger();
        final ConsumerWithCompletionControl<Integer> handlerB =
                ConsumerWithCompletionControl.blocked(x -> countB.set(hash32(countB.get(), x)));

        inputA.bind(handlerA);
        inputB.bindConsumer(handlerB);

        model.start();

        // Fill up B's buffer.
        int expectedCountA = 0;
        int expectedCountB = 0;
        for (int i = 0; i < capacity; i++) {
            inputA.put(i);
            expectedCountA = hash32(expectedCountA, i);
            expectedCountB = hash32(expectedCountB, i);
        }

        // Add more than B is willing to accept. B will drop all those items
        for (int i = capacity; i < 20; i++) {
            inputA.put(i);
            expectedCountA = hash32(expectedCountA, i);
        }

        handlerA.executionControl().await(10, AWAIT_MAX_DURATION);
        // Wait until A has handled all of its tasks.
        assertUnprocessedTasksValueIs(schedulerA, 0L);
        assertEquals(expectedCountA, countA.get());
        // B should not have processed any tasks.
        assertUnprocessedTasksValueIs(schedulerB, 10L);
        assertEquals(0, countB.get());

        // Release the signal and allow B to process tasks.
        handlerB.executionControl().unblock();
        handlerB.executionControl().await(10, AWAIT_MAX_DURATION);
        assertUnprocessedTasksValueIs(schedulerB, 0L);
        assertEquals(expectedCountB, countB.get());

        // Now, add some more data to A. That data should flow to B as well.
        for (int i = 30, j = 0; i < 40; i++, j++) {
            inputA.put(i);
            expectedCountA = hash32(expectedCountA, i);
            expectedCountB = hash32(expectedCountB, i);
        }

        handlerA.executionControl().await(10, AWAIT_MAX_DURATION);
        handlerB.executionControl().await(10, AWAIT_MAX_DURATION);
        assertUnprocessedTasksValueIs(schedulerA, 0L);
        assertUnprocessedTasksValueIs(schedulerB, 0L);

        assertEquals(expectedCountA, countA.get());
        assertEquals(expectedCountB, countB.get());

        model.stop();
    }

    /**
     *  This test asserts that a task scheduler being squelched does not accept new tasks.
     */
    @ParameterizedTest
    @ValueSource(strings = {"SEQUENTIAL", "SEQUENTIAL_THREAD"})
    void squelching(final String typeString) {
        this.model = TestWiringModelBuilder.create();
        final TaskSchedulerType type = TaskSchedulerType.valueOf(typeString);
        final int totalTasks = 30;
        // Total unprocessed tasks
        final AtomicInteger unprocessedTasks = new AtomicInteger(totalTasks);

        final ConsumerWithCompletionControl<Integer> taskHandler =
                ConsumerWithCompletionControl.blocked(x -> unprocessedTasks.decrementAndGet());

        final TaskScheduler<Void> taskScheduler = model.<Void>schedulerBuilder("test")
                .withType(type)
                .withUnhandledTaskCapacity(100)
                .withFlushingEnabled(true)
                .withSquelchingEnabled(true)
                .build();
        final BindableInputWire<Integer, Void> inputWire = taskScheduler.buildInputWire("channel");
        inputWire.bindConsumer(taskHandler);

        model.start();
        // This runnable (for reuse purposes) adds tasks to the scheduler
        // we test all 3 methods to push tasks to the scheduler
        final RunnableCompletionControl producer = RunnableCompletionControl.unblocked(() -> {
            for (int i = 0; i < 30; i++) {
                // we test all 3 methods to push tasks to the scheduler
                if (i < 10) {
                    inputWire.put(i);
                } else if (i < 20) {
                    inputWire.offer(i);
                } else {
                    inputWire.inject(i);
                }
            }
        });

        taskHandler.executionControl().unblock();
        // add tasks
        producer.run();
        // allow them to run
        producer.waitIsFinished(AWAIT_MAX_DURATION);
        taskHandler.executionControl().await(totalTasks, AWAIT_MAX_DURATION);
        // check there are no unprocessed tasks, according to the framework
        assertEquals(0L, taskScheduler.getUnprocessedTaskCount());
        // check there are no unprocessed tasks, according to the test counter
        assertEquals(0L, unprocessedTasks.get());

        // Enabling squelching should make should ignore the task and not execute it.
        taskScheduler.startSquelching();
        // the tasks should not be executed.
        unprocessedTasks.set(totalTasks);
        // The scheduler is squelching, tasks should not be executed.
        producer.run();
        assertEquals(totalTasks, unprocessedTasks.get());

        // Now, despite being squelched, the fmw will generate 30 tasks that will be
        // scheduled and executed. The first instruction of each task is to verify if squelching is active
        // and return immediately if so. In the process, onRamp and offRamp will still be invoked,
        // modifying the count of UnprocessedTaskCount until, given enough time, it gets to 0.
        // Thus, asserting the value getUnprocessedTaskCount at this point is bound to be flaky.
        // It seems incorrect that we still process the tasks in the scheduler just to reject them if squelching is on.

        // flush causes the unprocessedTaskCount to return to 0.
        taskScheduler.flush();
        assertUnprocessedTasksValueIs(taskScheduler, 0L);

        // Remove all permissions to start tasks.
        taskHandler.executionControl().block();

        // Enable the scheduler to start processing tasks again.
        taskScheduler.stopSquelching();
        unprocessedTasks.set(totalTasks);
        producer.run();
        producer.waitIsFinished(AWAIT_MAX_DURATION);
        assertUnprocessedTasksValueIs(taskScheduler, unprocessedTasks.get());
        taskHandler.executionControl().unblock();
        taskHandler.executionControl().await(totalTasks, AWAIT_MAX_DURATION);
        assertEquals(0, unprocessedTasks.get());
        assertUnprocessedTasksValueIs(taskScheduler, 0);

        model.stop();
    }

    @AfterEach
    void tearDown() {
        try {
            model.stop();
        } catch (final Exception e) {
            // DO NOTHING
        }
    }

    private static void sleep(long millis) {

        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            fail("Unexpected interruption", e);
        }
    }

    /**
     * The operation: TaskScheduler::getUnprocessedTaskCount manifests its results some time after the handler is correctly executed.
     * So even waiting for the handler to finish is not enough to be able to check that the values are the expected ones.
     * One possibility is to wait on each test, but this approach is somehow better.
     *
     * @see AssertionUtils#assertEventuallyEquals(Object, Supplier, Duration, String)
     */
    private static void assertUnprocessedTasksValueIs(
            final @NonNull TaskScheduler<?> taskScheduler, final long expected) {
        assertEventuallyEquals(
                expected,
                taskScheduler::getUnprocessedTaskCount,
                "Wire unprocessed task count did not match expected value:" + taskScheduler.getName());
    }

    /**
     * For all terminal operations that the framework produces after executing the handler,
     * we are forced to check this way.
     *
     * @see AssertionUtils#assertEventuallyEquals(Object, Supplier, Duration, String)
     */
    private static <T> void assertEventuallyEquals(
            final @NonNull T expected, @NonNull final Supplier<T> actual, @NonNull final String message) {
        AssertionUtils.assertEventuallyEquals(expected, actual, AWAIT_MAX_DURATION, message);
    }
}
