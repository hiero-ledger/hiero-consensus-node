// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.merkle;

import static org.junit.jupiter.api.Assertions.*;

import com.hedera.pbj.runtime.Codec;
import com.swirlds.state.lifecycle.StateDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StateDefinitionTest {

    @Mock
    private Codec<String> mockCodec;

    @Test
    void stateKeyRequired() {
        assertThrows(
                NullPointerException.class,
                () -> new StateDefinition<>(1, null, mockCodec, mockCodec, true, false, false));
    }

    @Test
    void valueCodecRequired() {
        assertThrows(
                NullPointerException.class, () -> new StateDefinition<>(1, "KEY", mockCodec, null, true, false, false));
    }

    @Test
    void singletonsCannotBeKeyValue() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new StateDefinition<>(1, "KEY", mockCodec, mockCodec, true, true, false));
    }

    @Test
    void nonSingletonRequiresKeyCodec() {
        assertThrows(
                NullPointerException.class, () -> new StateDefinition<>(1, "KEY", null, mockCodec, true, false, false));
    }

    @Test
    void keyValueFactoryWorks() {
        assertDoesNotThrow(() -> StateDefinition.keyValue(1, "KEY", mockCodec, mockCodec));
    }

    @Test
    void singletonFactoryWorks() {
        assertDoesNotThrow(() -> StateDefinition.singleton(1, "KEY", mockCodec));
    }

    @Test
    void constructorWorks() {
        assertDoesNotThrow(() -> new StateDefinition<>(1, "KEY", mockCodec, mockCodec, true, false, false));
    }
}
