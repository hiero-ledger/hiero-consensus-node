// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.falcon;

import static org.assertj.core.api.Assertions.assertThat;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import java.util.Random;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.test.fixtures.event.TestingEventBuilder;
import org.hiero.consensus.model.test.fixtures.hashgraph.EventWindowBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("FalconMiniIntake Test")
class FalconMiniIntakeTest {

    private static final long RANDOM_SEED = 42L;

    /** The random number generator for the events. */
    private Random random;

    /** The intake under test. */
    private FalconMiniIntake intake;

    @BeforeEach
    void setUp() {
        random = new Random(RANDOM_SEED);
        intake = new FalconMiniIntake();
    }

    @Test
    @DisplayName("An event whose parents are present is released at once")
    void releasesEventWithParentsPresent() {
        final PlatformEvent a = event(0, null, null, 1);
        final PlatformEvent b = event(0, a, null, 1);

        assertThat(handle(a)).containsExactly(a);
        assertThat(handle(b)).containsExactly(b);
        assertThat(List.of(a, b)).extracting(PlatformEvent::getSequenceNumber).containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("A duplicate of a released event is dropped and uses no sequence number")
    void dropsDuplicateOfReleasedEvent() {
        final PlatformEvent a = event(0, null, null, 1);
        final PlatformEvent b = event(0, a, null, 1);

        assertThat(handle(a)).containsExactly(a);
        assertThat(handle(a)).isEmpty();
        assertThat(handle(a.copyGossipedData())).isEmpty();
        assertThat(handle(b)).containsExactly(b);
        assertThat(b.getSequenceNumber()).isEqualTo(2L);
    }

    @Test
    @DisplayName("A duplicate of an event waiting for its parents is dropped")
    void dropsDuplicateOfOrphan() {
        final PlatformEvent a = event(0, null, null, 1);
        final PlatformEvent b = event(0, a, null, 1);

        assertThat(handle(b)).isEmpty();
        assertThat(handle(b.copyGossipedData())).isEmpty();
        assertThat(handle(a)).containsExactly(a, b);
        assertThat(List.of(a, b)).extracting(PlatformEvent::getSequenceNumber).containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("An ancient event is dropped")
    void dropsAncientEvent() {
        final PlatformEvent ancient = event(0, null, null, 2);
        final PlatformEvent nonAncient = event(1, null, null, 3);

        assertThat(shift(3)).isEmpty();
        assertThat(handle(ancient)).isEmpty();
        assertThat(handle(nonAncient)).containsExactly(nonAncient);
        assertThat(nonAncient.getSequenceNumber()).isEqualTo(1L);
    }

    @Test
    @DisplayName("An ancient parent counts as present")
    void ancientParentCountsAsPresent() {
        final PlatformEvent parent = event(0, null, null, 1);
        final PlatformEvent child = event(0, parent, null, 3);

        assertThat(shift(2)).isEmpty();
        assertThat(handle(child)).containsExactly(child);
    }

    @Test
    @DisplayName("Orphans are released when their parent arrives")
    void releasesOrphansWhenParentArrives() {
        final PlatformEvent a = event(0, null, null, 1);
        final PlatformEvent b = event(0, a, null, 1);
        final PlatformEvent c = event(0, b, null, 1);

        assertThat(handle(c)).isEmpty();
        assertThat(handle(b)).isEmpty();
        assertThat(handle(a)).containsExactly(a, b, c);
        assertThat(List.of(a, b, c))
                .extracting(PlatformEvent::getSequenceNumber)
                .containsExactly(1L, 2L, 3L);
    }

    @Test
    @DisplayName("An orphan is released only when all its missing parents are there")
    void waitsForAllMissingParents() {
        final PlatformEvent a = event(0, null, null, 1);
        final PlatformEvent b = event(1, null, null, 1);
        final PlatformEvent c = event(0, a, b, 1);

        assertThat(handle(c)).isEmpty();
        assertThat(handle(a)).containsExactly(a);
        assertThat(handle(b)).containsExactly(b, c);
    }

    @Test
    @DisplayName("Orphans are released depth first, in the same order as the orphan buffer")
    void releasesOrphansDepthFirst() {
        final PlatformEvent p = event(0, null, null, 1);
        final PlatformEvent x = event(1, null, p, 1);
        final PlatformEvent y = event(2, null, p, 1);
        final PlatformEvent x1 = event(1, x, null, 1);

        assertThat(handle(x)).isEmpty();
        assertThat(handle(y)).isEmpty();
        assertThat(handle(x1)).isEmpty();

        // x and y wait for p in the order they arrived; the stack hands out the one pushed last first
        assertThat(handle(p)).containsExactly(p, y, x, x1);
        assertThat(List.of(p, y, x, x1))
                .extracting(PlatformEvent::getSequenceNumber)
                .containsExactly(1L, 2L, 3L, 4L);
    }

    @Test
    @DisplayName("Orphans are released when their missing parent becomes ancient")
    void releasesOrphansWhenMissingParentBecomesAncient() {
        final PlatformEvent neverDelivered = event(0, null, null, 1);
        final PlatformEvent orphan = event(1, null, neverDelivered, 3);
        final PlatformEvent child = event(1, orphan, null, 3);

        assertThat(handle(orphan)).isEmpty();
        assertThat(handle(child)).isEmpty();
        assertThat(shift(2)).containsExactly(orphan, child);
        assertThat(List.of(orphan, child))
                .extracting(PlatformEvent::getSequenceNumber)
                .containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("An orphan that became ancient is not released when its parent arrives")
    void doesNotReleaseOrphanThatBecameAncient() {
        final PlatformEvent parent = event(0, null, null, 3);
        final PlatformEvent orphan = event(1, null, parent, 1);

        assertThat(handle(orphan)).isEmpty();
        assertThat(shift(2)).isEmpty();
        assertThat(handle(parent)).containsExactly(parent);
        assertThat(parent.getSequenceNumber()).isEqualTo(1L);
    }

    @Test
    @DisplayName("Events that become ancient are forgotten")
    void forgetsEventsThatBecomeAncient() {
        final PlatformEvent a = event(0, null, null, 1);
        final PlatformEvent b = event(1, null, null, 2);
        final PlatformEvent c = event(2, null, null, 4);
        handle(a);
        handle(b);
        handle(c);
        assertThat(intake.getKnownEventCount()).isEqualTo(3);

        assertThat(shift(2)).isEmpty();
        assertThat(intake.getKnownEventCount()).isEqualTo(2);
        assertThat(shift(5)).isEmpty();
        assertThat(intake.getKnownEventCount()).isZero();

        // the birth rounds after the shifts are kept apart in the lists that were reused
        final PlatformEvent d = event(3, null, null, 7);
        final PlatformEvent e = event(4, null, null, 5);
        assertThat(handle(d)).containsExactly(d);
        assertThat(handle(e)).containsExactly(e);
        assertThat(handle(d)).isEmpty();
        assertThat(shift(6)).isEmpty();
        assertThat(intake.getKnownEventCount()).isEqualTo(1);

        // a shift past every birth round that has a list
        assertThat(shift(20)).isEmpty();
        assertThat(intake.getKnownEventCount()).isZero();
        final PlatformEvent f = event(5, null, null, 21);
        assertThat(handle(f)).containsExactly(f);
        assertThat(handle(f)).isEmpty();
        assertThat(intake.getKnownEventCount()).isEqualTo(1);
    }

    /**
     * Creates an event.
     *
     * @param creator the id of the creator
     * @param selfParent the self parent, or {@code null} if there is none
     * @param otherParent the other parent, or {@code null} if there is none
     * @param birthRound the birth round
     * @return the event
     */
    @NonNull
    private PlatformEvent event(
            final long creator,
            @Nullable final PlatformEvent selfParent,
            @Nullable final PlatformEvent otherParent,
            final long birthRound) {
        return new TestingEventBuilder(random)
                .setCreatorId(NodeId.of(creator))
                .setSelfParent(selfParent)
                .setOtherParent(otherParent)
                .setBirthRound(birthRound)
                .build();
    }

    /**
     * Passes an event to the intake.
     *
     * @param event the event
     * @return a copy of the events released by it
     */
    @NonNull
    private List<PlatformEvent> handle(@NonNull final PlatformEvent event) {
        return List.copyOf(intake.handleEvent(event));
    }

    /**
     * Moves the event window of the intake.
     *
     * @param ancientThreshold the new ancient threshold
     * @return a copy of the events released by it
     */
    @NonNull
    private List<PlatformEvent> shift(final long ancientThreshold) {
        return List.copyOf(intake.setEventWindow(EventWindowBuilder.builder()
                .setLatestConsensusRound(ancientThreshold)
                .setAncientThreshold(ancientThreshold)
                .build()));
    }
}
