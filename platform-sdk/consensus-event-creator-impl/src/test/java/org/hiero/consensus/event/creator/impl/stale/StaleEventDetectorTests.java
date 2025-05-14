// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.creator.impl.stale;

import static org.hiero.consensus.model.event.AncientMode.BIRTH_ROUND_THRESHOLD;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.swirlds.common.metrics.noop.NoOpMetrics;
import com.swirlds.common.test.fixtures.Randotron;
import com.swirlds.component.framework.transformers.RoutableData;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.hiero.consensus.config.EventConfig_;
import org.hiero.consensus.model.event.AncientMode;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.event.StaleEventDetectorOutput;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.test.fixtures.event.TestingEventBuilder;
import org.hiero.consensus.model.test.fixtures.hashgraph.EventWindowBuilder;
import org.hiero.junit.extensions.ParamName;
import org.hiero.junit.extensions.ParamSource;
import org.hiero.junit.extensions.ParameterCombinationExtension;
import org.hiero.junit.extensions.UseParameterSources;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;

class StaleEventDetectorTests {

    /**
     * Extract self events from a stream containing both self events and stale self events. Corresponds to data tagged
     * with {@link StaleEventDetectorOutput#SELF_EVENT}.
     */
    private List<PlatformEvent> getSelfEvents(@NonNull final List<RoutableData<StaleEventDetectorOutput>> data) {
        final List<PlatformEvent> output = new ArrayList<>();
        for (final RoutableData<StaleEventDetectorOutput> datum : data) {
            if (datum.address() == StaleEventDetectorOutput.SELF_EVENT) {
                output.add((PlatformEvent) datum.data());
            }
        }
        return output;
    }

    /**
     * Validate that the correct stale event was returned as part of the output.
     *
     * @param data      the output data
     * @param selfEvent the self event that should have been returned
     */
    private void assertSelfEventReturned(
            @NonNull final List<RoutableData<StaleEventDetectorOutput>> data, @NonNull final PlatformEvent selfEvent) {

        final List<PlatformEvent> selfEvents = getSelfEvents(data);
        assertEquals(1, selfEvents.size());
        assertSame(selfEvent, selfEvents.getFirst());
    }

    /**
     * Validate that no self events were returned as part of the output. (Not to be confused with "stale self events"
     * events.) Essentially, we don't want to see data tagged with {@link StaleEventDetectorOutput#SELF_EVENT} unless we
     * are adding a self event and want to see it pass through.
     *
     * @param data the output data
     */
    private void assertNoSelfEventReturned(@NonNull final List<RoutableData<StaleEventDetectorOutput>> data) {
        final List<PlatformEvent> selfEvents = getSelfEvents(data);
        assertEquals(0, selfEvents.size());
    }

    /**
     * Extract stale self events from a stream containing both self events and stale self events. Corresponds to data
     * tagged with {@link StaleEventDetectorOutput#STALE_SELF_EVENT}.
     */
    private List<PlatformEvent> getStaleSelfEvents(@NonNull final List<RoutableData<StaleEventDetectorOutput>> data) {
        final List<PlatformEvent> output = new ArrayList<>();
        for (final RoutableData<StaleEventDetectorOutput> datum : data) {
            if (datum.address() == StaleEventDetectorOutput.STALE_SELF_EVENT) {
                output.add((PlatformEvent) datum.data());
            }
        }
        return output;
    }

    /**
     * Test that the {@link StaleEventDetector} throws an exception if the initial event window is not set.
     *
     * @param ancientMode  {@link AncientMode#values()}
     * @param randotron  {@link Randotron#create()} ()}
     */
    @TestTemplate
    @ExtendWith(ParameterCombinationExtension.class)
    @UseParameterSources({
        @ParamSource(
                param = "ancientMode",
                fullyQualifiedClass = "org.hiero.consensus.model.event.AncientMode",
                method = "values"),
        @ParamSource(
                param = "randotron",
                fullyQualifiedClass = "com.swirlds.common.test.fixtures.Randotron",
                method = "create")
    })
    void throwIfInitialEventWindowNotSetTest(
            @ParamName("ancientMode") final AncientMode ancientMode,
            @ParamName("randotron") final Randotron randotron) {
        final NodeId selfId = NodeId.of(randotron.nextPositiveLong());

        final Configuration configuration = new TestConfigBuilder()
                .withValue(EventConfig_.USE_BIRTH_ROUND_ANCIENT_THRESHOLD, ancientMode == BIRTH_ROUND_THRESHOLD)
                .getOrCreateConfig();
        final StaleEventDetector detector = new DefaultStaleEventDetector(configuration, new NoOpMetrics(), selfId);

        final PlatformEvent event = new TestingEventBuilder(randotron).build();

        assertThrows(
                IllegalStateException.class,
                () -> detector.addSelfEvent(event),
                () -> "Event window must be set before adding self events");
    }

