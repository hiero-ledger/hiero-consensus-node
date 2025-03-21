// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.schedulers;

import static com.swirlds.common.utility.NonCryptographicHashing.hash32;
import static com.swirlds.component.framework.schedulers.builders.TaskSchedulerBuilder.UNLIMITED_CAPACITY;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.component.framework.TestWiringModelBuilder;
import com.swirlds.component.framework.model.WiringModel;
import com.swirlds.component.framework.schedulers.builders.TaskSchedulerType;
import com.swirlds.component.framework.schedulers.internal.SequentialThreadTaskScheduler;
import com.swirlds.component.framework.wires.input.BindableInputWire;
import com.swirlds.component.framework.wires.input.InputWire;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;

class SequentialThreadTaskSchedulerTests {
    List<String> names;

    @BeforeEach
    void setUp() {
        names = IntStream.range(0, 10)
                .boxed()
                .map(i -> UUID.randomUUID().toString().replace("-", ""))
                .toList();
    }

    @RepeatedTest(10000)
    void sequentialThreadTaskClosesAllThreads() {
        final WiringModel model = TestWiringModelBuilder.create();
        final TaskSchedulerType type = TaskSchedulerType.SEQUENTIAL_THREAD;

        final AtomicInteger wireValue = new AtomicInteger();
        final Consumer<Integer> handler = x -> {
            wireValue.set(hash32(wireValue.get()));
        };

        final var taskSchedulers = names.stream()
                .map(name -> model.<Void>schedulerBuilder(name)
                        .withType(type)
                        .withUnhandledTaskCapacity(UNLIMITED_CAPACITY)
                        .build())
                .toList();

        final List<InputWire<Integer>> inputWireList = new ArrayList<>();
        for (final var taskScheduler : taskSchedulers) {
            final BindableInputWire<Integer, Void> channel = taskScheduler.buildInputWire("channel");
            channel.bindConsumer(handler);
            inputWireList.add(channel);
        }

        model.start();

        for (final var inputWire : inputWireList) {
            inputWire.put(ThreadLocalRandom.current().nextInt());
        }

        for (final var name : names) {
            final Thread threadByName = getLivePlatformThreadByName(SequentialThreadTaskScheduler.THREAD_NAME_PREFIX
                    + name
                    + SequentialThreadTaskScheduler.THREAD_NAME_SUFFIX);
            assertNotNull(threadByName);
            assertTrue(threadByName.isAlive());
        }

        model.stop();
    }

    @AfterEach
    void tierDown() {
        for (final var name : names) {
            final Thread threadByName = getLivePlatformThreadByName(SequentialThreadTaskScheduler.THREAD_NAME_PREFIX
                    + name
                    + SequentialThreadTaskScheduler.THREAD_NAME_SUFFIX);
            if (threadByName != null) {
                threadByName.interrupt();
            }
        }
    }
    /**
     * Search for a particular alive platform thread by its name.
     * @param name of the platform thread to locate
     * @return the thread if exists and is alive, null otherwise.
     */
    @Nullable
    private static Thread getLivePlatformThreadByName(@NonNull final String name) {
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.getName().equals(name)) {
                return t;
            }
        }
        return null;
    }
}
