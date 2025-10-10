// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.state;

import static com.hedera.node.app.service.contract.impl.schemas.V0490ContractSchema.BYTECODE_STATE_ID;
import static com.hedera.node.app.service.contract.impl.schemas.V0490ContractSchema.STORAGE_STATE_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.BYTECODE;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.CALLED_CONTRACT_ID;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.state.contract.Bytecode;
import com.hedera.hapi.node.state.contract.SlotKey;
import com.hedera.hapi.node.state.contract.SlotValue;
import com.hedera.node.app.hapi.utils.EntityType;
import com.hedera.node.app.service.contract.impl.state.WritableContractStateStore;
import com.hedera.node.app.service.entityid.WritableEntityCounters;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.spi.WritableKVState;
import com.swirlds.state.spi.WritableStates;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WritableContractStateStoreTest {

    private static final ContractID CONTRACT_ID =
            ContractID.newBuilder().contractNum(1L).build();
    private static final SlotKey SLOT_KEY = new SlotKey(CONTRACT_ID, Bytes.EMPTY);
    private static final SlotValue SLOT_VALUE = new SlotValue(Bytes.EMPTY, Bytes.EMPTY, Bytes.EMPTY);

    @Mock
    private WritableKVState<SlotKey, SlotValue> storage;

    @Mock
    private WritableKVState<ContractID, Bytecode> bytecode;

    @Mock
    private WritableStates states;

    @Mock
    private WritableEntityCounters entityCounters;

    private WritableContractStateStore subject;

    @BeforeEach
    void setUp() {
        given(states.<SlotKey, SlotValue>get(STORAGE_STATE_ID)).willReturn(storage);
        given(states.<ContractID, Bytecode>get(BYTECODE_STATE_ID)).willReturn(bytecode);

        final var config = HederaTestConfigBuilder.createConfig();

        subject = new WritableContractStateStore(states, entityCounters);
    }

    @Test
    void getsBytecodeAsExpected() {
        given(bytecode.get(CALLED_CONTRACT_ID)).willReturn(BYTECODE);

        assertSame(BYTECODE, subject.getBytecode(CALLED_CONTRACT_ID));
    }

    @Test
    void putsBytecodeAsExpected() {
        subject.putBytecode(CALLED_CONTRACT_ID, BYTECODE);

        verify(bytecode).put(CALLED_CONTRACT_ID, BYTECODE);
    }

    @Test
    void removesSlotAsExpected() {
        subject.removeSlot(SLOT_KEY);

        verify(storage).remove(SLOT_KEY);
    }

    @Test
    void getsSlotAsExpected() {
        given(storage.get(SLOT_KEY)).willReturn(SLOT_VALUE);

        assertSame(SLOT_VALUE, subject.getSlotValue(SLOT_KEY));
    }

    @Test
    void getsOriginalSlotAsExpected() {
        given(storage.getOriginalValue(SLOT_KEY)).willReturn(SLOT_VALUE);

        assertSame(SLOT_VALUE, subject.getOriginalSlotValue(SLOT_KEY));
    }

    @Test
    void putsSlotAsExpected() {
        subject.putSlot(SLOT_KEY, SLOT_VALUE);

        verify(storage).put(SLOT_KEY, SLOT_VALUE);
    }

    @Test
    void getsModifiedSlotKeysAsExpected() {
        final var modified = Set.of(SLOT_KEY);

        given(storage.modifiedKeys()).willReturn(modified);

        assertSame(modified, subject.getModifiedSlotKeys());
    }

    @Test
    void getsSizeAsExpected() {
        given(entityCounters.getCounterFor(EntityType.CONTRACT_STORAGE)).willReturn(1L);

        assertSame(1L, subject.getNumSlots());
    }

    @Test
    void getsNumBytecodesAsExpected() {
        given(entityCounters.getCounterFor(EntityType.CONTRACT_BYTECODE)).willReturn(123L);

        assertSame(123L, subject.getNumBytecodes());
    }
}
