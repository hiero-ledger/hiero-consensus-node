// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.state.merkle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;

import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.swirlds.state.lifecycle.StateDefinition;
import com.swirlds.state.lifecycle.StateMetadata;
import com.swirlds.state.test.fixtures.merkle.MerkleTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class StateMetadataTest extends MerkleTestBase {

    private StateDefinition<ProtoBytes, ProtoBytes> def;

    @BeforeEach
    void setUp() {
        setupFruitVirtualMap();
        def = fruitMetadata.stateDefinition();
    }

    @Test
    @DisplayName("Null service name throws in the constructor")
    void nullServiceNameThrows() {
        //noinspection DataFlowIssue
        assertThatThrownBy(() -> new StateMetadata<>(null, def));
    }

    @Test
    @DisplayName("Null state definition throws in the constructor")
    void nullStateDefinitionThrows() {
        //noinspection DataFlowIssue
        assertThatThrownBy(() -> new StateMetadata<>(FIRST_SERVICE, null));
    }

    @ParameterizedTest
    @MethodSource("illegalServiceNames")
    @DisplayName("Service names with illegal characters throw an exception")
    void invalidStateKey(final String serviceName) {
        assertThatThrownBy(() -> new StateMetadata<>(serviceName, def)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @MethodSource("legalServiceNames")
    @DisplayName("Service names with legal characters are valid")
    void validStateKey(final String serviceName) {
        final var md = new StateMetadata<>(serviceName, def);
        assertThat(md.serviceName()).isEqualTo(serviceName);
    }
}