    /**
     * Test that the {@link StaleEventDetector} throws an exception if the initial event window is not set.
     *
     * @param ancientMode  {@link AncientMode#values()}
     * @param randotron  {@link Randotron#create()} ()}
     */
    @TestTemplate
    @ExtendWith(ParameterCombinationExtension.class)
    @UseParameterSources({
        @ParamSource(
                param = "ancientMode",
                fullyQualifiedClass = "org.hiero.consensus.model.event.AncientMode",
                method = "values"),
        @ParamSource(
                param = "randotron",
                fullyQualifiedClass = "com.swirlds.common.test.fixtures.Randotron",
                method = "create")
    })
    void eventIsStaleBeforeAddedTest(
            @ParamName("ancientMode") final AncientMode ancientMode,
            @ParamName("randotron") final Randotron randotron) {
        final NodeId selfId = NodeId.of(randotron.nextPositiveLong());

        final Configuration configuration = new TestConfigBuilder()
                .withValue(EventConfig_.USE_BIRTH_ROUND_ANCIENT_THRESHOLD, ancientMode == BIRTH_ROUND_THRESHOLD)
                .getOrCreateConfig();
        final StaleEventDetector detector = new DefaultStaleEventDetector(configuration, new NoOpMetrics(), selfId);

        final long ancientThreshold = randotron.nextPositiveLong() + 100;
        final long eventValue =
                ancientThreshold - (randotron.nextLong(100) + 1); // +1, because value is not yet considered ancient

        final var builder = new TestingEventBuilder(randotron).setCreatorId(selfId);
        final PlatformEvent event;

        if (ancientMode == BIRTH_ROUND_THRESHOLD) {
            builder.setBirthRound(eventValue);
            event = builder.build();
        } else {
            event = spy(builder.build());
            when(event.getGeneration()).thenReturn(eventValue);
        }

        detector.setInitialEventWindow(EventWindowBuilder.birthRoundMode()
                .setLatestConsensusRound(randotron.nextPositiveInt())
                .setAncientThreshold(ancientThreshold)
                .setExpiredThreshold(randotron.nextPositiveLong())
                .build());

        final List<RoutableData<StaleEventDetectorOutput>> output = detector.addSelfEvent(event);

        final List<PlatformEvent> platformEvents = getSelfEvents(output);
        final List<PlatformEvent> staleEvents = getStaleSelfEvents(output);

        assertEquals(1, staleEvents.size());
        assertSame(event, staleEvents.getFirst());

        assertSelfEventReturned(output, event);
    }

    /**
     * Construct a consensus round.
     *
     * @param randotron        a source of randomness
     * @param events           events that will reach consensus in this round
     * @param ancientThreshold the ancient threshold for this round
     * @param ancientMode
     * @return a consensus round
     */
    @NonNull
    private static ConsensusRound createConsensusRound(
            @NonNull final Randotron randotron,
            @NonNull final List<PlatformEvent> events,
            final long ancientThreshold,
            final AncientMode ancientMode) {
        final EventWindow eventWindow = EventWindowBuilder.setAncientMode(ancientMode)
                .setLatestConsensusRound(randotron.nextPositiveLong())
                .setAncientThreshold(ancientThreshold)
                .setExpiredThreshold(randotron.nextPositiveLong())
                .build();

        return new ConsensusRound(
                mock(Roster.class), events, eventWindow, mock(ConsensusSnapshot.class), false, Instant.now());
    }

