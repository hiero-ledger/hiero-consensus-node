// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.entityid.impl.test.schemas;

import static com.hedera.node.app.service.entityid.impl.schemas.V0590EntityIdSchema.ENTITY_COUNTS_STATE_ID;
import static com.hedera.node.app.service.entityid.impl.schemas.V0730EntityIdSchema.HIGHEST_NODE_ID_KEY;
import static com.hedera.node.app.service.entityid.impl.schemas.V0730EntityIdSchema.HIGHEST_NODE_ID_STATE_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.entity.EntityCounts;
import com.hedera.hapi.platform.state.NodeId;
import com.hedera.node.app.service.entityid.impl.schemas.V0730EntityIdSchema;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.lifecycle.StateDefinition;
import com.swirlds.state.spi.ReadableSingletonState;
import com.swirlds.state.spi.ReadableStates;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.spi.WritableStates;
import java.util.Comparator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class V0730EntityIdSchemaTest {

    @Mock
    private MigrationContext ctx;

    @Mock
    private ReadableStates previousStates;

    @Mock
    private WritableStates newStates;

    @Mock
    private ReadableSingletonState entityCountsState;

    @Mock
    private WritableSingletonState highestNodeIdState;

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

    @Test
    @DisplayName("Migrate should be a no-op on genesis")
    void testMigrateOnGenesis() {
        given(ctx.isGenesis()).willReturn(true);

        subject.migrate(ctx);

        verify(ctx, never()).newStates();
        verify(ctx, never()).previousStates();
    }

    @Test
    @DisplayName("Migrate should read entity counts and set highest node ID on non-genesis")
    void testMigrateOnNonGenesis() {
        final long numNodes = 5L;
        final var entityCounts = EntityCounts.newBuilder().numNodes(numNodes).build();

        given(ctx.isGenesis()).willReturn(false);
        given(ctx.newStates()).willReturn(newStates);
        given(newStates.getSingleton(HIGHEST_NODE_ID_STATE_ID)).willReturn(highestNodeIdState);
        given(ctx.previousStates()).willReturn(previousStates);
        given(previousStates.getSingleton(ENTITY_COUNTS_STATE_ID)).willReturn(entityCountsState);
        given(entityCountsState.get()).willReturn(entityCounts);

        subject.migrate(ctx);

        verify(highestNodeIdState).put(NodeId.newBuilder().id(numNodes - 1).build());
    }
}
