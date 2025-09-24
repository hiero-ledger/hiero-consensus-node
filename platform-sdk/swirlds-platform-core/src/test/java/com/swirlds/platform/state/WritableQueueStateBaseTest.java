// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.inOrder;

import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.swirlds.state.spi.QueueChangeListener;
import com.swirlds.state.test.fixtures.ListWritableQueueState;
import java.util.ConcurrentModificationException;
import java.util.LinkedList;
import java.util.Queue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

final class WritableQueueStateBaseTest extends ReadableQueueStateBaseTest {

    @Nested
    class AddTests {
        @Test
        void addDoesNotChangeDataSource() {
            final var backingList = new LinkedList<String>();
            final var subject = new ListWritableQueueState<>(STEAM_STATE_ID, STEAM_STATE_LABEL, backingList);
            final var element = "Hydrology";

            subject.add(element);

            assertThat(backingList).isEmpty();
        }

        @Test
        void iteratorIsInvalidAfterAdd() {
            final var subject = writableSTEAMState();
            final var element = toProtoBytes("Hydrology");

            final var iterator = subject.iterator();
            assertThat(iterator.hasNext()).isTrue();
            assertThat(iterator.next()).isEqualTo(ART);
            subject.add(element);
            assertThatThrownBy(iterator::next).isInstanceOf(ConcurrentModificationException.class);
        }

        @Test
        void iterateAfterAddGivesNewElements() {
            final var subject = writableSTEAMState();
            final var element = toProtoBytes("Hydrology");

            subject.add(element);

            assertThat(subject.iterator())
                    .toIterable()
                    .containsExactly(ART, BIOLOGY, CHEMISTRY, DISCIPLINE, ECOLOGY, FIELDS, GEOMETRY, element);
        }

        @Test
        void addOnEmptyIsVisibleWithPeek() {
            final var subject =
                    ListWritableQueueState.builder(FAKE_STATE_ID, LABEL).build();
            final var element = toProtoBytes("Hydrology");

            subject.add(element);

            assertThat(subject.peek()).isSameAs(element);
        }
    }

    @Nested
    class PeekTests {
        @Test
        void peekOnEmptyList() {
            final var subject =
                    ListWritableQueueState.builder(FAKE_STATE_ID, LABEL).build();
            assertThat(subject.peek()).isNull();
        }

        @Test
        void peekOnPopulatedList() {
            final var subject = writableSTEAMState();
            assertThat(subject.peek()).isSameAs(ART);
        }

        @Test
        void peekAfterPoll() {
            final var subject = writableSTEAMState();
            subject.poll();
            assertThat(subject.peek()).isSameAs(BIOLOGY);
        }

        @Test
        void peekAfterRemoveIf() {
            final var subject = writableSTEAMState();
            subject.removeIf(s -> s.equals(ART));
            assertThat(subject.peek()).isSameAs(BIOLOGY);
        }

        @Test
        void peekAfterRemoveIfFails() {
            final var subject = writableSTEAMState();
            subject.removeIf(s -> false);
            assertThat(subject.peek()).isSameAs(ART);
        }

        @Test
        void peekAfterAdd() {
            final var subject = writableSTEAMState();
            subject.add(toProtoBytes("Hydrology"));
            assertThat(subject.peek()).isSameAs(ART);
        }

        @Test
        void peekAfterAddOnEmptyList() {
            final var subject =
                    ListWritableQueueState.builder(FAKE_STATE_ID, LABEL).build();
            final var value = toProtoBytes("Hydrology");
            subject.add(value);
            assertThat(subject.peek()).isSameAs(value);
        }
    }

    @Nested
    class PollTests {
        @Test
        void pollOnEmptyList() {
            final var subject =
                    ListWritableQueueState.builder(FAKE_STATE_ID, LABEL).build();
            assertThat(subject.poll()).isNull();
        }

        @Test
        void pollOnPopulatedList() {
            final var subject = writableSTEAMState();
            assertThat(subject.poll()).isSameAs(ART);
            assertThat(subject.poll()).isSameAs(BIOLOGY);
            assertThat(subject.poll()).isSameAs(CHEMISTRY);
            assertThat(subject.poll()).isSameAs(DISCIPLINE);
            assertThat(subject.poll()).isSameAs(ECOLOGY);
            assertThat(subject.poll()).isSameAs(FIELDS);
            assertThat(subject.poll()).isSameAs(GEOMETRY);
            assertThat(subject.poll()).isNull();
        }

        @Test
        void pollAfterPeek() {
            final var subject = writableSTEAMState();
            subject.peek();
            assertThat(subject.poll()).isSameAs(ART);
        }

        @Test
        void pollAfterRemoveIf() {
            final var subject = writableSTEAMState();
            subject.removeIf(s -> s.equals(ART));
            assertThat(subject.poll()).isSameAs(BIOLOGY);
        }