    /**
     * Test that the {@link StaleEventDetector} successfully detects stale events if, either when added they are already ancient,
     * or if they become ancient due to the progress of consensus and the rounds generated.
     *
     * @param ancientMode  {@link AncientMode#values()}
     * @param randotron  {@link Randotron#create()} ()}
     */
    @TestTemplate
    @ExtendWith(ParameterCombinationExtension.class)
    @UseParameterSources({
        @ParamSource(
                param = "ancientMode",
                fullyQualifiedClass = "org.hiero.consensus.model.event.AncientMode",
                method = "values"),
        @ParamSource(
                param = "randotron",
                fullyQualifiedClass = "com.swirlds.common.test.fixtures.Randotron",
                method = "create")
    })
    void randomEventsTest(
            @ParamName("ancientMode") final AncientMode ancientMode,
            @ParamName("randotron") final Randotron randotron) {
        final NodeId selfId = NodeId.of(randotron.nextPositiveLong());

        final Configuration configuration = new TestConfigBuilder()
                .withValue(EventConfig_.USE_BIRTH_ROUND_ANCIENT_THRESHOLD, ancientMode == BIRTH_ROUND_THRESHOLD)
                .getOrCreateConfig();
        final StaleEventDetector detector = new DefaultStaleEventDetector(configuration, new NoOpMetrics(), selfId);

        final Set<PlatformEvent> detectedStaleEvents = new HashSet<>();
        final Set<PlatformEvent> expectedStaleEvents = new HashSet<>();
        final List<PlatformEvent> consensusEvents = new ArrayList<>();

        long currentAncientThreshold = randotron.nextLong(100, 1_000);
        detector.setInitialEventWindow(EventWindowBuilder.setAncientMode(ancientMode)
                .setLatestConsensusRound(randotron.nextPositiveLong())
                .setAncientThreshold(currentAncientThreshold)
                .setExpiredThreshold(randotron.nextPositiveLong())
                .build());

        for (int i = 0; i < 10_000; i++) {
            final boolean selfEvent = randotron.nextBoolean(0.25);
            final NodeId eventCreator = selfEvent ? selfId : NodeId.of(randotron.nextPositiveLong());

            final TestingEventBuilder eventBuilder = new TestingEventBuilder(randotron).setCreatorId(eventCreator);

            final boolean eventIsAncientBeforeAdded = randotron.nextBoolean(0.01);

            final PlatformEvent event;

            final long overrideValue = currentAncientThreshold
                    + (eventIsAncientBeforeAdded ? -randotron.nextLong(1, 100) : randotron.nextLong(3));
            if (ancientMode == BIRTH_ROUND_THRESHOLD) {
                eventBuilder.setBirthRound(overrideValue);
                event = eventBuilder.build();
            } else {
                event = buildEventAndOverrideGeneration(eventBuilder, overrideValue);
            }

            final boolean willReachConsensus = !eventIsAncientBeforeAdded && randotron.nextBoolean(0.8);

            if (willReachConsensus) {
                consensusEvents.add(event);
            }

            if (selfEvent && (eventIsAncientBeforeAdded || !willReachConsensus)) {
                expectedStaleEvents.add(event);
            }

            if (selfEvent) {
                final List<RoutableData<StaleEventDetectorOutput>> output = detector.addSelfEvent(event);
                detectedStaleEvents.addAll(getStaleSelfEvents(output));
                assertSelfEventReturned(output, event);
            }

            // Once in a while, permit a round to "reach consensus"
            if (randotron.nextBoolean(0.01)) {
                currentAncientThreshold += randotron.nextLong(3);

                final ConsensusRound consensusRound =
                        createConsensusRound(randotron, consensusEvents, currentAncientThreshold, ancientMode);

                final List<RoutableData<StaleEventDetectorOutput>> output = detector.addConsensusRound(consensusRound);
                detectedStaleEvents.addAll(getStaleSelfEvents(output));
                assertNoSelfEventReturned(output);
                consensusEvents.clear();
            }
        }

        // Create a final round with all remaining consensus events. Move ancient threshold far enough forward
        // to flush out all events we expect to eventually become stale.
        currentAncientThreshold += randotron.nextLong(1_000, 10_000);
        final ConsensusRound consensusRound =
                createConsensusRound(randotron, consensusEvents, currentAncientThreshold, ancientMode);
        final List<RoutableData<StaleEventDetectorOutput>> output = detector.addConsensusRound(consensusRound);
        detectedStaleEvents.addAll(getStaleSelfEvents(output));
        assertNoSelfEventReturned(output);

        assertEquals(expectedStaleEvents.size(), detectedStaleEvents.size());
    }

    // Given that generations depend on a specific parent configuration,
    //  this method hacks the value to make it easier for the test to set them
    private static PlatformEvent buildEventAndOverrideGeneration(
            final TestingEventBuilder eventBuilder, final long generationOverride) {
        final PlatformEvent event;
        event = spy(eventBuilder.build());
        var descriptor = spy(event.getDescriptor());
        var eventDescriptor = spy(descriptor.eventDescriptor());
        when(event.getDescriptor()).thenReturn(descriptor);
        when(descriptor.eventDescriptor()).thenReturn(eventDescriptor);
        when(event.getGeneration()).thenReturn(generationOverride);
        doReturn(generationOverride).when(descriptor).getAncientIndicator(any(AncientMode.class));
        doReturn(generationOverride).when(eventDescriptor).generation();
        return event;
    }

