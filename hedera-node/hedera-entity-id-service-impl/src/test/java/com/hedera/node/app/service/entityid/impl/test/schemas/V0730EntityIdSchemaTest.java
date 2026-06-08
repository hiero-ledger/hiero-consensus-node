// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.entityid.impl.test.schemas;

import static com.hedera.node.app.service.entityid.impl.schemas.V0730EntityIdSchema.HIGHEST_NODE_ID_KEY;
import static com.hedera.node.app.service.entityid.impl.schemas.V0730EntityIdSchema.HIGHEST_NODE_ID_STATE_ID;
import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.platform.state.NodeId;
import com.hedera.node.app.service.entityid.impl.schemas.V0730EntityIdSchema;
import com.swirlds.state.lifecycle.StateDefinition;
import java.util.Comparator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class V0730EntityIdSchemaTest {
    private V0730EntityIdSchema subject;

    @BeforeEach
    void setUp() {
        subject = new V0730EntityIdSchema();
    }

    @Test
    @DisplayName("Schema version should be 0.73.0")
    void testSchemaVersion() {
        final var expectedVersion =
                SemanticVersion.newBuilder().major(0).minor(73).patch(0).build();
        assertThat(subject.getVersion()).isEqualTo(expectedVersion);
    }

    @Test
    @DisplayName("States to create should include NODE_ID singleton")
    void testStatesToCreate() {
        final var statesToCreate = subject.statesToCreate();

        assertThat(statesToCreate).hasSize(1);

        final var sortedResult = statesToCreate.stream()
                .sorted(Comparator.comparing(StateDefinition::stateKey))
                .toList();

        final var nodeIdDef = sortedResult.getFirst();
        assertThat(nodeIdDef.stateKey()).isEqualTo(HIGHEST_NODE_ID_KEY);
        assertThat(nodeIdDef.valueCodec()).isEqualTo(NodeId.PROTOBUF);
        assertThat(nodeIdDef.singleton()).isTrue();
    }

    @Test
    @DisplayName("NODE_ID_STATE_ID should match SingletonType ordinal")
    void testNodeIdStateId() {
        assertThat(HIGHEST_NODE_ID_STATE_ID).isPositive();
    }
}
