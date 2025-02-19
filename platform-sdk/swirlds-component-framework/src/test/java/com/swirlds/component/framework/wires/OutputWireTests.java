/*
 * Copyright (C) 2024-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.swirlds.component.framework.wires;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.component.framework.model.WiringModel;
import com.swirlds.component.framework.model.WiringModelBuilder;
import com.swirlds.component.framework.schedulers.TaskScheduler;
import com.swirlds.component.framework.schedulers.builders.TaskSchedulerType;
import com.swirlds.component.framework.wires.input.BindableInputWire;
import com.swirlds.component.framework.wires.input.InputWire;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
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
        final PlatformContext platformContext =
                TestPlatformContextBuilder.create().build();
        final WiringModel model = WiringModelBuilder.create(platformContext).build();

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
        final PlatformContext platformContext =
                TestPlatformContextBuilder.create().build();
        final WiringModel model = WiringModelBuilder.create(platformContext).build();

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
}
