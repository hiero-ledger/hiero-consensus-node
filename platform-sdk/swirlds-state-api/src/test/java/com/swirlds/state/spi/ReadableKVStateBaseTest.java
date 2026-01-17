// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.spi;

// SPDX-License-Identifier: Apache-2.0
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.swirlds.state.test.fixtures.MapReadableKVState;
import com.swirlds.state.test.fixtures.StateTestBase;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for the base class for all states. Most of the methods in this class are final, and can be
 * tested in isolation of specific subclasses. Those that cannot be (such as {@link
 * ReadableKVStateBase#reset()}) will be covered by other tests in addition to this one.
 */
public class ReadableKVStateBaseTest extends StateTestBase {

    private ReadableKVStateBase<ProtoBytes, ProtoBytes> state;
    protected Map<ProtoBytes, ProtoBytes> backingMap;

    @BeforeEach
    void setUp() {
        this.backingMap = createBackingMap();
        this.state = createFruitState(this.backingMap);
    }

    protected Map<ProtoBytes, ProtoBytes> createBackingMap() {
        final var map = new HashMap<ProtoBytes, ProtoBytes>();
        map.put(A_KEY, APPLE);
        map.put(B_KEY, BANANA);
        map.put(C_KEY, CHERRY);
        map.put(D_KEY, DATE);
        map.put(E_KEY, EGGPLANT);
        map.put(F_KEY, FIG);
        map.put(G_KEY, GRAPE);
        return map;
    }

    protected ReadableKVStateBase<ProtoBytes, ProtoBytes> createFruitState(Map<ProtoBytes, ProtoBytes> backingMap) {
        return new MapReadableKVState<>(FRUIT_STATE_ID, FRUIT_STATE_LABEL, backingMap);
    }

    /** Make sure the constructor is holding onto the state ID properly */
    @Test
    @DisplayName("The state ID must match what was provided in the constructor")
    void testStateId() {
        assertThat(state.getStateId()).isEqualTo(FRUIT_STATE_ID);
    }

    /**
     * When we are asked to get an unknown item (something not in the backing store), the {@link
     * ReadableKVStateBase} still needs to remember this key and include it in the set of read keys,
     * because the fact that the key was missing might be an important piece of information when
     * working in pre-handle. So we keep track in readKeys of ALL keys read, not just those that had
     * values.
     */
    @Test
    @DisplayName("`get` of an unknown item returns an empty optional")
    void testNonExistingGet() {
        assertThat(state.readKeys()).isEmpty();
        assertThat(state.get(UNKNOWN_KEY)).isNull();
        assertThat(state.readKeys()).contains(UNKNOWN_KEY);
    }

    @Test
    @DisplayName("The set of readKeys must be unmodifiable")
    void testReadKeysIsUnmodifiable() {
        state.get(A_KEY);
        final var readKeys = state.readKeys();
        assertThatThrownBy(() -> readKeys.add(B_KEY)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> readKeys.remove(A_KEY)).isInstanceOf(UnsupportedOperationException.class);
    }

    /**
     * When asked to get a known item, not only is it returned, but we also record this in the
     * "readKeys".
     */
    @Test
    @DisplayName("`get` of a known item returns that item")
    void testExistingGet() {
        assertThat(state.readKeys()).isEmpty();
        assertThat(state.get(A_KEY)).isNotNull();
        assertThat(state.get(A_KEY)).isEqualTo(APPLE);
        assertThat(state.readKeys()).contains(A_KEY);
    }

    /** Similar to get, but for "contains". We must record this in "readKeys". */
    @Test
    @DisplayName("`contains` of an unknown item returns false")
    void testNonExistingContains() {
        assertThat(state.readKeys()).isEmpty();
        assertThat(state.contains(UNKNOWN_KEY)).isFalse();
        assertThat(state.readKeys()).contains(UNKNOWN_KEY);
    }

    /** Similar to get, but for contains. */
    @Test
    @DisplayName("`contains` of a known item returns true")
    void testExistingContains() {
        assertThat(state.readKeys()).isEmpty();
        assertThat(state.contains(B_KEY)).isTrue();
        assertThat(state.readKeys()).contains(B_KEY);
    }

    /**
     * Read some states, which will populate the "readKeys". Then clear the state, which must clear
     * the "readKeys".
     */
    @Test
    @DisplayName("`reset` clears the readKeys cache")
    void testReset() {
        // Populate "readKeys" by reading values
        assertThat(state.get(A_KEY)).isNotNull();
        assertThat(state.contains(B_KEY)).isTrue();
        assertThat(state.readKeys()).hasSize(2);

        // Reset and verify the cache is empty
        state.reset();
        assertThat(state.readKeys()).isEmpty();

        // Repopulate the cache by reading again, proving it is still working after reset
        assertThat(state.get(A_KEY)).isNotNull();
        assertThat(state.contains(B_KEY)).isTrue();
        assertThat(state.readKeys()).hasSize(2);
    }

    @Test
    @DisplayName("Can iterate over all fruit")
    void testIteration() {
        assertThat(state.keys())
                .toIterable()
                .containsExactlyInAnyOrder(backingMap.keySet().toArray(new ProtoBytes[0]));
    }
}
