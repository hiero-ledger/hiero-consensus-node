// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.infra;

import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.tuweniToPbjBytes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.state.contract.SlotKey;
import com.hedera.hapi.node.state.contract.SlotValue;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaOperations;
import com.hedera.node.app.service.contract.impl.exec.scope.SystemContractOperations;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater.Enhancement;
import com.hedera.node.app.service.contract.impl.infra.IterableStorageManager;
import com.hedera.node.app.service.contract.impl.state.ContractStateStore;
import com.hedera.node.app.service.contract.impl.state.StorageAccess;
import com.hedera.node.app.service.contract.impl.state.StorageAccesses;
import com.hedera.node.app.service.contract.impl.state.WritableEvmHookStore;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.apache.tuweni.units.bigints.UInt256;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Exercises real linked-list persistence against mutable state shared by enclosing and nested scopes. */
class NestedStoragePersistenceTest {
    private static final ContractID CONTRACT =
            ContractID.newBuilder().contractNum(1234).build();

    @ParameterizedTest
    @MethodSource("overlappingWrites")
    void reconcilesNestedAndResumedWritesWithoutChangingTraceSnapshots(
            final int initialValue, final int beforeValue, final int nestedValue, final int afterValue) {
        final var state = new StorageState();
        if (initialValue != 0) {
            state.seed(0, initialValue);
        }
        state.write(0, beforeValue);
        state.write(0, nestedValue);
        final var nestedAccess = write(0, beforeValue, nestedValue);
        state.persist(List.of(nestedAccess));
        state.assertConsistent();
        assertEquals(nestedValue == 0 ? 0 : 1, state.account.contractKvPairsNumber());

        state.write(0, afterValue);
        final var outerAccess = write(0, initialValue, afterValue);
        state.persist(List.of(outerAccess));

        state.assertConsistent();
        assertEquals(afterValue == 0 ? 0 : 1, state.account.contractKvPairsNumber());
        assertEquals(UInt256.valueOf(beforeValue), nestedAccess.value(), "Nested trace keeps its original read");
        assertEquals(UInt256.valueOf(initialValue), outerAccess.value(), "Outer trace keeps its original read");
    }

