// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.pces.impl;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.swirlds.base.test.fixtures.time.FakeTime;
import org.hiero.consensus.io.IOIterator;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.pces.impl.common.PcesFileTracker;
import org.hiero.consensus.pces.impl.common.PcesMultiFileIterator;
import org.hiero.consensus.pces.impl.replayer.PcesReplayerWiring;
import org.hiero.consensus.status.monitor.StatusMonitorModule;
import org.hiero.consensus.status.monitor.actions.DoneReplayingEventsAction;
import org.hiero.consensus.status.monitor.actions.PlatformStatusAction;
import org.hiero.consensus.status.monitor.actions.StartedReplayingEventsAction;
import org.hiero.consensus.wiring.framework.wires.input.InputWire;
import org.hiero.consensus.wiring.framework.wires.output.OutputWire;
import org.hiero.consensus.wiring.framework.wires.output.StandardOutputWire;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/**
 * Tests for the {@link PcesCoordinator} class.
 */
@DisplayName("PcesCoordinator Tests")
class PcesCoordinatorTests {

    private static final long LOWER_BOUND = 7L;
    private static final long STARTING_ROUND = 42L;

    private FakeTime time;
    private PcesFileTracker initialPcesFiles;
    private InputWire<IOIterator<PlatformEvent>> pcesIteratorInputWire;
    private PcesReplayerWiring pcesReplayerWiring;
    private StatusMonitorModule statusMonitorModule;
    private InputWire<PlatformStatusAction> statusActionInputWire;
    private Runnable signalEndOfPcesReplay;

    private PcesMultiFileIterator iterator;

    private PcesCoordinator coordinator;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        time = new FakeTime();
        initialPcesFiles = mock(PcesFileTracker.class);
        pcesIteratorInputWire = mock(InputWire.class);
        statusMonitorModule = mock(StatusMonitorModule.class);
        statusActionInputWire = mock(InputWire.class);
        when(statusMonitorModule.platformStatusActionInputWire()).thenReturn(statusActionInputWire);
        signalEndOfPcesReplay = mock(Runnable.class);

        // Only pcesIteratorInputWire() is exercised by the coordinator; the other wires are placeholders.
        pcesReplayerWiring =
                new PcesReplayerWiring(pcesIteratorInputWire, mock(OutputWire.class), mock(StandardOutputWire.class));

        iterator = mock(PcesMultiFileIterator.class);
        when(initialPcesFiles.getEventIterator(LOWER_BOUND, STARTING_ROUND)).thenReturn(iterator);

        coordinator = new PcesCoordinator(
                time, initialPcesFiles, pcesReplayerWiring, statusMonitorModule, signalEndOfPcesReplay);
    }

    @Test
    @DisplayName("replayPcesEvents invokes its collaborators in the required order")
    void invokesCollaboratorsInOrder() {
        coordinator.replayPcesEvents(LOWER_BOUND, STARTING_ROUND);

        // A single InOrder across every collaborator pins down the global ordering, with the iterator injection
        // as the pivot: status started + status flush happen before it, and pipeline flush + end-of-replay signal +
        // status done happen after it.
        final InOrder inOrder = inOrder(
                statusActionInputWire,
                statusMonitorModule,
                initialPcesFiles,
                pcesIteratorInputWire,
                signalEndOfPcesReplay);

        inOrder.verify(statusActionInputWire).put(isA(StartedReplayingEventsAction.class));
        inOrder.verify(statusMonitorModule).flush();
        inOrder.verify(initialPcesFiles).getEventIterator(LOWER_BOUND, STARTING_ROUND);
        inOrder.verify(pcesIteratorInputWire).inject(iterator);
        inOrder.verify(signalEndOfPcesReplay).run();
        inOrder.verify(statusActionInputWire).put(isA(DoneReplayingEventsAction.class));
        inOrder.verifyNoMoreInteractions();
    }

    @Test
    @DisplayName("The iterator built from the PCES files is the one injected into the replayer wiring")
    void injectsIteratorFromPcesFiles() {
        coordinator.replayPcesEvents(LOWER_BOUND, STARTING_ROUND);

        final ArgumentCaptor<IOIterator<PlatformEvent>> captor = ArgumentCaptor.forClass(IOIterator.class);
        verify(pcesIteratorInputWire).inject(captor.capture());

        assertSame(iterator, captor.getValue(), "the injected iterator must be the one produced by the PCES files");
    }

    @Test
    @DisplayName("The status consumer receives exactly the started and done actions")
    void reportsStartedThenDone() {
        coordinator.replayPcesEvents(LOWER_BOUND, STARTING_ROUND);

        // DoneReplayingEventsAction is stamped with the current time.
        verify(statusActionInputWire).put(isA(StartedReplayingEventsAction.class));
        verify(statusActionInputWire).put(isA(DoneReplayingEventsAction.class));
        verify(statusActionInputWire, times(2)).put(isA(PlatformStatusAction.class));
        verifyNoMoreInteractions(statusActionInputWire);
    }
}
