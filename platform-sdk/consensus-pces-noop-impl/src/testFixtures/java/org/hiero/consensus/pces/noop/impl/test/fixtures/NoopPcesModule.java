// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.pces.noop.impl.test.fixtures;

import static java.util.Objects.requireNonNull;

import com.swirlds.base.time.Time;
import com.swirlds.component.framework.model.WiringModel;
import com.swirlds.component.framework.schedulers.builders.TaskSchedulerType;
import com.swirlds.component.framework.wires.input.BindableInputWire;
import com.swirlds.component.framework.wires.input.InputWire;
import com.swirlds.component.framework.wires.input.NoInput;
import com.swirlds.component.framework.wires.output.OutputWire;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.hiero.consensus.io.RecycleBin;
import org.hiero.consensus.metrics.statistics.EventPipelineTracker;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.status.PlatformStatusAction;
import org.hiero.consensus.pces.PcesModule;
import org.hiero.consensus.state.signed.ReservedSignedState;

/**
 * No-op implementation of the {@link PcesModule}.
 */
public class NoopPcesModule implements PcesModule {

    private InputWire<PlatformEvent> eventsToWrite;
    private OutputWire<PlatformEvent> pcesEventsToReplay;
    private InputWire<Long> minimumBirthRoundInputWire;
    private InputWire<Long> discontinuityInputWire;
    private OutputWire<PlatformEvent> writtenEventsOutputWire;
    private InputWire<EventWindow> eventWindowInputWire;

    /**
     * {@inheritDoc}
     */
    @Override
    public void initialize(
            @NonNull final WiringModel model,
            @NonNull final Configuration configuration,
            @NonNull final Metrics metrics,
            @NonNull final Time time,
            @NonNull final NodeId selfId,
            @NonNull final RecycleBin recycleBin,
            final long startingRound,
            @NonNull final Runnable flushIntake,
            @NonNull final Runnable flushTransactionHandling,
            @NonNull final Supplier<ReservedSignedState> latestImmutableStateSupplier,
            @NonNull final Consumer<PlatformStatusAction> statusActionConsumer,
            @NonNull final Runnable stateHasherFlusher,
            @NonNull final Runnable signalEndOfPcesReplay,
            @Nullable final EventPipelineTracker pipelineTracker) {
        requireNonNull(model);
        requireNonNull(configuration);
        requireNonNull(metrics);
        requireNonNull(selfId);
        requireNonNull(recycleBin);
        requireNonNull(flushIntake);
        requireNonNull(flushTransactionHandling);
        requireNonNull(latestImmutableStateSupplier);
        requireNonNull(statusActionConsumer);
        requireNonNull(stateHasherFlusher);
        requireNonNull(signalEndOfPcesReplay);

        final var scheduler = model.<PlatformEvent>schedulerBuilder("InlinePcesWriter")
                .withType(TaskSchedulerType.SEQUENTIAL)
                .withFlushingEnabled(true)
                .build();
        final var replayerScheduler = model.<NoInput>schedulerBuilder("pcesReplayer")
                .withType(TaskSchedulerType.DIRECT)
                .build();
        final BindableInputWire<?, NoInput> replayInput = replayerScheduler.buildInputWire("event files to replay");
        replayInput.bindConsumer(_ -> {});

        final BindableInputWire<PlatformEvent, PlatformEvent> bwriter = scheduler.buildInputWire("events to write");
        bwriter.bind(Function.identity());
        this.eventsToWrite = bwriter;
        this.writtenEventsOutputWire = scheduler.getOutputWire();
        this.pcesEventsToReplay = replayerScheduler.buildSecondaryOutputWire();

        // Mirror the real module's replayer→writer "done streaming pces" link
        final BindableInputWire<NoInput, PlatformEvent> doneStreamingPces =
                scheduler.buildInputWire("done streaming pces");
        doneStreamingPces.bindConsumer(_ -> {});
        replayerScheduler.getOutputWire().solderTo(doneStreamingPces);

        final BindableInputWire<Long, PlatformEvent> minimumBirthRoundToStore =
                scheduler.buildInputWire("minimum identifier to store");
        minimumBirthRoundToStore.bindConsumer(_ -> {});
        this.minimumBirthRoundInputWire = minimumBirthRoundToStore;
        final BindableInputWire<Long, PlatformEvent> discontinuity = scheduler.buildInputWire("discontinuity");
        discontinuity.bindConsumer(_ -> {});
        this.discontinuityInputWire = discontinuity;
        final BindableInputWire<EventWindow, PlatformEvent> eventWindow = scheduler.buildInputWire("event window");
        eventWindow.bindConsumer(_ -> {});
        this.eventWindowInputWire = eventWindow;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void replayPcesEvents(final long pcesReplayLowerBound, final long startingRound) {
        // no-op
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public OutputWire<PlatformEvent> pcesEventsToReplay() {
        return requireNonNull(pcesEventsToReplay, "Not initialized");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public InputWire<PlatformEvent> eventsToWriteInputWire() {
        return requireNonNull(eventsToWrite, "Not initialized");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public OutputWire<PlatformEvent> writtenEventsOutputWire() {
        return requireNonNull(writtenEventsOutputWire, "Not initialized");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public InputWire<EventWindow> eventWindowInputWire() {
        return requireNonNull(eventWindowInputWire, "Not initialized");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public InputWire<Long> minimumBirthRoundInputWire() {
        return requireNonNull(minimumBirthRoundInputWire, "Not initialized");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public InputWire<Long> discontinuityInputWire() {
        return requireNonNull(discontinuityInputWire, "Not initialized");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void flush() {
        // no-op
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void copyPcesFilesRetryOnFailure(
            @NonNull final Configuration configuration,
            @NonNull final NodeId selfId,
            @NonNull final Path destinationDirectory,
            final long lowerBound,
            final long round) {
        // no-op
    }
}
