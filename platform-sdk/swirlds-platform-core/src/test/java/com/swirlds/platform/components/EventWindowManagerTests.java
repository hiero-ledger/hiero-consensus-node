// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.components;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.swirlds.base.time.Time;
import com.swirlds.common.metrics.noop.NoOpMetrics;
import com.swirlds.component.framework.component.ComponentWiring;
import com.swirlds.component.framework.model.WiringModel;
import com.swirlds.component.framework.model.WiringModelBuilder;
import com.swirlds.component.framework.schedulers.builders.TaskSchedulerType;
import java.util.concurrent.atomic.AtomicReference;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.junit.jupiter.api.Test;

public class EventWindowManagerTests {

    @Test
    void WiringInputTest() {
        final EventWindowManager eventWindowManager = new DefaultEventWindowManager();
        final WiringModel model =
                WiringModelBuilder.create(new NoOpMetrics(), Time.getCurrent()).build();
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
