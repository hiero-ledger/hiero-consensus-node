// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.ids;

import static com.hedera.node.app.ids.schemas.V0490EntityIdSchema.ENTITY_ID_STATE_KEY;
import static com.hedera.node.app.ids.schemas.V0590EntityIdSchema.ENTITY_COUNTS_KEY;
import static org.junit.jupiter.api.Assertions.*;

import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.hapi.node.state.entity.EntityCounts;
import com.hedera.node.app.hapi.utils.EntityType;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.test.fixtures.FunctionWritableSingletonState;
import com.swirlds.state.test.fixtures.MapWritableStates;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WritableEntityIdStoreTest {
    private final AtomicReference<EntityNumber> nextEntityNumber = new AtomicReference<>();
    private final AtomicReference<EntityCounts> entityCounts = new AtomicReference<>();
    private final WritableSingletonState<EntityNumber> entityIdState = new FunctionWritableSingletonState<>(
            EntityIdService.NAME, ENTITY_ID_STATE_KEY, nextEntityNumber::get, nextEntityNumber::set);
    private final WritableSingletonState<EntityCounts> entityCountsState = new FunctionWritableSingletonState<>(
            EntityIdService.NAME, ENTITY_COUNTS_KEY, entityCounts::get, entityCounts::set);
    private WritableEntityIdStore subject;

    @BeforeEach
    void setup() {
        nextEntityNumber.set(EntityNumber.DEFAULT);
        entityCounts.set(EntityCounts.newBuilder().numAccounts(10L).build());
        final var writableStates =
                new MapWritableStates(Map.of(ENTITY_ID_STATE_KEY, entityIdState, ENTITY_COUNTS_KEY, entityCountsState));
        subject = new WritableEntityIdStore(writableStates);
    }

    @Test
    void peeksAndIncrementsAsExpected() {
        assertEquals(1, subject.peekAtNextNumber());
        subject.incrementAndGet();
        assertEquals(2, subject.peekAtNextNumber());
    }

    @Test
    void incrementsAsExpected() {
        subject.incrementEntityTypeCount(EntityType.TOKEN);
        assertEquals(1, entityCountsState.get().numTokens());
        subject.incrementEntityTypeCount(EntityType.TOKEN_ASSOCIATION);
        assertEquals(1, entityCountsState.get().numTokenRelations());
        subject.incrementEntityTypeCount(EntityType.NFT);
        assertEquals(1, entityCountsState.get().numNfts());
        subject.incrementEntityTypeCount(EntityType.ALIAS);
        assertEquals(1, entityCountsState.get().numAliases());
        subject.incrementEntityTypeCount(EntityType.NODE);
        assertEquals(1, entityCountsState.get().numNodes());
        subject.incrementEntityTypeCount(EntityType.SCHEDULE);
        assertEquals(1, entityCountsState.get().numSchedules());
        subject.incrementEntityTypeCount(EntityType.CONTRACT_BYTECODE);
        assertEquals(1, entityCountsState.get().numContractBytecodes());
        subject.incrementEntityTypeCount(EntityType.CONTRACT_STORAGE);
        assertEquals(1, entityCountsState.get().numContractStorageSlots());
        subject.incrementEntityTypeCount(EntityType.TOPIC);
        assertEquals(1, entityCountsState.get().numTopics());
        subject.incrementEntityTypeCount(EntityType.FILE);
        assertEquals(1, entityCountsState.get().numFiles());
        subject.incrementEntityTypeCount(EntityType.AIRDROP);
        assertEquals(1, entityCountsState.get().numAirdrops());
        subject.incrementEntityTypeCount(EntityType.STAKING_INFO);
        assertEquals(1, entityCountsState.get().numStakingInfos());
    }

    @Test
    void decrementsAsExpected() {
        subject.incrementEntityTypeCount(EntityType.ALIAS);
        subject.incrementEntityTypeCount(EntityType.TOKEN_ASSOCIATION);
        subject.incrementEntityTypeCount(EntityType.NFT);
        subject.incrementEntityTypeCount(EntityType.AIRDROP);
        subject.incrementEntityTypeCount(EntityType.SCHEDULE);
        subject.incrementEntityTypeCount(EntityType.CONTRACT_STORAGE);

        assertEquals(1, entityCountsState.get().numAliases());
        subject.decrementEntityTypeCounter(EntityType.ALIAS);
        assertEquals(0, entityCountsState.get().numAliases());

        assertEquals(1, entityCountsState.get().numTokenRelations());
        subject.decrementEntityTypeCounter(EntityType.TOKEN_ASSOCIATION);
        assertEquals(0, entityCountsState.get().numTokenRelations());

        assertEquals(1, entityCountsState.get().numNfts());
        subject.decrementEntityTypeCounter(EntityType.NFT);
        assertEquals(0, entityCountsState.get().numNfts());

        assertEquals(1, entityCountsState.get().numSchedules());
        subject.decrementEntityTypeCounter(EntityType.SCHEDULE);
        assertEquals(0, entityCountsState.get().numSchedules());

        assertEquals(1, entityCountsState.get().numContractStorageSlots());
        subject.decrementEntityTypeCounter(EntityType.CONTRACT_STORAGE);
        assertEquals(0, entityCountsState.get().numContractStorageSlots());
    }
}
