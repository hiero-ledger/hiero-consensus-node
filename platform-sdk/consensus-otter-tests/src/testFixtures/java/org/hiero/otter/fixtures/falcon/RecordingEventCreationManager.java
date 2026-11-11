// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.falcon;

import static java.util.Objects.requireNonNull;

import com.swirlds.base.time.Time;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.util.function.Consumer;
import org.hiero.consensus.event.creator.impl.EventCreationManager;
import org.hiero.consensus.model.event.EventOrigin;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.gossip.SyncProgress;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.quiescence.QuiescenceCommand;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.otter.fixtures.falcon.recording.Step;
import org.hiero.otter.fixtures.falcon.recording.Step.CreationAttempt;
import org.hiero.otter.fixtures.falcon.recording.Step.NewEventWindow;
import org.hiero.otter.fixtures.falcon.recording.Step.PlatformStatusChange;
import org.hiero.otter.fixtures.falcon.recording.Step.Released;

/**
 * An {@link EventCreationManager} that records every call it gets as a {@link Step}, then passes it on.
 *
 * <p>It throws for the calls it does not record. Falcon does not make them yet; a change that does must add a step for
 * them, so that a replay of the recording gets every input of the event creator.
 */
final class RecordingEventCreationManager implements EventCreationManager {

    private final EventCreationManager delegate;
    private final Time time;
    private final NodeId selfId;
    private final Consumer<Step> stepRecorder;

    /**
     * Constructor.
     *
     * @param delegate the event creation manager the calls are passed on to
     * @param time the time of the node
     * @param selfId the ID of the node
     * @param stepRecorder receives the steps
     */
    RecordingEventCreationManager(
            @NonNull final EventCreationManager delegate,
            @NonNull final Time time,
            @NonNull final NodeId selfId,
            @NonNull final Consumer<Step> stepRecorder) {
        this.delegate = requireNonNull(delegate);
        this.time = requireNonNull(time);
        this.selfId = requireNonNull(selfId);
        this.stepRecorder = requireNonNull(stepRecorder);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Nullable
    public PlatformEvent maybeCreateEvent() {
        final PlatformEvent created = delegate.maybeCreateEvent();
        stepRecorder.accept(CreationAttempt.of(time.now(), created));
        return created;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void registerEvent(@NonNull final PlatformEvent event) {
        final EventOrigin origin = event.getCreatorId().equals(selfId) ? EventOrigin.RUNTIME : EventOrigin.GOSSIP;
        stepRecorder.accept(Released.of(time.now(), event, origin));
        delegate.registerEvent(event);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setEventWindow(@NonNull final EventWindow eventWindow) {
        stepRecorder.accept(new NewEventWindow(time.now(), eventWindow));
        delegate.setEventWindow(eventWindow);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void updatePlatformStatus(@NonNull final PlatformStatus platformStatus) {
        stepRecorder.accept(new PlatformStatusChange(time.now(), platformStatus));
        delegate.updatePlatformStatus(platformStatus);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void reportUnhealthyDuration(@NonNull final Duration duration) {
        throw new UnsupportedOperationException("Health reports are not recorded");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void reportSyncProgress(@NonNull final SyncProgress syncProgress) {
        throw new UnsupportedOperationException("Sync progress is not recorded");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void quiescenceCommand(@NonNull final QuiescenceCommand quiescenceCommand) {
        throw new UnsupportedOperationException("Quiescence commands are not recorded");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void clear() {
        throw new UnsupportedOperationException("Clearing the event creator is not recorded");
    }
}
