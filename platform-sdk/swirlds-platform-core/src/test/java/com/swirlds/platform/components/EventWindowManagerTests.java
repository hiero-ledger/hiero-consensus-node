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

package com.swirlds.platform.components;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.component.framework.component.ComponentWiring;
import com.swirlds.component.framework.model.WiringModel;
import com.swirlds.component.framework.model.WiringModelBuilder;
import com.swirlds.component.framework.schedulers.builders.TaskSchedulerType;
import com.swirlds.platform.consensus.EventWindow;
import com.swirlds.platform.internal.ConsensusRound;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

public class EventWindowManagerTests {

    @Test
    void WiringInputTest() {
        final EventWindowManager eventWindowManager = new DefaultEventWindowManager();
        final WiringModel model = WiringModelBuilder.create(
                        TestPlatformContextBuilder.create().build())
                .build();
        final ComponentWiring<EventWindowManager, EventWindow> wiring = new ComponentWiring<>(
                model,
                EventWindowManager.class,
                model.<EventWindow>schedulerBuilder("eventWindowManager")
                        .withType(TaskSchedulerType.DIRECT_THREADSAFE)
                        .build());
        wiring.bind(eventWindowManager);

        final AtomicReference<EventWindow> output = new AtomicReference<>(null);

        wiring.getOutputWire().solderTo("output", "event window", output::set);

        final EventWindow eventWindow1 = mock(EventWindow.class);
        wiring.getInputWire(EventWindowManager::updateEventWindow).inject(eventWindow1);
        assertSame(eventWindow1, output.get());

        final EventWindow eventWindow2 = mock(EventWindow.class);
        final ConsensusRound round = mock(ConsensusRound.class);
        when(round.getEventWindow()).thenReturn(eventWindow2);
        wiring.getInputWire(EventWindowManager::extractEventWindow).inject(round);
        assertSame(eventWindow2, output.get());
        assertNotSame(eventWindow1, eventWindow2);
    }
}
