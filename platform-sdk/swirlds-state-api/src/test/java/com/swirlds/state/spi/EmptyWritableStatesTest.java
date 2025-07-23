// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;

import com.swirlds.state.test.fixtures.StateTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EmptyWritableStatesTest extends StateTestBase {
    private final EmptyWritableStates states = new EmptyWritableStates();

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
        assertThat(states.contains(FRUIT_STATE_KEY)).isFalse();
    }

    @Test
    @DisplayName("Throws NPE if the key is null")
    void nullKey() {
        //noinspection DataFlowIssue
        assertThatThrownBy(() -> states.get(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Throws IAE for any non-null key")
    void nonNullKey() {
        assertThatThrownBy(() -> states.get(UNKNOWN_STATE_KEY)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Throws IAE for any non-null singleton key")
    void nonNullSingletonKey() {
        assertThatThrownBy(() -> states.getSingleton(UNKNOWN_STATE_KEY)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Throws IAE for any non-null queue key")
    void nonNullQueueKey() {
        assertThatThrownBy(() -> states.getQueue(UNKNOWN_STATE_KEY)).isInstanceOf(IllegalArgumentException.class);
    }
}
