// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.stream;

import static com.swirlds.common.test.fixtures.RandomUtils.randomHash;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.swirlds.base.time.Time;
import com.swirlds.common.metrics.noop.NoOpMetrics;
import com.swirlds.common.stream.MultiStream;
import com.swirlds.common.stream.RunningEventHashOverride;
import com.swirlds.common.test.fixtures.RandomUtils;
import com.swirlds.component.framework.component.ComponentWiring;
import com.swirlds.component.framework.model.WiringModel;
import com.swirlds.component.framework.model.WiringModelBuilder;
import com.swirlds.component.framework.schedulers.builders.TaskSchedulerType;
import java.util.List;
import java.util.Random;
import org.hiero.consensus.model.crypto.Hash;
import org.hiero.consensus.model.event.CesEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ConsensusEventStreamTest {
    private static final MultiStream<CesEvent> multiStreamMock = mock(MultiStream.class);
    private static final ConsensusEventStream CONSENSUS_EVENT_STREAM = new DefaultConsensusEventStream(
            Time.getCurrent(), multiStreamMock, ConsensusEventStreamTest::isFreezeEvent);

    private static final CesEvent freezeEvent = mock(CesEvent.class);

    @Test
    void addEventTest() {
        final int nonFreezeEventsNum = 10;
        for (int i = 0; i < nonFreezeEventsNum; i++) {
            final CesEvent event = mock(CesEvent.class);
            CONSENSUS_EVENT_STREAM.addEvents(List.of(event));

            verify(multiStreamMock).addObject(event);
            // for non-freeze event, multiStream should not be closed after adding it
            verify(multiStreamMock, never()).close();
        }

        final WiringModel model =
                WiringModelBuilder.create(new NoOpMetrics(), Time.getCurrent()).build();
        final ComponentWiring<ConsensusEventStream, Void> wiring = new ComponentWiring<>(
                model,
                ConsensusEventStream.class,
                model.<Void>schedulerBuilder("eventStreamManager")
                        .withType(TaskSchedulerType.DIRECT)
                        .build());
        wiring.bind(CONSENSUS_EVENT_STREAM);

        wiring.getInputWire(ConsensusEventStream::addEvents).inject(List.of(freezeEvent));
        verify(multiStreamMock).addObject(freezeEvent);
        // for freeze event, multiStream should be closed after adding it
        verify(multiStreamMock).close();

        final CesEvent eventAddAfterFrozen = mock(CesEvent.class);
        CONSENSUS_EVENT_STREAM.addEvents(List.of(eventAddAfterFrozen));
        // after frozen, when adding event to the EventStreamManager, multiStream.add(event) should not be called
        verify(multiStreamMock, never()).addObject(eventAddAfterFrozen);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void setStartWriteAtCompleteWindowTest(final boolean startWriteAtCompleteWindow) {
        final Random random = RandomUtils.getRandomPrintSeed();
        final Hash runningHash = randomHash(random);

        final WiringModel model =
                WiringModelBuilder.create(new NoOpMetrics(), Time.getCurrent()).build();
        final ComponentWiring<ConsensusEventStream, Void> wiring = new ComponentWiring<>(
                model,
                ConsensusEventStream.class,
                model.<Void>schedulerBuilder("eventStreamManager")
                        .withType(TaskSchedulerType.DIRECT)
                        .build());
        wiring.bind(CONSENSUS_EVENT_STREAM);

        wiring.getInputWire(ConsensusEventStream::legacyHashOverride)
                .inject(new RunningEventHashOverride(runningHash, startWriteAtCompleteWindow));
        verify(multiStreamMock).setRunningHash(runningHash);
    }

    /**
     * used for testing adding freeze event
     *
     * @param event the event to be added
     * @return whether
     */
    private static boolean isFreezeEvent(final CesEvent event) {
        return event == freezeEvent;
    }
}
