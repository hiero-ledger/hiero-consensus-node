// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.pcli.graph;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import org.hiero.consensus.model.event.PlatformEvent;

/**
 * A pipeline for processing event graphs: reads events from an {@link EventGraphSource},
 * applies optional filtering with an {@link EventFilter}, applies optional mapping with
 * an {@link EventMapper}, and delivers results to an {@link EventSink} (per-event) and/or
 * a {@link BatchEventSink} (all events at once).
 *
 * <h2>Pipeline Stages</h2>
 * <p>Events flow through the pipeline in order: Source → Filter → Mapper → Sink
 *
 * <h2>Custom Pipeline Examples</h2>
 *
 * <p><b>Example 1: Collect all events into a list</b>
 * <pre>{@code
 * // Given an EventGraphSource that provides events...
 * List<PlatformEvent> events = new ArrayList<>();
 * new EventGraphPipeline(source, null, null, null, events::addAll).process();
 * }</pre>
 *
 * <p><b>Example 2: Filter events by birth round</b>
 * <pre>{@code
 * EventFilter keepRecent = event -> event.getBirthRound() >= 100;
 * new EventGraphPipeline(source, keepRecent, null, null, results::addAll).process();
 * }</pre>
 *
 * <p><b>Example 3: Stream events to a writer (low memory)</b>
 * <pre>{@code
 * EventSink writer = event -> outputStream.writeEvent(event);
 * new EventGraphPipeline(source, null, null, writer, null).process();
 * }</pre>
 *
 * <p><b>Example 4: Transform events</b>
 * <pre>{@code
 * EventMapper addMetadata = event -> createEventWithMetadata(event);
 * new EventGraphPipeline(source, filter, addMetadata, writer, null).process();
 * }</pre>
 *
 * <h2>Memory Considerations</h2>
 * <ul>
 *   <li>Using only {@link EventSink} processes events in streaming fashion without accumulating in memory.</li>
 *   <li>Using {@link BatchEventSink} collects all output events in memory before delivery.</li>
 * </ul>
 *
 * @see EventGraphSource
 */
public class EventGraphPipeline {

    private final EventGraphSource source;
    private final EventFilter filter;
    private final EventMapper mapper;
    private final EventSink eventSink;
    private final BatchEventSink batchEventSink;

    /**
     * Creates a new pipeline with the given components.
     *
     * @param source         the source of events to process
     * @param filter         defines how to filter events; if null, all events are kept
     * @param mapper         defines how to transform events; if null, events pass through unchanged
     * @param eventSink      receives each event individually as it is processed; if null, per-event delivery is skipped
     * @param batchEventSink receives all output events after processing completes; if null, batch delivery is skipped
     */
    public EventGraphPipeline(
            @NonNull final EventGraphSource source,
            @Nullable final EventFilter filter,
            @Nullable final EventMapper mapper,
            @Nullable final EventSink eventSink,
            @Nullable final BatchEventSink batchEventSink) {
        this.source = Objects.requireNonNull(source);
        this.filter = filter;
        this.mapper = mapper;
        this.eventSink = eventSink;
        this.batchEventSink = batchEventSink;
    }

    /**
     * Processes events from the source, applies filtering and mapping, then delivers the results.
     * If no filter is set, all events are kept. If no mapper is set, events pass through unchanged.
     * If an {@link EventSink} is set, each event is delivered individually as it is processed.
     * If a {@link BatchEventSink} is set, all events are collected and delivered as a batch after processing completes.
     * Note: When only using EventSink (no BatchEventSink), events are NOT collected in memory for efficiency.
     */
    public void process() {
        // Only collect events in memory if we have a batch sink
        final List<PlatformEvent> outputEvents = batchEventSink != null ? new ArrayList<>() : null;

        while (source.hasNext()) {
            final PlatformEvent event = source.next();
            if (filter != null && !filter.filter(event)) {
                continue;
            }
            final PlatformEvent outputEvent = mapper != null ? mapper.map(event) : event;
            if (eventSink != null) {
                eventSink.accept(outputEvent);
            }
            if (outputEvents != null) {
                outputEvents.add(outputEvent);
            }
        }
        if (batchEventSink != null) {
            batchEventSink.accept(outputEvents);
        }
    }

    /**
     * A filter that determines which events should be included in processing.
     * Events for which {@link #filter(PlatformEvent)} returns {@code true} are kept;
     * events for which it returns {@code false} are excluded.
     */
    public interface EventFilter extends Predicate<PlatformEvent> {
        /**
         * Determines whether the given event should be included.
         *
         * @param event the event to evaluate
         * @return {@code true} if the event should be kept, {@code false} if it should be excluded
         */
        boolean filter(@NonNull PlatformEvent event);

        @Override
        default boolean test(@NonNull PlatformEvent event) {
            return filter(event);
        }
    }

    /**
     * A mapper that transforms a {@link PlatformEvent} into a new {@link PlatformEvent}.
     */
    public interface EventMapper extends Function<PlatformEvent, PlatformEvent> {
        /**
         * Transforms the given event.
         *
         * @param event the event to transform
         * @return the transformed event
         */
        @NonNull
        PlatformEvent map(@NonNull PlatformEvent event);

        @Override
        default PlatformEvent apply(@NonNull PlatformEvent event) {
            return map(event);
        }
    }

    /**
     * A consumer that receives each output event individually as it is processed.
     * This allows for streaming processing where events are handled one at a time
     * rather than waiting for the entire batch to complete.
     */
    public interface EventSink extends Consumer<PlatformEvent> {}

    /**
     * A consumer that receives all output events from the pipeline as a batch after processing completes.
     * Implementations decide what to do with the events (e.g., write to storage, collect in memory).
     */
    public interface BatchEventSink extends Consumer<List<PlatformEvent>> {}
}
