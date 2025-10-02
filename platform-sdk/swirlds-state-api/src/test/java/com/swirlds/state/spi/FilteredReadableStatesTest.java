// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;

import com.swirlds.state.test.fixtures.MapReadableStates;
import com.swirlds.state.test.fixtures.StateTestBase;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class FilteredReadableStatesTest {

    @Nested
    @DisplayName("FilteredReadableStates over an empty delegate ReadableStates")
    class EmptyDelegate extends StateTestBase {
        private FilteredReadableStates states;

        @BeforeEach
        void setUp() {
            final var delegate = MapReadableStates.builder().build();
            states = new FilteredReadableStates(delegate, Collections.emptySet());
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
    @DisplayName("FilteredReadableStates with no state keys specified")
    class NoStateKeys extends StateTestBase {
        private FilteredReadableStates states;

        @BeforeEach
        void setUp() {
            states = new FilteredReadableStates(allReadableStates(), Collections.emptySet());
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
        protected MapReadableStates allReadableStates() {
            return MapReadableStates.builder()
                    .state(readableFruitState())
                    .state(readableCountryState())
                    .state(readableSTEAMState())
                    .build();
        }
    }

    @Nested
    @DisplayName("FilteredReadableStates with a subset of state keys available in the delegate")
    class Subset extends StateTestBase {
        private FilteredReadableStates states;

        @BeforeEach
        void setUp() {
            final var delegate = MapReadableStates.builder()
                    .state(readableFruitState())
                    .state(readableCountryState()) // <-- singleton state
                    .state(readableSTEAMState()) // <-- queue state
                    .build();
            states = new FilteredReadableStates(delegate, Set.of(COUNTRY_STATE_ID));
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
    @DisplayName("FilteredReadableStates allows more state keys than are in the delegate")
    class Superset extends StateTestBase {
        private FilteredReadableStates states;

        @BeforeEach
        void setUp() {
            final var delegate = MapReadableStates.builder()
                    .state(readableFruitState())
                    .state(readableCountryState())
                    .build();
            states = new FilteredReadableStates(delegate, Set.of(FRUIT_STATE_ID, COUNTRY_STATE_ID));
        }

        @Test
        @DisplayName("Exactly 2 states were included because only two of four filtered states were in the delegate")
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
        @DisplayName("Cannot read STEM because it is not in the delegate")
        void missingState() {
            assertThatThrownBy(() -> states.get(STEAM_STATE_ID)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> states.getSingleton(STEAM_STATE_ID)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("StateKeys Tests")
    class StateKeysTest extends StateTestBase {
        private ReadableStates delegate;

        @BeforeEach
        void setUp() {
            delegate = MapReadableStates.builder()
                    .state(readableFruitState())
                    .state(readableCountryState())
                    .build();
        }

        @Test
        @DisplayName("The filtered `stateKeys` contains all states that are in the filter and in the" + " delegate")
        void filteredStateKeys() {
            // Given a delegate with multiple k/v states and a set of state keys that are
            // a subset of keys in the delegate AND contain some keys not in the delegate
            final var stateIds = Set.of(COUNTRY_STATE_ID, STEAM_STATE_ID);
            final var filtered = new FilteredReadableStates(delegate, stateIds);

            // When we look at the contents of the filtered `stateKeys`
            final var filteredStateIds = filtered.stateIds();

            // Then we find only those states that are both in the state keys passed to
            // the FilteredReadableStates, and in the delegate.
            assertThat(filteredStateIds).containsExactlyInAnyOrder(COUNTRY_STATE_ID);
        }

        @Test
        @DisplayName("A modifiable `stateIds` set provided to a constructor can be changed without"
                + " impacting the FilteredReadableStates")
        void modifiableStateIds() {
            // Given a delegate with multiple k/v states and a modifiable set of state IDs,
            final var modifiableStateIds = new HashSet<Integer>();
            modifiableStateIds.add(COUNTRY_STATE_ID);

            // When a FilteredReadableStates is created, and the Set of all state IDs for
            // the filtered set is read and the modifiable state IDs map is modified
            final var filtered = new FilteredReadableStates(delegate, modifiableStateIds);
            final var filteredStateIds = filtered.stateIds();
            modifiableStateIds.add(STEAM_STATE_ID);
            modifiableStateIds.remove(COUNTRY_STATE_ID);

            // Then these changes are NOT found in the filtered state IDs
            assertThat(filteredStateIds).containsExactlyInAnyOrder(COUNTRY_STATE_ID);
        }

        @Test
        @DisplayName("The set of filtered state keys is unmodifiable")
        void filteredStateIDsAreUnmodifiable() {
            // Given a FilteredReadableStates
            final var stateIds = Set.of(COUNTRY_STATE_ID);
            final var filtered = new FilteredReadableStates(delegate, stateIds);

            // When the filtered state IDs is read and a modification attempted,
            // then an exception is thrown
            final var filteredStateIds = filtered.stateIds();
            assertThatThrownBy(() -> filteredStateIds.add(FRUIT_STATE_ID))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
