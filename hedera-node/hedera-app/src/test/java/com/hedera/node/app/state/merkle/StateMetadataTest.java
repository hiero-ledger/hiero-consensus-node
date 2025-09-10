// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.state.merkle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;

import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.swirlds.platform.test.fixtures.state.MerkleTestBase;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.StateDefinition;
import com.swirlds.state.lifecycle.StateMetadata;
import com.swirlds.state.test.fixtures.merkle.TestSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class StateMetadataTest extends MerkleTestBase {

    private Schema schema;
    private StateDefinition<ProtoBytes, ProtoBytes> def;

    @BeforeEach
    void setUp() {
        setupSpaceMerkleMap();
        schema = new TestSchema(1);
        def = spaceMetadata.stateDefinition();
    }

    @Test
    @DisplayName("Null service name throws in the constructor")
    void nullServiceNameThrows() {
        //noinspection DataFlowIssue
        assertThatThrownBy(() -> new StateMetadata<>(null, schema, def));
    }

    @Test
    @DisplayName("Null schema throws in the constructor")
    void nullSchemaThrows() {
        //noinspection DataFlowIssue
        assertThatThrownBy(() -> new StateMetadata<>(FIRST_SERVICE, null, def));
    }

    @Test
    @DisplayName("Null state definition throws in the constructor")
    void nullStateDefinitionThrows() {
        //noinspection DataFlowIssue
        assertThatThrownBy(() -> new StateMetadata<>(FIRST_SERVICE, schema, null));
    }

    @ParameterizedTest
    @MethodSource("illegalServiceNames")
    @DisplayName("Service names with illegal characters throw an exception")
    void invalidStateKey(final String serviceName) {
        assertThatThrownBy(() -> new StateMetadata<>(serviceName, schema, def))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @MethodSource("legalServiceNames")
    @DisplayName("Service names with legal characters are valid")
    void validStateKey(final String serviceName) {
        final var md = new StateMetadata<>(serviceName, schema, def);
        assertThat(md.serviceName()).isEqualTo(serviceName);
    }

    // verify the different generated classIDs are right

    @Test
    @DisplayName("inMemoryValueClassId is as expected")
    void inMemoryValueClassId() {
        final var md = new StateMetadata<>(FIRST_SERVICE, schema, def);
        final var expected = computeClassId(md, "InMemoryValue");

        assertThat(expected).isEqualTo(md.inMemoryValueClassId());
    }

    @Test
    @DisplayName("onDiskKeyClassId is as expected")
    void onDiskKeyClassId() {
        final var md = new StateMetadata<>(FIRST_SERVICE, schema, def);
        final var expected = computeClassId(md, "OnDiskKey");
        assertThat(expected).isEqualTo(md.onDiskKeyClassId());
    }

    @Test
    @DisplayName("onDiskValueClassId is as expected")
    void onDiskValueClassId() {
        final var md = new StateMetadata<>(FIRST_SERVICE, schema, def);
        final var expected = computeClassId(md, "OnDiskValue");
        assertThat(expected).isEqualTo(md.onDiskValueClassId());
    }

    @Test
    @DisplayName("onDiskKeySerializerClassId is as expected")
    void onDiskKeySerializerClassId() {
        final var md = new StateMetadata<>(FIRST_SERVICE, schema, def);
        final var expected = computeClassId(md, "OnDiskKeySerializer");
        assertThat(expected).isEqualTo(md.onDiskKeySerializerClassId());
    }

    @Test
    @DisplayName("onDiskValueSerializerClassId is as expected")
    void onDiskValueSerializerClassId() {
        final var md = new StateMetadata<>(FIRST_SERVICE, schema, def);
        final var expected = computeClassId(md, "OnDiskValueSerializer");
        assertThat(expected).isEqualTo(md.onDiskValueSerializerClassId());
    }

    private long computeClassId(StateMetadata<ProtoBytes, ProtoBytes> md, String suffix) {
        return StateMetadata.computeClassId(
                md.serviceName(), md.stateDefinition().stateKey(), md.schema().getVersion(), suffix);
    }
}