    private static Stream<Arguments> overlappingWrites() {
        return Stream.of(
                Arguments.of(10, 11, 0, 33),
                Arguments.of(0, 11, 0, 0),
                Arguments.of(0, 11, 22, 33),
                Arguments.of(10, 0, 22, 33),
                Arguments.of(0, 0, 22, 0),
                Arguments.of(10, 11, 0, 0));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void restoresHeadMiddleOrTailAfterNestedRemoval(final int removedSlot) {
        final var state = new StorageState();
        state.seed(0, 10);
        state.seed(1, 11);
        state.seed(2, 12);
        state.write(removedSlot, 0);
        state.persist(List.of(write(removedSlot, 10 + removedSlot, 0)));
        state.assertConsistent();
        assertEquals(2, state.account.contractKvPairsNumber());

        state.write(removedSlot, 33);
        // The enclosing trace also includes neighbors changed by the nested list maintenance.
        state.persist(Stream.of(0, 1, 2)
                .map(slot -> write(slot, 10 + slot, slot == removedSlot ? 33 : 10 + slot))
                .toList());

        state.assertConsistent();
        assertEquals(3, state.account.contractKvPairsNumber());
        assertEquals(key(removedSlot), state.account.firstContractStorageKey());
    }

    @Test
    void classifiesAdjacentRemovalsBeforeMutatingTheirLinks() {
        final var state = new StorageState();
        state.seed(0, 10);
        state.seed(1, 11);
        state.seed(2, 12);
        state.write(1, 0);
        state.write(2, 0);

        state.persist(List.of(write(1, 0, 0), write(2, 0, 0)));

        state.assertConsistent();
        assertEquals(1, state.account.contractKvPairsNumber());
        assertEquals(key(0), state.account.firstContractStorageKey(), "A full zero key is a real list head");
    }

    @Test
    void repeatedNestedInsertionsAndRemovalsDoNotRetainObsoleteMembership() {
        final var state = new StorageState();
        state.write(0, 11);
        state.persist(List.of(write(0, 0, 11)));
        state.write(0, 0);
        state.persist(List.of(write(0, 11, 0)));
        state.write(0, 22);
        state.persist(List.of(write(0, 0, 22)));
        state.persist(List.of(write(0, 0, 22)));

        state.assertConsistent();
        assertEquals(1, state.account.contractKvPairsNumber());
    }

    @Test
    void restoredSavepointStateAlsoRestoresTheAccountingBaseline() {
        final var state = new StorageState();
        state.seed(0, 10);
        state.seed(1, 11);
        final var originalSlots = new HashMap<>(state.slots);
        final var originalAccount = state.account;
        final var originalCount = state.count;
        state.write(0, 0);
        state.persist(List.of(write(0, 10, 0)));
        state.write(2, 22);
        state.persist(List.of(write(2, 0, 22)));

        // Savepoint rollback restores slots, account metadata, and counters together. There is no
        // separate Java membership cache that could outlive this rollback.
        state.slots.clear();
        state.slots.putAll(originalSlots);
        state.account = originalAccount;
        state.count = originalCount;
        state.write(0, 33);
        state.write(2, 44);
        state.persist(List.of(write(0, 10, 33), write(2, 0, 44)));

        state.assertConsistent();
        assertEquals(3, state.account.contractKvPairsNumber());
    }

    private static StorageAccess write(final int slot, final int before, final int after) {
        return StorageAccess.newWrite(UInt256.valueOf(slot), UInt256.valueOf(before), UInt256.valueOf(after));
    }

    private static Bytes key(final int slot) {
        return tuweniToPbjBytes(UInt256.valueOf(slot));
    }

    private static final class StorageState {
        private final Map<SlotKey, SlotValue> slots = new HashMap<>();
        private Account account = Account.newBuilder().build();
        private long count;
        private final ContractStateStore store = mock(ContractStateStore.class);
        private final HederaOperations operations = mock(HederaOperations.class);
        private final HederaNativeOperations nativeOperations = mock(HederaNativeOperations.class);
        private final WritableEvmHookStore hookStore = mock(WritableEvmHookStore.class);
        private final Enhancement enhancement =
                new Enhancement(operations, nativeOperations, mock(SystemContractOperations.class));
        private final IterableStorageManager manager = new IterableStorageManager();

        private StorageState() {
            when(nativeOperations.getAccount(CONTRACT)).thenAnswer(_ -> account);
            when(store.getSlotValue(any())).thenAnswer(invocation -> slots.get(invocation.getArgument(0)));
            when(hookStore.getModifiedEvmHookSlotKeys()).thenReturn(Set.of());
            doAnswer(invocation -> {
                        slots.put(invocation.getArgument(0), invocation.getArgument(1));
                        return null;
                    })
                    .when(store)
                    .putSlot(any(), any());
            doAnswer(invocation -> {
                        slots.remove(invocation.getArgument(0));
                        return null;
                    })
                    .when(store)
                    .removeSlot(any());
            doAnswer(invocation -> {
                        count += invocation.<Long>getArgument(0);
                        return null;
                    })
                    .when(store)
                    .adjustSlotCount(anyLong());
            doAnswer(invocation -> {
                        account = account.copyBuilder()
                                .firstContractStorageKey(invocation.getArgument(1))
                                .contractKvPairsNumber(
                                        account.contractKvPairsNumber() + invocation.<Integer>getArgument(2))
                                .build();
                        return null;
                    })
                    .when(operations)
                    .updateStorageMetadata(eq(CONTRACT), any(), anyInt());
        }

        private void seed(final int slot, final int value) {
            write(slot, value);
            persist(List.of(NestedStoragePersistenceTest.write(slot, 0, value)));
        }

        private void write(final int slot, final int value) {
            final var slotKey = new SlotKey(CONTRACT, key(slot));
            final var previous = slots.get(slotKey);
            if (previous != null || value != 0) {
                slots.put(
                        slotKey,
                        new SlotValue(
                                tuweniToPbjBytes(UInt256.valueOf(value)),
                                previous == null ? Bytes.EMPTY : previous.previousKey(),
                                previous == null ? Bytes.EMPTY : previous.nextKey()));
            }
        }

        private void persist(final List<StorageAccess> scopeAccesses) {
            final var changes = manager.getPersistenceChanges(
                    enhancement, List.of(new StorageAccesses(CONTRACT, scopeAccesses)), store);
            manager.persistChanges(
                    enhancement,
                    changes,
                    changes.stream().map(StorageAccesses::summarizeSizeEffects).toList(),
                    store,
                    hookStore);
        }

        private void assertConsistent() {
            assertEquals(slots.size(), count, "Global count matches physical slots");
            assertEquals(slots.size(), account.contractKvPairsNumber(), "Account count matches physical slots");
            final Set<Bytes> visited = new HashSet<>();
            var next = account.firstContractStorageKey();
            var previous = Bytes.EMPTY;
            while (!next.equals(Bytes.EMPTY)) {
                assertTrue(visited.add(next), "Linked list has no cycle");
                final var value = slots.get(new SlotKey(CONTRACT, next));
                assertTrue(value != null, "Every link targets a stored slot");
                assertEquals(previous, value.previousKey(), "Backward and forward links agree");
                assertFalse(UInt256.fromBytes(
                                org.apache.tuweni.bytes.Bytes.wrap(value.value().toByteArray()))
                        .isZero());
                previous = next;
                next = value.nextKey();
            }
            assertEquals(slots.size(), visited.size(), "Every stored slot is linked");
        }
    }
}
