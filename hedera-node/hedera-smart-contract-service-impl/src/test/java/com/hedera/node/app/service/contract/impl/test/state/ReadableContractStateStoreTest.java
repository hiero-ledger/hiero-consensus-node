// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.state;

import static com.hedera.node.app.service.contract.impl.schemas.V0490ContractSchema.BYTECODE_STATE_ID;
import static com.hedera.node.app.service.contract.impl.schemas.V0490ContractSchema.STORAGE_STATE_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.BYTECODE;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.CALLED_CONTRACT_ID;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.state.contract.Bytecode;
import com.hedera.hapi.node.state.contract.SlotKey;
import com.hedera.hapi.node.state.contract.SlotValue;
import com.hedera.node.app.hapi.utils.EntityType;
import com.hedera.node.app.service.contract.impl.state.ReadableContractStateStore;
import com.hedera.node.app.spi.ids.ReadableEntityCounters;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.spi.ReadableKVState;
import com.swirlds.state.spi.ReadableStates;
import java.util.Collections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReadableContractStateStoreTest {

    private static final SlotKey SLOT_KEY =
            new SlotKey(ContractID.newBuilder().contractNum(1L).build(), Bytes.EMPTY);
    private static final SlotValue SLOT_VALUE = new SlotValue(Bytes.EMPTY, Bytes.EMPTY, Bytes.EMPTY);

    @Mock
    private ReadableKVState<SlotKey, SlotValue> storage;

    @Mock
    private ReadableKVState<ContractID, Bytecode> bytecode;

    @Mock
    private ReadableStates states;

    @Mock
    private ReadableEntityCounters readableEntityCounters;

    private ReadableContractStateStore subject;

    @BeforeEach
    void setUp() {
        given(states.<SlotKey, SlotValue>get(STORAGE_STATE_ID)).willReturn(storage);
        given(states.<ContractID, Bytecode>get(BYTECODE_STATE_ID)).willReturn(bytecode);

        subject = new ReadableContractStateStore(states, readableEntityCounters);
    }

    @Test
    void allMutationsAreUnsupported() {
        assertThrows(UnsupportedOperationException.class, () -> subject.removeSlot(SLOT_KEY));
        assertThrows(UnsupportedOperationException.class, () -> subject.putSlot(SLOT_KEY, SLOT_VALUE));
        assertThrows(UnsupportedOperationException.class, () -> subject.putBytecode(CALLED_CONTRACT_ID, BYTECODE));
    }

    @Test
    void getsBytecodeAsExpected() {
        given(bytecode.get(CALLED_CONTRACT_ID)).willReturn(BYTECODE);

        assertSame(BYTECODE, subject.getBytecode(CALLED_CONTRACT_ID));
    }

    @Test
    void getsSlotAsExpected() {
        given(storage.get(SLOT_KEY)).willReturn(SLOT_VALUE);

        assertSame(SLOT_VALUE, subject.getSlotValue(SLOT_KEY));
    }

    @Test
    void getsOriginalSlotAsExpected() {
        given(storage.get(SLOT_KEY)).willReturn(SLOT_VALUE);

        assertSame(SLOT_VALUE, subject.getOriginalSlotValue(SLOT_KEY));
    }

    @Test
    void getsModifiedSlotKeysAsExpected() {
        assertSame(Collections.emptySet(), subject.getModifiedSlotKeys());
    }

    @Test
    void getsSizeAsExpected() {
        given(readableEntityCounters.getCounterFor(EntityType.CONTRACT_STORAGE)).willReturn(1L);

        assertSame(1L, subject.getNumSlots());
    }

    @Test
    void getsNumBytecodesAsExpected() {
        given(readableEntityCounters.getCounterFor(EntityType.CONTRACT_BYTECODE))
                .willReturn(123L);

        assertSame(123L, subject.getNumBytecodes());
    }
}
