// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;

import com.swirlds.state.test.fixtures.MapWritableStates;
import com.swirlds.state.test.fixtures.StateTestBase;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class FilteredWritableStatesTest {

    @Nested
    @DisplayName("FilteredWritableStates over an empty delegate WritableStates")
    class EmptyDelegate extends StateTestBase {
        private FilteredWritableStates states;

        @BeforeEach
        void setUp() {
            final var delegate = MapWritableStates.builder().build();
            states = new FilteredWritableStates(delegate, Collections.emptySet());
        }

        @Test
        @DisplayName("Size is zero")
        void size() {
            assertThat(states.size()).isZero();
        }

        @Test
        @DisplayName("Is Empty")
        void empty() {
            assertThat(states.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("Contains")
        void contains() {
            assertThat(states.contains(FRUIT_STATE_ID)).isFalse();
        }

        @Test
        @DisplayName("Throws IAE for an unknown K/V state ID")
        void unknownKVStateId() {
            assertThatThrownBy(() -> states.get(UNKNOWN_STATE_ID)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Throws IAE for an unknown singleton state ID")
        void unknownSingletonStateId() {
            assertThatThrownBy(() -> states.getSingleton(UNKNOWN_STATE_ID))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Throws IAE for an unknown queue state ID")
        void unknownQueueStateId() {
            assertThatThrownBy(() -> states.getQueue(UNKNOWN_STATE_ID)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("FilteredWritableStates with no state keys specified")
    class NoStateKeys extends StateTestBase {
        private FilteredWritableStates states;

        @BeforeEach
        void setUp() {
            states = new FilteredWritableStates(allWritableStates(), Collections.emptySet());
        }

        @Test
        @DisplayName("Size is zero")
        void size() {
            assertThat(states.size()).isZero();
        }

        @Test
        @DisplayName("Is Empty")
        void empty() {
            assertThat(states.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("Contains")
        void contains() {
            assertThat(states.contains(FRUIT_STATE_ID)).isFalse();
        }

        @Test
        @DisplayName("Throws IAE for an unknown K/V state ID")
        void unknownKVStateId() {
            assertThatThrownBy(() -> states.get(UNKNOWN_STATE_ID)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Throws IAE for an unknown singleton state ID")
        void unknownSingletonStateId() {
            assertThatThrownBy(() -> states.getSingleton(UNKNOWN_STATE_ID))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Throws IAE for an unknown queue state ID")
        void unknownQueueStateId() {
            assertThatThrownBy(() -> states.getQueue(UNKNOWN_STATE_ID)).isInstanceOf(IllegalArgumentException.class);
        }

        @NonNull
        protected MapWritableStates allWritableStates() {
            return MapWritableStates.builder()
                    .state(writableCountryState())
                    .state(writableSTEAMState())
                    .build();
        }
    }

    @Nested
    @DisplayName("FilteredWritableStates with a subset of state keys available in the delegate")
    class Subset extends StateTestBase {
        private FilteredWritableStates states;

        @BeforeEach
        void setUp() {
            final var delegate = MapWritableStates.builder()
                    .state(writableFruitState())
                    .state(writableCountryState()) // <-- singleton state
                    .state(writableSTEAMState()) // <-- queue state
                    .build();
            states = new FilteredWritableStates(delegate, Set.of(COUNTRY_STATE_ID));
        }

        @Test
        @DisplayName("Exactly 1 state was included")
        void size() {
            assertThat(states.size()).isEqualTo(1);
        }

        @Test
        @DisplayName("Is Not Empty")
        void empty() {
            assertThat(states.isEmpty()).isFalse();
        }

        @Test
        @DisplayName("Contains")
        void contains() {
            assertThat(states.contains(FRUIT_STATE_ID)).isFalse();
            assertThat(states.contains(COUNTRY_STATE_ID)).isTrue();
            assertThat(states.contains(STEAM_STATE_ID)).isFalse();
        }

        @Test
        @DisplayName("Can read the states")
        void acceptedStates() {
            assertThat(states.getSingleton(COUNTRY_STATE_ID)).isNotNull();
        }

        @Test
        @DisplayName("Throws IAE for other than the two specified states")
        void filteredStates() {
            assertThatThrownBy(() -> states.get(FRUIT_STATE_ID)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> states.get(STEAM_STATE_ID)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("FilteredWritableStates allows more state keys than are in the delegate")
    class Superset extends StateTestBase {
        private FilteredWritableStates states;

        @BeforeEach
        void setUp() {
            final var delegate = MapWritableStates.builder()
                    .state(writableFruitState())
                    .state(writableCountryState())
                    .build();
            states = new FilteredWritableStates(delegate, Set.of(FRUIT_STATE_ID, COUNTRY_STATE_ID));
        }

        @Test
        @DisplayName(
                "Exactly 2 states were included because only two of four filtered states were in" + " the delegate")
        void size() {
            assertThat(states.size()).isEqualTo(2);
        }

        @Test
        @DisplayName("Is Not Empty")
        void empty() {
            assertThat(states.isEmpty()).isFalse();
        }

        @Test
        @DisplayName("Contains")
        void contains() {
            assertThat(states.contains(FRUIT_STATE_ID)).isTrue();
            assertThat(states.contains(COUNTRY_STATE_ID)).isTrue();
            assertThat(states.contains(UNKNOWN_STATE_ID)).isFalse();
        }

        @Test
        @DisplayName("Can read FRUIT and COUNTRY because they are in the acceptable set and in the" + " delegate")
        void acceptedStates() {
            assertThat(states.get(FRUIT_STATE_ID)).isNotNull();
            assertThat(states.getSingleton(COUNTRY_STATE_ID)).isNotNull();
        }

        @Test
        @DisplayName("Cannot read STEAM because it is not in the delegate")
        void missingState() {
            assertThatThrownBy(() -> states.get(STEAM_STATE_ID)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> states.getSingleton(STEAM_STATE_ID)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("StateKeys Tests")
    class StateKeysTest extends StateTestBase {
        private WritableStates delegate;

        @BeforeEach
        void setUp() {
            delegate = MapWritableStates.builder()
                    .state(writableFruitState())
                    .state(writableCountryState())
                    .build();
        }

        @Test
        @DisplayName("The filtered `stateIds` contains all states that are in the filter and in the" + " delegate")
        void filteredStateIds() {
            // Given a delegate with multiple k/v states and a set of state IDs that are
            // a subset of keys in the delegate AND contain some keys not in the delegate
            final var stateIds = Set.of(STEAM_STATE_ID, COUNTRY_STATE_ID);
            final var filtered = new FilteredWritableStates(delegate, stateIds);

            // When we look at the contents of the filtered `stateIds`
            final var filteredStateIds = filtered.stateIds();

            // Then we find only those states that are both in the state IDs passed to
            // the FilteredWritableStates, and in the delegate.
            assertThat(filteredStateIds).containsExactlyInAnyOrder(COUNTRY_STATE_ID);
        }

        @Test
        @DisplayName("A modifiable `stateIds` set provided to a constructor can be changed without"
                + " impacting the FilteredWritableStates")
        void modifiableStateIDs() {
            // Given a delegate with multiple k/v states and a modifiable set of state IDs,
            final var modifiableStateIds = new HashSet<Integer>();
            modifiableStateIds.add(COUNTRY_STATE_ID);

            // When a FilteredWritableStates is created, and the Set of all state IDs for
            // the filtered set is read and the modifiable state IDs map is modified
            final var filtered = new FilteredWritableStates(delegate, modifiableStateIds);
            final var filteredStateIds = filtered.stateIds();
            modifiableStateIds.add(STEAM_STATE_ID);
            modifiableStateIds.remove(COUNTRY_STATE_ID);

            // Then these changes are NOT found in the filtered state IDs
            assertThat(filteredStateIds).containsExactlyInAnyOrder(COUNTRY_STATE_ID);
        }

        @Test
        @DisplayName("The set of filtered state IDs is unmodifiable")
        void filteredStateIdsAreUnmodifiable() {
            // Given a FilteredWritableStates
            final var stateIds = Set.of(COUNTRY_STATE_ID);
            final var filtered = new FilteredWritableStates(delegate, stateIds);

            // When the filtered state keys is read and a modification attempted,
            // then an exception is thrown
            final var filteredStateIds = filtered.stateIds();
            assertThatThrownBy(() -> filteredStateIds.add(FRUIT_STATE_ID))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