        @Test
        void pollAfterRemoveIfFails() {
            final var subject = writableSTEAMState();
            subject.removeIf(s -> false);
            assertThat(subject.poll()).isSameAs(ART);
        }

        @Test
        void pollAfterAdd() {
            final var subject = writableSTEAMState();
            final var value = toProtoBytes("Hydrology");
            subject.add(value);
            assertThat(subject.poll()).isSameAs(ART);
            assertThat(subject.poll()).isSameAs(BIOLOGY);
            assertThat(subject.poll()).isSameAs(CHEMISTRY);
            assertThat(subject.poll()).isSameAs(DISCIPLINE);
            assertThat(subject.poll()).isSameAs(ECOLOGY);
            assertThat(subject.poll()).isSameAs(FIELDS);
            assertThat(subject.poll()).isSameAs(GEOMETRY);
            assertThat(subject.poll()).isSameAs(value);
            assertThat(subject.poll()).isNull();
        }

        @Test
        void pollAfterAddOnEmptyList() {
            final var subject =
                    ListWritableQueueState.builder(FAKE_STATE_ID, LABEL).build();
            subject.add("Hydrology");
            assertThat(subject.poll()).isSameAs("Hydrology");
        }
    }

    @Nested
    class RemoveIfTests {
        @Test
        void removeIfOnEmptyList() {
            final var subject =
                    ListWritableQueueState.builder(FAKE_STATE_ID, LABEL).build();
            assertThat(subject.removeIf(s -> true)).isNull();
        }

        @Test
        void removeIfFalsePredicate() {
            final var subject = writableSTEAMState();
            assertThat(subject.removeIf(s -> false)).isNull();
        }

        @Test
        void removeIfAfterAdd() {
            final var subject =
                    ListWritableQueueState.builder(FAKE_STATE_ID, LABEL).build();
            subject.add("Hydrology");
            assertThat(subject.removeIf(s -> true)).isSameAs("Hydrology");
        }

        @Test
        void removeIfOnPopulatedList() {
            final var subject = writableSTEAMState();
            assertThat(subject.removeIf(s -> true)).isSameAs(ART);
            assertThat(subject.removeIf(s -> true)).isSameAs(BIOLOGY);
            assertThat(subject.removeIf(s -> true)).isSameAs(CHEMISTRY);
            assertThat(subject.removeIf(s -> true)).isSameAs(DISCIPLINE);
            assertThat(subject.removeIf(s -> true)).isSameAs(ECOLOGY);
            assertThat(subject.removeIf(s -> true)).isSameAs(FIELDS);
            assertThat(subject.removeIf(s -> true)).isSameAs(GEOMETRY);
            assertThat(subject.removeIf(s -> true)).isNull();
        }

        @Test
        void removeIfAfterPeek() {
            final var subject = writableSTEAMState();
            subject.peek();
            assertThat(subject.removeIf(s -> true)).isSameAs(ART);
        }

        @Test
        void removeIfAfterPoll() {
            final var subject = writableSTEAMState();
            subject.poll();
            assertThat(subject.removeIf(s -> true)).isSameAs(BIOLOGY);
        }

        @Test
        void removeIfAfterRemoveIfFails() {
            final var subject = writableSTEAMState();
            subject.removeIf(s -> false);
            assertThat(subject.removeIf(s -> true)).isSameAs(ART);
        }
    }

    @Nested
    class CommitTests {
        @Test
        void commitOnEmptyList() {
            final var backingList = new LinkedList<String>();
            final var subject = new ListWritableQueueState<>(STEAM_STATE_ID, STEAM_STATE_LABEL, backingList);
            subject.commit();
            assertThat(backingList).isEmpty();
        }

        @Test
        void commitAfterAddOnEmptyList() {
            final var backingList = new LinkedList<String>();
            final var subject = new ListWritableQueueState<>(STEAM_STATE_ID, STEAM_STATE_LABEL, backingList);
            final var element = "Hydrology";

            subject.add(element);
            subject.commit();

            assertThat(backingList).containsExactly(element);
        }

        @Test
        void commitTwice() {
            final var backingList = new LinkedList<String>();
            final var subject = new ListWritableQueueState<>(STEAM_STATE_ID, STEAM_STATE_LABEL, backingList);
            final var element = "Hydrology";

            subject.add(element);
            subject.commit();
            subject.commit();

            assertThat(backingList).containsExactly(element);
        }

        @Test
        void commitAfterAddOnNonEmptyList() {
            final var backingList = new LinkedList<ProtoBytes>();
            backingList.add(ART);
            final var subject = new ListWritableQueueState<>(STEAM_STATE_ID, STEAM_STATE_LABEL, backingList);

            subject.add(BIOLOGY);
            subject.commit();

            assertThat(backingList).containsExactly(ART, BIOLOGY);
        }

