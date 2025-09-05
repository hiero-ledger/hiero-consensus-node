// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;

import com.swirlds.state.test.fixtures.StateTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EmptyReadableStatesTest extends StateTestBase {

    private final EmptyReadableStates states = new EmptyReadableStates();

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
    @DisplayName("Contains is always false")
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
        assertThatThrownBy(() -> states.getSingleton(UNKNOWN_STATE_ID)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Throws IAE for an unknown queue state ID")
    void unknownQueueStateId() {
        assertThatThrownBy(() -> states.getQueue(UNKNOWN_STATE_ID)).isInstanceOf(IllegalArgumentException.class);
    }
}
