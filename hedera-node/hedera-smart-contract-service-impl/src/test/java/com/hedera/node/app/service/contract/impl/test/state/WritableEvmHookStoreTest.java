// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.state;

import static com.hedera.node.app.service.contract.impl.schemas.V065ContractSchema.EVM_HOOK_STATES_STATE_ID;
import static com.hedera.node.app.service.contract.impl.schemas.V065ContractSchema.EVM_HOOK_STORAGE_STATE_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HookEntityId;
import com.hedera.hapi.node.base.HookId;
import com.hedera.hapi.node.hooks.EvmHookStorageSlot;
import com.hedera.hapi.node.hooks.EvmHookStorageUpdate;
import com.hedera.hapi.node.state.contract.SlotValue;
import com.hedera.hapi.node.state.hooks.EvmHookSlotKey;
import com.hedera.hapi.node.state.hooks.EvmHookState;
import com.hedera.node.app.service.contract.impl.state.WritableEvmHookStore;
import com.hedera.node.app.service.entityid.WritableEntityCounters;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.spi.WritableKVState;
import com.swirlds.state.spi.WritableStates;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WritableEvmHookStoreTest {
    private static final HookId HOOK_ID = HookId.newBuilder()
            .entityId(HookEntityId.newBuilder()
                    .accountId(AccountID.newBuilder().accountNum(1234L).build()))
            .hookId(1L)
            .build();

    private static final Bytes A = Bytes.wrap("a");
    private static final Bytes B = Bytes.wrap("b");
    private static final Bytes C = Bytes.wrap("c");
    private static final Bytes X = Bytes.wrap("x");
    private static final Bytes VALUE_1 = Bytes.wrap("1");
    private static final Bytes VALUE_2 = Bytes.wrap("2");
    private static final Bytes VALUE_3 = Bytes.wrap("3");
    private static final Bytes VALUE_4 = Bytes.wrap("4");
    private static final Bytes VALUE_9 = Bytes.wrap("9");

    @Mock
    private WritableStates states;

    @Mock
    private WritableKVState<HookId, EvmHookState> hookStates;

    @Mock
    private WritableKVState<EvmHookSlotKey, SlotValue> storage;

    @Mock
    private WritableEntityCounters entityCounters;

    private final Map<HookId, EvmHookState> originalHookStates = new LinkedHashMap<>();
    private final Map<HookId, EvmHookState> currentHookStates = new LinkedHashMap<>();
    private final Map<EvmHookSlotKey, SlotValue> originalStorage = new LinkedHashMap<>();
    private final Map<EvmHookSlotKey, SlotValue> currentStorage = new LinkedHashMap<>();

    private WritableEvmHookStore subject;

    @BeforeEach
    void setUp() {
        given(states.<HookId, EvmHookState>get(EVM_HOOK_STATES_STATE_ID)).willReturn(hookStates);
        given(states.<EvmHookSlotKey, SlotValue>get(EVM_HOOK_STORAGE_STATE_ID)).willReturn(storage);

        given(hookStates.get(any())).willAnswer(invocation -> currentHookStates.get(invocation.getArgument(0)));
        doAnswer(invocation -> {
                    currentHookStates.put(invocation.getArgument(0), invocation.getArgument(1));
                    return null;
                })
                .when(hookStates)
                .put(any(), any());

        given(storage.get(any())).willAnswer(invocation -> currentStorage.get(invocation.getArgument(0)));
        given(storage.getOriginalValue(any())).willAnswer(invocation -> originalStorage.get(invocation.getArgument(0)));
        doAnswer(invocation -> {
                    currentStorage.put(invocation.getArgument(0), invocation.getArgument(1));
                    return null;
                })
                .when(storage)
                .put(any(), any());
        lenient()
                .doAnswer(invocation -> {
                    currentStorage.remove(invocation.getArgument(0));
                    return null;
                })
                .when(storage)
                .remove(any());

        subject = new WritableEvmHookStore(states, entityCounters);
    }

    @Test
    void updateAfterRemovingPredecessorKeepsLivePointers() {
        givenExistingHookWithSlots(
                A,
                Map.of(
                        A, slot(VALUE_1, Bytes.EMPTY, B),
                        B, slot(VALUE_2, A, C),
                        C, slot(VALUE_3, B, Bytes.EMPTY)));

        final int delta = subject.updateStorage(HOOK_ID, List.of(slotUpdate(A, Bytes.EMPTY), slotUpdate(B, VALUE_9)));

        assertEquals(-1, delta);
        assertNull(currentStorage.get(slotKey(A)));
        assertEquals(slot(VALUE_9, Bytes.EMPTY, C), currentStorage.get(slotKey(B)));
        assertEquals(slot(VALUE_3, B, Bytes.EMPTY), currentStorage.get(slotKey(C)));
        assertEquals(B, currentHookStates.get(HOOK_ID).firstContractStorageKey());
        assertEquals(2L, currentHookStates.get(HOOK_ID).numStorageSlots());
    }

    @Test
    void updateAfterInsertingNewHeadKeepsLivePointers() {
        givenExistingHookWithSlots(
                A,
                Map.of(
                        A, slot(VALUE_1, Bytes.EMPTY, B),
                        B, slot(VALUE_2, A, Bytes.EMPTY)));

        final int delta = subject.updateStorage(HOOK_ID, List.of(slotUpdate(X, VALUE_4), slotUpdate(A, VALUE_9)));

        assertEquals(1, delta);
        assertEquals(slot(VALUE_4, Bytes.EMPTY, A), currentStorage.get(slotKey(X)));
        assertEquals(slot(VALUE_9, X, B), currentStorage.get(slotKey(A)));
        assertEquals(slot(VALUE_2, A, Bytes.EMPTY), currentStorage.get(slotKey(B)));
        assertEquals(X, currentHookStates.get(HOOK_ID).firstContractStorageKey());
        assertEquals(3L, currentHookStates.get(HOOK_ID).numStorageSlots());
    }

    private void givenExistingHookWithSlots(
            @SuppressWarnings("SameParameterValue") final Bytes firstKey, final Map<Bytes, SlotValue> slots) {
        final var state = EvmHookState.newBuilder()
                .hookId(HOOK_ID)
                .firstContractStorageKey(firstKey)
                .numStorageSlots(slots.size())
                .build();
        originalHookStates.put(HOOK_ID, state);
        currentHookStates.put(HOOK_ID, state);

        for (final var entry : slots.entrySet()) {
            final var slotKey = slotKey(entry.getKey());
            originalStorage.put(slotKey, entry.getValue());
            currentStorage.put(slotKey, entry.getValue());
        }
    }

    private EvmHookSlotKey slotKey(final Bytes key) {
        return new EvmHookSlotKey(HOOK_ID, key);
    }

    private static SlotValue slot(final Bytes value, final Bytes prev, final Bytes next) {
        return new SlotValue(value, prev, next);
    }

    private static EvmHookStorageUpdate slotUpdate(final Bytes key, final Bytes value) {
        return EvmHookStorageUpdate.newBuilder()
                .storageSlot(
                        EvmHookStorageSlot.newBuilder().key(key).value(value).build())
                .build();
    }
}
