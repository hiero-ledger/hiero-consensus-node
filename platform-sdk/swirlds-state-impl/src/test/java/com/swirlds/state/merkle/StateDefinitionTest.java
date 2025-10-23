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
                () -> new StateDefinition<>(1, null, mockCodec, mockCodec, 123, true, false, false));
    }

    @Test
    void valueCodecRequired() {
        assertThrows(
                NullPointerException.class,
                () -> new StateDefinition<>(1, "KEY", mockCodec, null, 123, true, false, false));
    }

    @Test
    void singletonsCannotBeOnDisk() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new StateDefinition<>(1, "KEY", mockCodec, mockCodec, 123, true, true, false));
    }

    @Test
    void onDiskMustHintPositiveNumKeys() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new StateDefinition<>(1, "KEY", mockCodec, mockCodec, 0, true, false, false));
    }

    @Test
    void nonSingletonRequiresKeyCodec() {
        assertThrows(
                NullPointerException.class,
                () -> new StateDefinition<>(1, "KEY", null, mockCodec, 1, true, false, false));
    }

    @Test
    void inMemoryFactoryWorks() {
        assertDoesNotThrow(() -> StateDefinition.inMemory(1, "KEY", mockCodec, mockCodec));
    }

    @Test
    void onDiskFactoryWorks() {
        assertDoesNotThrow(() -> StateDefinition.onDisk(1, "KEY", mockCodec, mockCodec, 123));
    }

    @Test
    void singletonFactoryWorks() {
        assertDoesNotThrow(() -> StateDefinition.singleton(1, "KEY", mockCodec));
    }

    @Test
    void constructorWorks() {
        assertDoesNotThrow(() -> new StateDefinition<>(1, "KEY", mockCodec, mockCodec, 123, true, false, false));
    }
}