        @Test
        void commitAfterRemoving() {
            final var backingList = new LinkedList<ProtoBytes>();
            backingList.add(ART);
            final var subject = new ListWritableQueueState<>(STEAM_STATE_ID, STEAM_STATE_LABEL, backingList);

            assertThat(subject.poll()).isEqualTo(ART);
            assertThat(backingList).containsExactly(ART);
            subject.commit();

            assertThat(backingList).isEmpty();
        }

        @Test
        void commitAfterRemovingAllAddedElementsDoesNotThrow() {
            final var backingList = new LinkedList<ProtoBytes>();
            final var subject = new ListWritableQueueState<>(STEAM_STATE_ID, STEAM_STATE_LABEL, backingList);
            subject.add(ART);
            subject.add(BIOLOGY);
            subject.removeIf(s -> true);
            subject.removeIf(s -> true);
            assertThatCode(subject::commit).doesNotThrowAnyException();
        }

        @Test
        void commitAfterRemovingSomeAddedElementsOnlyIncludesAdded() {
            final var backingList = new LinkedList<ProtoBytes>();
            final var subject = new ListWritableQueueState<>(STEAM_STATE_ID, STEAM_STATE_LABEL, backingList);
            subject.add(ART);
            subject.add(BIOLOGY);
            subject.add(CHEMISTRY);
            subject.removeIf(s -> true);
            subject.peek();
            subject.removeIf(s -> true);
            subject.peek();
            subject.commit();
            assertThat(backingList).containsExactly(CHEMISTRY);
        }

        @Test
        void commitResetsIndex() {
            final var backingList = new LinkedList<ProtoBytes>();
            final var subject = new ListWritableQueueState<>(STEAM_STATE_ID, STEAM_STATE_LABEL, backingList);
            subject.add(ART);
            subject.add(BIOLOGY);
            subject.removeIf(s -> true);
            assertEquals(BIOLOGY, subject.peek());
            subject.removeIf(s -> true);
            subject.commit();
            assertThat(backingList).isEmpty();

            subject.add(ART);
            subject.add(ECOLOGY);
            assertEquals(ART, subject.peek());
        }

        @Test
        void commitAfterPeekingAndAddingStillAddsEverything() {
            final var backingList = new LinkedList<ProtoBytes>();
            backingList.add(ART);
            final var subject = new ListWritableQueueState<>(STEAM_STATE_ID, STEAM_STATE_LABEL, backingList);
            subject.peek();
            subject.add(BIOLOGY);
            subject.commit();
            assertThat(backingList).containsExactly(ART, BIOLOGY);
        }
    }

    @Nested
    class ResetTests {
        @Test
        void resetOnEmptyList() {
            final var backingList = new LinkedList<String>();
            final var subject = new ListWritableQueueState<>(STEAM_STATE_ID, STEAM_STATE_LABEL, backingList);
            subject.reset();
            assertThat(backingList).isEmpty();
        }

        @Test
        void resetBeforeCommit() {
            final var backingList = new LinkedList<ProtoBytes>();
            final var subject = new ListWritableQueueState<>(STEAM_STATE_ID, STEAM_STATE_LABEL, backingList);

            subject.add(ART);
            subject.reset();
            subject.commit();

            assertThat(backingList).isEmpty();
        }

        @Test
        void resetAfterCommit() {
            final var backingList = new LinkedList<ProtoBytes>();
            final var subject = new ListWritableQueueState<>(STEAM_STATE_ID, STEAM_STATE_LABEL, backingList);

            subject.add(ART);
            subject.commit();
            subject.reset();

            assertThat(backingList).containsExactly(ART);
        }
    }

    @Nested
    @DisplayName("with registered listeners")
    @ExtendWith(MockitoExtension.class)
    final class WithRegisteredListeners {
        @Mock
        private QueueChangeListener<String> firstListener;

        @Mock
        private QueueChangeListener<String> secondListener;

        private final Queue<String> backingStore = new LinkedList<>();
        private final ListWritableQueueState<String> subject =
                new ListWritableQueueState<>(STEAM_STATE_ID, STEAM_STATE_LABEL, backingStore);

        @BeforeEach
        void setUp() {
            subject.registerListener(firstListener);
            subject.registerListener(secondListener);
        }

        @Test
        @DisplayName("all listeners are notified of pops and adds in order")
        void allAreNotifiedOfPopsAndAddsInOrder() {
            final var inOrder = inOrder(firstListener, secondListener);
            backingStore.add("Apple");
            backingStore.add("Banana");
            backingStore.add("Cantaloupe");

            subject.removeIf(s -> true);
            subject.removeIf(s -> true);
            subject.add("Dragonfruit");
            subject.commit();

            inOrder.verify(firstListener).queuePopChange();
            inOrder.verify(secondListener).queuePopChange();
            inOrder.verify(firstListener).queuePopChange();
            inOrder.verify(secondListener).queuePopChange();
            inOrder.verify(firstListener).queuePushChange("Dragonfruit");
            inOrder.verify(secondListener).queuePushChange("Dragonfruit");
        }
    }
}
