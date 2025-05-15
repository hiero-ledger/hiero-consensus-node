// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event;

import static org.hiero.consensus.model.hashgraph.ConsensusConstants.ROUND_FIRST;

import com.swirlds.common.metrics.FunctionGauge;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.hiero.consensus.config.EventConfig;
import org.hiero.consensus.model.event.AncientMode;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.sequence.map.SequenceMap;
import org.hiero.consensus.model.sequence.map.StandardSequenceMap;

/**
 * Buffers events from the future (i.e. events with a birth round that is greater than the configured round). It is
 * important to note that the future event buffer is only used to store events from the near future that can be fully
 * validated. Events from beyond the event horizon (i.e. far future events that cannot be immediately validated) are
 * never stored by any part of the system.
 * <p>
 * Output from the future event buffer is guaranteed to preserve topological ordering, as long as the input to the
 * buffer is topologically ordered.
 */
public class FutureEventBuffer {

    /**
     * A little lambda that builds a new array list. Cache this here so we don't have to create a new lambda each time
     * we buffer a future event.
     */
    private static final Function<Long, List<PlatformEvent>> BUILD_LIST = x -> new ArrayList<>();

    private final FutureEventBufferingOption bufferingOption;

    private EventWindow eventWindow;

    private final SequenceMap<Long /* birth round */, List<PlatformEvent>> futureEvents =
            new StandardSequenceMap<>(ROUND_FIRST, 8, true, x -> x);

    private final AtomicLong bufferedEventCount = new AtomicLong(0);

    /**
     * Constructor.
     */
    public FutureEventBuffer(
            @NonNull final Configuration configuration,
            @NonNull final Metrics metrics,
            @NonNull final FutureEventBufferingOption bufferingOption) {
        this.bufferingOption = bufferingOption;
        final AncientMode ancientMode =
                configuration.getConfigData(EventConfig.class).getAncientMode();

        eventWindow = EventWindow.getGenesisEventWindow(ancientMode);

        metrics.getOrCreate(
                new FunctionGauge.Config<>("platform", "futureEventBuffer", Long.class, bufferedEventCount::get)
                        .withDescription("the number of events sitting in the future event buffer")
                        .withUnit("count"));
    }

    /**
     * Add an event to the future event buffer.
     *
     * @param event the event to add
     * @return the event if it is not a time traveler, or null if the event is from the future and needs to be buffered.
     */
    @Nullable
    public PlatformEvent addEvent(@NonNull final PlatformEvent event) {
        if (eventWindow.isAncient(event)) {
            // we can safely ignore ancient events
            return null;
        } else if (event.getBirthRound() <= bufferingOption.getMaximumReleasableRound(eventWindow)) {
            // this is not a future event, no need to buffer it
            return event;
        }

        // this is a future event, buffer it
        futureEvents.computeIfAbsent(event.getBirthRound(), BUILD_LIST).add(event);
        bufferedEventCount.incrementAndGet();
        return null;
    }

    /**
     * Update the current event window. As the event window advances, time catches up to time travelers, and events that
     * were previously from the future are now from the present.
     *
     * @param eventWindow the new event window
     * @return a list of events that were previously from the future but are now from the present, or an empty list if
     * there are no such events.
     */
    @NonNull
    public List<PlatformEvent> updateEventWindow(@NonNull final EventWindow eventWindow) {
        this.eventWindow = Objects.requireNonNull(eventWindow);

        // We want to release all events with birth rounds less than the oldest round to buffer.
        // In order to do that, we tell the sequence map to shift its window to the oldest round that we want
        // to keep within the buffer.
        final long oldestRoundToBuffer = bufferingOption.getOldestRoundToBuffer(eventWindow);

        final List<PlatformEvent> events = new ArrayList<>();
        futureEvents.shiftWindow(oldestRoundToBuffer, (round, roundEvents) -> {
            for (final PlatformEvent event : roundEvents) {
                if (!eventWindow.isAncient(event)) {
                    events.add(event);
                }
            }
        });

        bufferedEventCount.addAndGet(-events.size());
        return events;
    }

    /**
     * Clear all data from the future event buffer.
     */
    public void clear() {
        futureEvents.clear();
    }
}
