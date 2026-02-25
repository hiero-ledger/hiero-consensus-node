// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.entityid.impl.test;

import static com.hedera.node.app.service.entityid.impl.schemas.V0590EntityIdSchema.ENTITY_COUNTS_STATE_ID;
import static com.hedera.node.app.service.entityid.impl.schemas.V0720EntityIdSchema.NODE_ID_STATE_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.entity.EntityCounts;
import com.hedera.hapi.platform.state.NodeId;
import com.hedera.node.app.service.entityid.impl.schemas.V0720EntityIdSchema;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.spi.ReadableSingletonState;
import com.swirlds.state.spi.ReadableStates;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.spi.WritableStates;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class V0720EntityIdSchemaTest {

    @Mock
    private MigrationContext migrationContext;

    @Mock
    private WritableStates newStates;

    @Mock
    private ReadableStates previousStates;

    private V0720EntityIdSchema subject;

    @BeforeEach
    void setUp() {
        subject = new V0720EntityIdSchema();
    }

    @Test
    void hasCorrectVersion() {
        final var expected =
                SemanticVersion.newBuilder().major(0).minor(72).patch(0).build();
        assertEquals(expected, subject.getVersion());
    }

    @Test
    void registersNodeIdSingleton() {
        final var statesToCreate = subject.statesToCreate();
        assertThat(statesToCreate).hasSize(1);
    }

    @Test
    void migrateOnGenesisDoesNothing() {
        given(migrationContext.isGenesis()).willReturn(true);

        subject.migrate(migrationContext);

        verifyNoInteractions(newStates, previousStates);
    }

    @Test
    @SuppressWarnings("unchecked")
    void migrateOnNonGenesisInitializesFromEntityCounts() {
        final var entityCounts = EntityCounts.newBuilder().numNodes(5).build();
        final WritableSingletonState<NodeId> nodeIdState = mock(WritableSingletonState.class);
        final ReadableSingletonState<EntityCounts> entityCountsState = mock(ReadableSingletonState.class);

        given(migrationContext.isGenesis()).willReturn(false);
        given(migrationContext.newStates()).willReturn(newStates);
        given(migrationContext.previousStates()).willReturn(previousStates);
        doReturn(nodeIdState).when(newStates).getSingleton(NODE_ID_STATE_ID);
        doReturn(entityCountsState).when(previousStates).getSingleton(ENTITY_COUNTS_STATE_ID);
        given(entityCountsState.get()).willReturn(entityCounts);

        subject.migrate(migrationContext);

        // Should set highest node ID to numNodes - 1 = 4
        verify(nodeIdState).put(NodeId.newBuilder().id(4L).build());
    }
}
