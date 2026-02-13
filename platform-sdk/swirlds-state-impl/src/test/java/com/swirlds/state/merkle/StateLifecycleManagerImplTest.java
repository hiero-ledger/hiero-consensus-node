// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.merkle;

import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.assertAllDatabasesClosed;
import static com.swirlds.state.test.fixtures.merkle.VirtualMapStateTestUtils.createTestState;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.swirlds.base.time.Time;
import com.swirlds.state.State;
import com.swirlds.state.test.fixtures.merkle.MerkleTestBase;
import com.swirlds.state.test.fixtures.merkle.VirtualMapStateTestUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.hiero.consensus.metrics.noop.NoOpMetrics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class StateLifecycleManagerImplTest extends MerkleTestBase {

    private StateLifecycleManagerImpl subject;

    @BeforeEach
    void setUp() {
        subject = new StateLifecycleManagerImpl(
                new NoOpMetrics(), Time.getCurrent(), VirtualMapStateTestUtils::createTestStateWithVM, CONFIGURATION);
    }

    @AfterEach
    void tearDown() {
        // Release all states held by the lifecycle manager to close databases
        releaseManagerStates();
        assertAllDatabasesClosed();
    }

    private void releaseManagerStates() {
        try {
            final var mutable = subject.getMutableState();
            if (!mutable.isDestroyed()) {
                mutable.release();
            }
        } catch (final IllegalStateException ignored) {
            // not initialized
        }
        try {
            final var immutable = subject.getLatestImmutableState();
            if (!immutable.isDestroyed()) {
                immutable.release();
            }
        } catch (final IllegalStateException ignored) {
            // not initialized
        }
    }

    @Nested
    @DisplayName("Observer Tests")
    final class ObserverTests {

        @Test
        @DisplayName("Observer is notified when initState is called")
        void observerNotifiedOnInitState() {
            final var observedState = new AtomicReference<State>();
            subject.addObserver(observedState::set);

            final var state = createTestState();
            subject.initState(state);

            assertThat(observedState.get()).isNotNull();
            // The observer receives the original state (which becomes immutable after copy)
            assertThat(observedState.get()).isSameAs(state);
            assertThat(state.isMutable()).isFalse();
        }

        @Test
        @DisplayName("Observer is notified when initStateOnReconnect is called")
        void observerNotifiedOnInitStateOnReconnect() {
            final var observedState = new AtomicReference<State>();
            subject.addObserver(observedState::set);

            final var state = createTestState();
            subject.initStateOnReconnect(state);

            assertThat(observedState.get()).isNotNull();
            assertThat(observedState.get()).isSameAs(state);
            assertThat(state.isMutable()).isFalse();
        }

        @Test
        @DisplayName("Observer is notified when copyMutableState is called")
        void observerNotifiedOnCopyMutableState() {
            final var state = createTestState();
            subject.initState(state);

            final var observedState = new AtomicReference<State>();
            subject.addObserver(observedState::set);

            subject.copyMutableState();

            assertThat(observedState.get()).isNotNull();
            // The observer receives the state that just became immutable (not the new mutable)
            assertThat(observedState.get()).isNotSameAs(subject.getMutableState());
            assertThat(observedState.get().isMutable()).isFalse();
        }

        @Test
        @DisplayName("Multiple observers are all notified")
        void multipleObserversNotified() {
            final var count = new AtomicInteger(0);
            final var first = new AtomicReference<State>();
            final var second = new AtomicReference<State>();

            subject.addObserver(s -> {
                count.incrementAndGet();
                first.set(s);
            });
            subject.addObserver(s -> {
                count.incrementAndGet();
                second.set(s);
            });

            final var state = createTestState();
            subject.initState(state);

            assertThat(count.get()).isEqualTo(2);
            assertThat(first.get()).isSameAs(state);
            assertThat(second.get()).isSameAs(state);
        }

        @Test
        @DisplayName("Observer receives immutable state on each copy")
        void observerReceivesImmutableStateOnEachCopy() {
            final List<State> observed = new ArrayList<>();
            subject.addObserver(observed::add);

            final var state = createTestState();
            subject.initState(state);
            assertThat(observed).hasSize(1);

            subject.copyMutableState();
            assertThat(observed).hasSize(2);

            subject.copyMutableState();
            assertThat(observed).hasSize(3);

            // Each observed state should be immutable
            for (final var s : observed) {
                assertThat(s.isMutable()).isFalse();
            }
        }

        @Test
        @DisplayName("No observers registered means no errors on init")
        void noObserversRegisteredDoesNotFail() {
            final var state = createTestState();
            subject.initState(state);

            assertThat(subject.getMutableState()).isNotNull();
            assertThat(subject.getLatestImmutableState()).isNotNull();
        }
    }

    @Nested
    @DisplayName("State Lifecycle Tests")
    final class LifecycleTests {

        @Test
        @DisplayName("getMutableState throws before initialization")
        void getMutableStateThrowsBeforeInit() {
            assertThatThrownBy(() -> subject.getMutableState()).isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("getLatestImmutableState throws before initialization")
        void getLatestImmutableStateThrowsBeforeInit() {
            assertThatThrownBy(() -> subject.getLatestImmutableState()).isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("initState sets both mutable and immutable state")
        void initStateSetsStates() {
            final var state = createTestState();
            subject.initState(state);

            assertThat(subject.getMutableState()).isNotNull();
            assertThat(subject.getMutableState().isMutable()).isTrue();
            assertThat(subject.getLatestImmutableState()).isNotNull();
            assertThat(subject.getLatestImmutableState().isMutable()).isFalse();
        }

        @Test
        @DisplayName("initState throws if already initialized")
        void initStateThrowsIfAlreadyInitialized() {
            final var state = createTestState();
            subject.initState(state);

            final var state2 = createTestState();
            assertThatThrownBy(() -> subject.initState(state2)).isInstanceOf(IllegalStateException.class);
            // Clean up the second state that was never managed
            state2.release();
        }

        @Test
        @DisplayName("copyMutableState produces new mutable and updates immutable state")
        void copyMutableStateProducesNewStates() {
            final var state = createTestState();
            subject.initState(state);

            final var originalMutable = subject.getMutableState();
            final var originalImmutable = subject.getLatestImmutableState();

            subject.copyMutableState();

            assertThat(subject.getMutableState()).isNotSameAs(originalMutable);
            assertThat(subject.getLatestImmutableState()).isNotSameAs(originalImmutable);
            assertThat(subject.getMutableState().isMutable()).isTrue();
            assertThat(subject.getLatestImmutableState().isMutable()).isFalse();
        }
    }
}
