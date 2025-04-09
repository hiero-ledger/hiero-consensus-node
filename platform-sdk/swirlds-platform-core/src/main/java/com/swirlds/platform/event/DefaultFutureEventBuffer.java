// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event;

import static org.hiero.consensus.model.hashgraph.ConsensusConstants.ROUND_FIRST;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.metrics.FunctionGauge;
import com.swirlds.platform.sequence.map.SequenceMap;
import com.swirlds.platform.sequence.map.StandardSequenceMap;
import com.swirlds.platform.wiring.NoInput;
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

/**
 * Default implementation of the {@link FutureEventBuffer}
 */
public class DefaultFutureEventBuffer implements FutureEventBuffer {

    /**
     * A little lambda that builds a new array list. Cache this here so we don't have to create a new lambda each time
     * we buffer a future event.
     */
    private static final Function<Long, List<PlatformEvent>> BUILD_LIST = x -> new ArrayList<>();

    private EventWindow eventWindow;

    private final SequenceMap<Long /* birth round */, List<PlatformEvent>> futureEvents =
            new StandardSequenceMap<>(ROUND_FIRST, 8, true, x -> x);

    private final AtomicLong bufferedEventCount = new AtomicLong(0);

    /**
     * Constructor.
     *
     * @param platformContext the platform context
     */
    public DefaultFutureEventBuffer(@NonNull final PlatformContext platformContext) {
        final AncientMode ancientMode = platformContext
                .getConfiguration()
                .getConfigData(EventConfig.class)
                .getAncientMode();

        eventWindow = EventWindow.getGenesisEventWindow(ancientMode);

        platformContext
                .getMetrics()
                .getOrCreate(
                        new FunctionGauge.Config<>("platform", "futureEventBuffer", Long.class, bufferedEventCount::get)
                                .withDescription("the number of events sitting in the future event buffer")
                                .withUnit("count"));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Nullable
    public List<PlatformEvent> addEvent(@NonNull final PlatformEvent event) {
        if (eventWindow.isAncient(event)) {
            // we can safely ignore ancient events
            return null;
        } else if (event.getBirthRound() <= eventWindow.getPendingConsensusRound()) {
            // this is not a future event, no need to buffer it
            return List.of(event);
        }

        // this is a future event, buffer it
        futureEvents.computeIfAbsent(event.getBirthRound(), BUILD_LIST).add(event);
        bufferedEventCount.incrementAndGet();
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Nullable
    public List<PlatformEvent> updateEventWindow(@NonNull final EventWindow eventWindow) {
        this.eventWindow = Objects.requireNonNull(eventWindow);

        // We want to release all events with birth rounds less than or equal to the pending consensus round.
        // In order to do that, we tell the sequence map to shift its window to the oldest round that we want
        // to keep within the buffer.
        final long oldestRoundToBuffer = eventWindow.getPendingConsensusRound() + 1;

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
     * {@inheritDoc}
     */
    @Override
    public void clear(@NonNull final NoInput ignored) {
        futureEvents.clear();
    }
}
