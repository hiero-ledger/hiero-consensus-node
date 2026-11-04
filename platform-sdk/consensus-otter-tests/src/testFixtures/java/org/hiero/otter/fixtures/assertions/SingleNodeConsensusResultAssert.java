// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.assertions;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.assertj.core.api.AbstractAssert;
import org.assertj.core.api.Assertions;
import org.hiero.consensus.hashgraph.impl.test.fixtures.consensus.framework.validation.ConsensusRoundValidator;
import org.hiero.consensus.model.event.EventDescriptorWrapper;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.otter.fixtures.result.SingleNodeConsensusResult;

/**
 * Assertions for {@link SingleNodeConsensusResult}.
 */
@SuppressWarnings({"UnusedReturnValue", "unused"})
public class SingleNodeConsensusResultAssert
        extends AbstractAssert<SingleNodeConsensusResultAssert, SingleNodeConsensusResult> {

    private static final int MAX_REPORTED_EVENTS = 10;

    /**
     * Creates a new instance of {@link SingleNodeConsensusResultAssert}.
     *
     * @param actual the actual {@link SingleNodeConsensusResult} to assert
     */
    public SingleNodeConsensusResultAssert(@Nullable final SingleNodeConsensusResult actual) {
        super(actual, SingleNodeConsensusResultAssert.class);
    }

    /**
     * Creates an assertion for the given {@link SingleNodeConsensusResult}.
     *
     * @param actual the {@link SingleNodeConsensusResult} to assert
     * @return an assertion for the given {@link SingleNodeConsensusResult}
     */
    @NonNull
    public static SingleNodeConsensusResultAssert assertThat(@Nullable final SingleNodeConsensusResult actual) {
        return new SingleNodeConsensusResultAssert(actual);
    }

    /**
     * Verifies that the last round created is equal to the expected value.
     *
     * @param expected the expected last round
     * @return this assertion object for method chaining
     */
    @NonNull
    public SingleNodeConsensusResultAssert hasLastRoundNum(final long expected) {
        isNotNull();
        if (actual.lastRoundNum() != expected) {
            failWithMessage(
                    "Expected last round of node %s to be <%d> but was <%d>",
                    actual.nodeId(), expected, actual.lastRoundNum());
        }
        return this;
    }

    /**
     * Verifies that the last round created is larger than the expected value, in other words, that the consensus
     * algorithm has proceeded since the given round.
     *
     * @param expected the round to compare with
     * @return this assertion object for method chaining
     */
    @NonNull
    public SingleNodeConsensusResultAssert hasAdvancedSinceRound(final long expected) {
        isNotNull();
        if (actual.lastRoundNum() <= expected) {
            failWithMessage(
                    "Expected last round of node %s to be larger than <%d> but was <%d>",
                    actual.nodeId(), expected, actual.lastRoundNum());
        }
        return this;
    }

    /**
     * Verifies that the created consensus rounds are equal to the expected rounds.
     *
     * @param expected the expected rounds
     * @return this assertion object for method chaining
     */
    @NonNull
    public SingleNodeConsensusResultAssert hasRounds(@NonNull final List<ConsensusRound> expected) {
        isNotNull();
        Assertions.assertThat(actual.consensusRounds())
                .withFailMessage(
                        "Expected consensus rounds of node %s to be <%s> but was <%s>",
                        actual.nodeId(), expected, actual.consensusRounds())
                .containsExactlyElementsOf(expected);
        return this;
    }

    /**
     * Verifies that events with a creation time prior to and including the given {@code splitTime} have a birth round
     * equal to or less than the {@code splitRound}, and all events with a creation time after the {@code splitTime}
     * have a birth ground greater than {@code splitRound}.
     *
     * @param splitTime  all events with a creation time before and including this time should have a birth round equal
     *                   to or less that the {@code splitRound}
     * @param splitRound the maximum birth round for events created before and up to the {@code splitTime}
     * @return this assertion object for method chaining
     */
    @NonNull
    public SingleNodeConsensusResultAssert hasBirthRoundSplit(@NonNull final Instant splitTime, final long splitRound) {
        isNotNull();
        for (final ConsensusRound round : actual().consensusRounds()) {
            for (final PlatformEvent event : round.getConsensusEvents()) {
                if (event.getTimeCreated().isAfter(splitTime)) {
                    Assertions.assertThat(event.getBirthRound())
                            .withFailMessage(
                                    "Expected events with creation time less than or equal to %s to "
                                            + "have a birth round less than or equal to <%s>, but creation time "
                                            + "was %s and birth round was <%s>",
                                    splitTime, splitRound, event.getTimeCreated(), event.getBirthRound())
                            .isGreaterThan(splitRound);
                } else {
                    Assertions.assertThat(event.getBirthRound())
                            .withFailMessage(
                                    "Expected events with creation time greater than %s to "
                                            + "have a birth round greater than <%s>, but creation time "
                                            + "was %s and birth round was <%s>",
                                    splitTime, splitRound, event.getTimeCreated(), event.getBirthRound())
                            .isLessThanOrEqualTo(splitRound);
                }
            }
        }
        return this;
    }

    /**
     * Verifies that the created consensus rounds are consistent.
     *
     * <p>This includes checking if the ancient thresholds are increasing and the timestamps of
     * events are strictly increasing.
     *
     * @return this assertion object for method chaining
     */
    public SingleNodeConsensusResultAssert hasConsistentRounds() {
        isNotNull();
        ConsensusRoundValidator.validate(actual.consensusRounds());
        return this;
    }

    /**
     * Verifies that no events were lost: every pre-consensus event that the latest consensus round made ancient has
     * either reached consensus or become stale, but not both. Only the results since the last
     * {@link SingleNodeConsensusResult#clear()} are checked.
     *
     * <p>The check is only valid while the node runs without a restart, reconnect or freeze. If the node has one of
     * them, call {@link SingleNodeConsensusResult#clear()} after it, so that only the results after it are checked.
     *
     * @return this assertion object for method chaining
     */
    @NonNull
    public SingleNodeConsensusResultAssert hasNoLostEvents() {
        isNotNull();
        failIfNotAllResultsArrived("lost events");

        // Pre-consensus events are read first, so that every one of them can have reached consensus or become stale
        final List<EventDescriptorWrapper> preConsensusEvents = actual.preConsensusEvents();
        final EventWindow eventWindow = actual.getLatestEventWindow();
        final Set<EventDescriptorWrapper> consensusEvents = consensusEventsOf(actual.consensusRounds());
        final Set<EventDescriptorWrapper> staleEvents = new HashSet<>(actual.staleEvents());

        final List<EventDescriptorWrapper> neither = new ArrayList<>();
        final List<EventDescriptorWrapper> both = new ArrayList<>();
        for (final EventDescriptorWrapper event : preConsensusEvents) {
            if (!eventWindow.isAncient(event)) {
                continue;
            }
            final boolean isConsensus = consensusEvents.contains(event);
            final boolean isStale = staleEvents.contains(event);
            if (isConsensus && isStale) {
                both.add(event);
            } else if (!isConsensus && !isStale) {
                neither.add(event);
            }
        }

        if (!neither.isEmpty() || !both.isEmpty()) {
            failWithMessage(
                    """
                    Expected every ancient pre-consensus event of node %s to have either reached consensus or become \
                    stale, but %d events did neither and %d events did both (ancient threshold: %d).
                    Neither: %s
                    Both: %s""",
                    actual.nodeId(),
                    neither.size(),
                    both.size(),
                    eventWindow.ancientThreshold(),
                    firstEvents(neither),
                    firstEvents(both));
        }
        return this;
    }

    /**
     * Verifies that every event that has reached consensus or become stale was emitted as a pre-consensus event
     * first.
     *
     * <p>{@link SingleNodeConsensusResult#clear()} discards pre-consensus events that may still reach consensus or
     * become stale afterward. Therefore, this assertion needs all results since the node started, and it fails if
     * {@link SingleNodeConsensusResult#clear()} has been called on the result.
     *
     * @return this assertion object for method chaining
     */
    @NonNull
    public SingleNodeConsensusResultAssert hasNoUnannouncedEvents() {
        isNotNull();
        failIfNotAllResultsArrived("unannounced events");
        if (actual.isCleared()) {
            failWithMessage("""
                    Cannot check node %s for unannounced events, because clear() has been called on its result. The \
                    check needs every pre-consensus event since the node started. Use a result from a new call to \
                    newConsensusResult() or newConsensusResults() that is never cleared.""", actual.nodeId());
        }

        // Pre-consensus events are read last, so that they include every event that has reached consensus or become
        // stale
        final Set<EventDescriptorWrapper> consensusEvents = consensusEventsOf(actual.consensusRounds());
        final List<EventDescriptorWrapper> staleEvents = actual.staleEvents();
        final Set<EventDescriptorWrapper> preConsensusEvents = new HashSet<>(actual.preConsensusEvents());

        final List<EventDescriptorWrapper> unannouncedConsensusEvents = consensusEvents.stream()
                .filter(event -> !preConsensusEvents.contains(event))
                .toList();
        final List<EventDescriptorWrapper> unannouncedStaleEvents = staleEvents.stream()
                .filter(event -> !preConsensusEvents.contains(event))
                .toList();

        if (!unannouncedConsensusEvents.isEmpty() || !unannouncedStaleEvents.isEmpty()) {
            failWithMessage(
                    """
                    Expected every consensus event and every stale event of node %s to have been emitted as a \
                    pre-consensus event first, but %d consensus events and %d stale events were not.
                    Consensus events: %s
                    Stale events: %s""",
                    actual.nodeId(),
                    unannouncedConsensusEvents.size(),
                    unannouncedStaleEvents.size(),
                    firstEvents(unannouncedConsensusEvents),
                    firstEvents(unannouncedStaleEvents));
        }
        return this;
    }

    private void failIfNotAllResultsArrived(@NonNull final String checkName) {
        if (!actual.allResultsArrived()) {
            failWithMessage(
                    "Cannot check node %s for %s, because not all of its results have arrived.",
                    actual.nodeId(), checkName);
        }
    }

    @NonNull
    private static Set<EventDescriptorWrapper> consensusEventsOf(@NonNull final List<ConsensusRound> rounds) {
        return rounds.stream()
                .flatMap(round -> round.getConsensusEvents().stream())
                .map(PlatformEvent::getDescriptor)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    @NonNull
    private static String firstEvents(@NonNull final List<EventDescriptorWrapper> events) {
        if (events.size() <= MAX_REPORTED_EVENTS) {
            return events.toString();
        }
        return events.subList(0, MAX_REPORTED_EVENTS) + " and " + (events.size() - MAX_REPORTED_EVENTS) + " more";
    }
}
