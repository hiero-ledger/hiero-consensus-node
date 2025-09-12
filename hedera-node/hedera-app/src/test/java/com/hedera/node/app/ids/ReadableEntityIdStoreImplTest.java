// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.ids;

import static com.hedera.node.app.ids.schemas.V0490EntityIdSchema.ENTITY_ID_STATE_ID;
import static com.hedera.node.app.ids.schemas.V0590EntityIdSchema.ENTITY_COUNTS_STATE_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.hapi.node.state.entity.EntityCounts;
import com.swirlds.state.spi.ReadableSingletonState;
import com.swirlds.state.spi.ReadableStates;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReadableEntityIdStoreImplTest {

    @Mock
    private ReadableStates mockStates;

    @Mock
    private ReadableSingletonState<EntityNumber> mockEntityIdState;

    @Mock
    private ReadableSingletonState<EntityCounts> mockEntityCountsState;

    private ReadableEntityIdStoreImpl subject;

    @BeforeEach
    void setUp() {
        given(mockStates.<EntityNumber>getSingleton(ENTITY_ID_STATE_ID)).willReturn(mockEntityIdState);
        given(mockStates.<EntityCounts>getSingleton(ENTITY_COUNTS_STATE_ID)).willReturn(mockEntityCountsState);

        subject = new ReadableEntityIdStoreImpl(mockStates);
    }

    @Test
    void testPeekAtNextNumber_withNullEntityId() {
        when(mockEntityIdState.get()).thenReturn(null);
        assertEquals(1, subject.peekAtNextNumber());
    }

    @Test
    void testPeekAtNextNumber_withValidEntityId() {
        EntityNumber entityNumber = new EntityNumber(100);
        when(mockEntityIdState.get()).thenReturn(entityNumber);
        assertEquals(101, subject.peekAtNextNumber());
    }

    @Test
    void testNumAccounts() {
        final var entityCounts = EntityCounts.newBuilder().numAccounts(50L).build();
        when(mockEntityCountsState.get()).thenReturn(entityCounts);

        assertEquals(50L, subject.numAccounts());
    }

    @Test
    void testNumTokens() {
        final var entityCounts = EntityCounts.newBuilder().numTokens(20L).build();
        when(mockEntityCountsState.get()).thenReturn(entityCounts);

        assertEquals(20L, subject.numTokens());
    }

    @Test
    void testNumFiles() {
        final var entityCounts = EntityCounts.newBuilder().numFiles(10L).build();
        when(mockEntityCountsState.get()).thenReturn(entityCounts);

        assertEquals(10L, subject.numFiles());
    }

    @Test
    void testNumTopics() {
        final var entityCounts = EntityCounts.newBuilder().numTopics(5L).build();
        when(mockEntityCountsState.get()).thenReturn(entityCounts);

        assertEquals(5L, subject.numTopics());
    }

    @Test
    void testNumContractBytecodes() {
        final var entityCounts =
                EntityCounts.newBuilder().numContractBytecodes(15L).build();
        when(mockEntityCountsState.get()).thenReturn(entityCounts);

        assertEquals(15L, subject.numContractBytecodes());
    }

    @Test
    void testNumContractStorageSlots() {
        final var entityCounts =
                EntityCounts.newBuilder().numContractStorageSlots(30L).build();
        when(mockEntityCountsState.get()).thenReturn(entityCounts);

        assertEquals(30L, subject.numContractStorageSlots());
    }

    @Test
    void testNumNfts() {
        final var entityCounts = EntityCounts.newBuilder().numNfts(25L).build();
        when(mockEntityCountsState.get()).thenReturn(entityCounts);

        assertEquals(25L, subject.numNfts());
    }

    @Test
    void testNumTokenRelations() {
        final var entityCounts =
                EntityCounts.newBuilder().numTokenRelations(40L).build();
        when(mockEntityCountsState.get()).thenReturn(entityCounts);

        assertEquals(40L, subject.numTokenRelations());
    }

    @Test
    void testNumAliases() {
        final var entityCounts = EntityCounts.newBuilder().numAliases(18L).build();
        when(mockEntityCountsState.get()).thenReturn(entityCounts);

        assertEquals(18L, subject.numAliases());
    }

    @Test
    void testNumSchedules() {
        final var entityCounts = EntityCounts.newBuilder().numSchedules(22L).build();
        when(mockEntityCountsState.get()).thenReturn(entityCounts);

        assertEquals(22L, subject.numSchedules());
    }

    @Test
    void testNumAirdrops() {
        final var entityCounts = EntityCounts.newBuilder().numAirdrops(8L).build();
        when(mockEntityCountsState.get()).thenReturn(entityCounts);

        assertEquals(8L, subject.numAirdrops());
    }

    @Test
    void testNumNodes() {
        final var entityCounts = EntityCounts.newBuilder().numNodes(12L).build();
        when(mockEntityCountsState.get()).thenReturn(entityCounts);

        assertEquals(12L, subject.numNodes());
    }
}