    /**
     * Test that the {@link StaleEventDetector} successfully detects stale events if, either when added they are already ancient,
     * or if they become ancient due to the progress of consensus and the rounds generated.
     *
     * @param ancientMode  {@link AncientMode#values()}
     * @param randotron  {@link Randotron#create()} ()}
     */
    @TestTemplate
    @ExtendWith(ParameterCombinationExtension.class)
    @UseParameterSources({
        @ParamSource(
                param = "ancientMode",
                fullyQualifiedClass = "org.hiero.consensus.model.event.AncientMode",
                method = "values"),
        @ParamSource(
                param = "randotron",
                fullyQualifiedClass = "com.swirlds.common.test.fixtures.Randotron",
                method = "create")
    })
    void clearTest(
            @ParamName("ancientMode") final AncientMode ancientMode,
            @ParamName("randotron") final Randotron randotron) {
        final NodeId selfId = NodeId.of(randotron.nextPositiveLong());

        final Configuration configuration = new TestConfigBuilder()
                .withValue(EventConfig_.USE_BIRTH_ROUND_ANCIENT_THRESHOLD, ancientMode == BIRTH_ROUND_THRESHOLD)
                .getOrCreateConfig();
        final StaleEventDetector detector = new DefaultStaleEventDetector(configuration, new NoOpMetrics(), selfId);

        final long ancientThreshold1 = randotron.nextPositiveInt() + 100;
        final long overrideValue = ancientThreshold1 + randotron.nextPositiveInt(10);

        final TestingEventBuilder testingEventBuilder = new TestingEventBuilder(randotron).setCreatorId(selfId);
        final PlatformEvent event1 = ancientMode == BIRTH_ROUND_THRESHOLD
                ? testingEventBuilder.setBirthRound(overrideValue).build()
                : buildEventAndOverrideGeneration(testingEventBuilder, overrideValue);

        detector.setInitialEventWindow(EventWindowBuilder.setAncientMode(ancientMode)
                .setLatestConsensusRound(randotron.nextPositiveInt())
                .setAncientThreshold(ancientThreshold1)
                .setExpiredThreshold(randotron.nextPositiveLong())
                .build());

        final List<RoutableData<StaleEventDetectorOutput>> output1 = detector.addSelfEvent(event1);
        assertSelfEventReturned(output1, event1);
        assertEquals(0, getStaleSelfEvents(output1).size());

        detector.clear();

        // Adding an event again before setting the event window should throw.
        assertThrows(IllegalStateException.class, () -> detector.addSelfEvent(event1));

        // Setting the ancient threshold after the original event should not cause it to come back as stale.
        final long ancientThreshold2 = overrideValue + randotron.nextPositiveInt();
        detector.setInitialEventWindow(EventWindowBuilder.setAncientMode(ancientMode)
                .setLatestConsensusRound(randotron.nextPositiveInt())
                .setAncientThreshold(ancientThreshold2)
                .setExpiredThreshold(randotron.nextPositiveLong())
                .build());
        // Verify that we get otherwise normal behavior after the clear.

        final long eventBirthRound2 = ancientThreshold2 + randotron.nextPositiveInt(10);
        final TestingEventBuilder testingEventBuilder2 = new TestingEventBuilder(randotron).setCreatorId(selfId);
        final PlatformEvent event2 = ancientMode == BIRTH_ROUND_THRESHOLD
                ? testingEventBuilder2.setBirthRound(eventBirthRound2).build()
                : buildEventAndOverrideGeneration(testingEventBuilder2, eventBirthRound2);

        final List<RoutableData<StaleEventDetectorOutput>> output2 = detector.addSelfEvent(event2);
        assertSelfEventReturned(output2, event2);
        assertEquals(0, getStaleSelfEvents(output2).size());

        final long ancientThreshold3 = eventBirthRound2 + randotron.nextPositiveInt(10);
        final ConsensusRound consensusRound =
                createConsensusRound(randotron, List.of(), ancientThreshold3, ancientMode);
        final List<RoutableData<StaleEventDetectorOutput>> output3 = detector.addConsensusRound(consensusRound);
        assertNoSelfEventReturned(output3);
        final List<PlatformEvent> staleEvents = getStaleSelfEvents(output3);
        assertEquals(1, staleEvents.size());
        assertSame(event2, staleEvents.getFirst());
    }
}
