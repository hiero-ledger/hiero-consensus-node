// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.infra;

import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.tuweniToPbjBytes;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.state.contract.SlotKey;
import com.hedera.hapi.node.state.contract.SlotValue;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaOperations;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater.Enhancement;
import com.hedera.node.app.service.contract.impl.infra.IterableStorageManager;
import com.hedera.node.app.service.contract.impl.state.ContractStateStore;
import com.hedera.node.app.service.contract.impl.state.StorageAccess;
import com.hedera.node.app.service.contract.impl.state.StorageAccesses;
import com.hedera.node.app.service.contract.impl.state.StorageSizeChange;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.List;
import org.apache.tuweni.units.bigints.UInt256;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IterableStorageManagerTest {
    private final ContractID CONTRACT_1 =
            ContractID.newBuilder().contractNum(1L).build();
    private final ContractID CONTRACT_2 =
            ContractID.newBuilder().contractNum(2L).build();
    private final Bytes BYTES_1 = tuweniToPbjBytes(UInt256.ONE);
    private final Bytes BYTES_2 = tuweniToPbjBytes(UInt256.valueOf(2L));
    private final Bytes BYTES_3 = tuweniToPbjBytes(UInt256.valueOf(3L));

    @Mock
    private HederaOperations hederaOperations;

    @Mock
    private HederaNativeOperations hederaNativeOperations;

    @Mock
    private Enhancement enhancement;

    @Mock
    private ContractStateStore store;

    @Mock
    private Account account;

    private final IterableStorageManager subject = new IterableStorageManager();

    @Test
    void rewriteUpdatesKvCountStorageMetadataOnly() {
        final var sizeChanges = List.of(
                new StorageSizeChange(CONTRACT_1, 2, 3),
                new StorageSizeChange(CONTRACT_2, 3, 2),
                new StorageSizeChange(ContractID.newBuilder().contractNum(3L).build(), 4, 4));

        given(enhancement.operations()).willReturn(hederaOperations);
        subject.persistChanges(enhancement, List.of(), sizeChanges, store);

        verify(hederaOperations).updateStorageMetadata(CONTRACT_1, Bytes.EMPTY, 1);
        verify(hederaOperations).updateStorageMetadata(CONTRACT_2, Bytes.EMPTY, -1);
        verify(hederaOperations, never()).updateStorageMetadata(CONTRACT_2, Bytes.EMPTY, 0);
    }

    @Test
    void removesLastSlotsWithZeroValues() {
        final var accesses = List.of(
                new StorageAccesses(
                        CONTRACT_1,
                        List.of(
                                StorageAccess.newRead(UInt256.ONE, UInt256.MIN_VALUE),
                                StorageAccess.newWrite(UInt256.ONE, UInt256.valueOf(1L), UInt256.MAX_VALUE))),
                new StorageAccesses(
                        CONTRACT_2,
                        List.of(
                                StorageAccess.newRead(UInt256.ONE, UInt256.MAX_VALUE),
                                StorageAccess.newWrite(UInt256.ONE, UInt256.MAX_VALUE, UInt256.ZERO))));

        final var sizeChanges =
                List.of(new StorageSizeChange(CONTRACT_1, 0, 0), new StorageSizeChange(CONTRACT_2, 1, 0));

        given(enhancement.nativeOperations()).willReturn(hederaNativeOperations);
        given(hederaNativeOperations.getAccount((ContractID) any())).willReturn(account);
        given(account.firstContractStorageKey()).willReturn(BYTES_1);
        given(enhancement.operations()).willReturn(hederaOperations);
        // Deleting the last slot contract storage for CONTRACT_2
        given(store.getSlotValue(new SlotKey(CONTRACT_2, BYTES_1)))
                .willReturn(new SlotValue(Bytes.EMPTY, Bytes.EMPTY, Bytes.EMPTY));

        subject.persistChanges(enhancement, accesses, sizeChanges, store);

        // Model deleting the second contract storage
        verify(store).getSlotValue(new SlotKey(CONTRACT_2, BYTES_1));
        verify(store).removeSlot(new SlotKey(CONTRACT_2, BYTES_1));
        verify(store).adjustSlotCount(-1);
        verifyNoMoreInteractions(store);

        // Model call to modify metadata for CONTRACT_2.
        // The new first key is Bytes.EMPTY as the last slot for the contract was deleted.
        verify(hederaOperations).updateStorageMetadata(CONTRACT_2, Bytes.EMPTY, -1);
        verifyNoMoreInteractions(hederaOperations);
    }

    @Test
    void removeFirstSlot() {
        final var accesses = List.of(new StorageAccesses(
                CONTRACT_1, List.of(StorageAccess.newWrite(UInt256.ONE, UInt256.MAX_VALUE, UInt256.ZERO))));

        final var sizeChanges = List.of(new StorageSizeChange(CONTRACT_1, 1, 0));

        given(enhancement.nativeOperations()).willReturn(hederaNativeOperations);
        given(hederaNativeOperations.getAccount(CONTRACT_1)).willReturn(account);
        given(account.firstContractStorageKey()).willReturn(BYTES_1);
        given(enhancement.operations()).willReturn(hederaOperations);
        // Deleting the first slot
        given(store.getSlotValue(new SlotKey(CONTRACT_1, BYTES_1)))
                .willReturn(new SlotValue(BYTES_1, Bytes.EMPTY, BYTES_2));
        given(store.getSlotValue(new SlotKey(CONTRACT_1, BYTES_2)))
                .willReturn(new SlotValue(BYTES_2, BYTES_1, BYTES_3));

        subject.persistChanges(enhancement, accesses, sizeChanges, store);

        // Model deleting the first contract storage
        verify(store).putSlot(new SlotKey(CONTRACT_1, BYTES_2), new SlotValue(BYTES_2, Bytes.EMPTY, BYTES_3));
        verify(store).removeSlot(new SlotKey(CONTRACT_1, BYTES_1));
        // The new first key is BYTES_2 as the first slot for the contract was deleted.
        verify(hederaOperations).updateStorageMetadata(CONTRACT_1, BYTES_2, -1);
        verify(store).adjustSlotCount(-1);
        verifyNoMoreInteractions(store);
        verifyNoMoreInteractions(hederaOperations);
    }

    @Test
    void stillRemovesSlotEvenIfNextSlotIsMissing() {
        final var accesses = List.of(new StorageAccesses(
                CONTRACT_1, List.of(StorageAccess.newWrite(UInt256.ONE, UInt256.MAX_VALUE, UInt256.ZERO))));

        final var sizeChanges = List.of(new StorageSizeChange(CONTRACT_1, 1, 0));

        given(enhancement.nativeOperations()).willReturn(hederaNativeOperations);
        given(hederaNativeOperations.getAccount(CONTRACT_1)).willReturn(account);
        given(account.firstContractStorageKey()).willReturn(BYTES_1);
        given(enhancement.operations()).willReturn(hederaOperations);
        // Deleting the first slot
        given(store.getSlotValue(new SlotKey(CONTRACT_1, BYTES_1)))
                .willReturn(new SlotValue(BYTES_1, Bytes.EMPTY, BYTES_2));
        // The next slot is missing (invariant failure, should be impossible)
        given(store.getSlotValue(new SlotKey(CONTRACT_1, BYTES_2))).willReturn(null);

        subject.persistChanges(enhancement, accesses, sizeChanges, store);

        // Model deleting the first contract storage
        verify(store).removeSlot(new SlotKey(CONTRACT_1, BYTES_1));
        verify(store).adjustSlotCount(-1);
        // The new first key is BYTES_2 as the first slot for the contract was deleted.
        verify(hederaOperations).updateStorageMetadata(CONTRACT_1, BYTES_1, -1);
        verifyNoMoreInteractions(store);
        verifyNoMoreInteractions(hederaOperations);
    }

    @Test
    void removeSecondSlot() {
        final var accesses = List.of(new StorageAccesses(
                CONTRACT_1, List.of(StorageAccess.newWrite(UInt256.valueOf(2L), UInt256.MAX_VALUE, UInt256.ZERO))));

        final var sizeChanges = List.of(new StorageSizeChange(CONTRACT_1, 1, 0));

        given(enhancement.nativeOperations()).willReturn(hederaNativeOperations);
        given(hederaNativeOperations.getAccount(CONTRACT_1)).willReturn(account);
        given(account.firstContractStorageKey()).willReturn(BYTES_1);
        given(enhancement.operations()).willReturn(hederaOperations);
        // Deleting the second slot
        given(store.getSlotValue(new SlotKey(CONTRACT_1, BYTES_2)))
                .willReturn(new SlotValue(BYTES_2, BYTES_1, BYTES_3));
        given(store.getSlotValue(new SlotKey(CONTRACT_1, BYTES_1)))
                .willReturn(new SlotValue(BYTES_1, Bytes.EMPTY, BYTES_2));
        given(store.getSlotValue(new SlotKey(CONTRACT_1, BYTES_3)))
                .willReturn(new SlotValue(BYTES_3, BYTES_2, BYTES_3));

        subject.persistChanges(enhancement, accesses, sizeChanges, store);

        // Model deleting the second contract storage
        verify(store).putSlot(new SlotKey(CONTRACT_1, BYTES_1), new SlotValue(BYTES_1, Bytes.EMPTY, BYTES_3));
        verify(store).putSlot(new SlotKey(CONTRACT_1, BYTES_3), new SlotValue(BYTES_3, BYTES_1, BYTES_3));
        verify(store).removeSlot(new SlotKey(CONTRACT_1, BYTES_2));
        // The new first key is BYTES_1 as before running the test
        verify(hederaOperations).updateStorageMetadata(CONTRACT_1, BYTES_1, -1);
        verify(store).adjustSlotCount(-1);
        verifyNoMoreInteractions(store);
        verifyNoMoreInteractions(hederaOperations);
    }

    @Test
    void removeSlotValueNotFound() {
        final var accesses = List.of(new StorageAccesses(
                CONTRACT_1, List.of(StorageAccess.newWrite(UInt256.valueOf(2L), UInt256.MAX_VALUE, UInt256.ZERO))));

        final var sizeChanges = List.of(new StorageSizeChange(CONTRACT_1, 1, 0));

        given(enhancement.nativeOperations()).willReturn(hederaNativeOperations);
        given(hederaNativeOperations.getAccount(CONTRACT_1)).willReturn(account);
        given(account.firstContractStorageKey()).willReturn(BYTES_1);
        given(enhancement.operations()).willReturn(hederaOperations);
        // Looking up the slot value returns null
        given(store.getSlotValue(new SlotKey(CONTRACT_1, BYTES_2))).willReturn(null);
        subject.persistChanges(enhancement, accesses, sizeChanges, store);

        // The new first key is BYTES_1 as before running the test
        verify(hederaOperations).updateStorageMetadata(CONTRACT_1, BYTES_1, -1);
        verify(store).removeSlot(new SlotKey(CONTRACT_1, BYTES_2));
        verifyNoMoreInteractions(hederaOperations);
    }

    @Test
    void insertSlotIntoEmptyStorage() {
        final var accesses = List.of(new StorageAccesses(
                CONTRACT_1, List.of(StorageAccess.newWrite(UInt256.valueOf(2L), UInt256.ZERO, UInt256.MAX_VALUE))));

        final var sizeChanges = List.of(new StorageSizeChange(CONTRACT_1, 0, 1));

        given(enhancement.nativeOperations()).willReturn(hederaNativeOperations);
        given(hederaNativeOperations.getAccount(CONTRACT_1)).willReturn(account);
        given(account.firstContractStorageKey()).willReturn(Bytes.EMPTY);
        given(enhancement.operations()).willReturn(hederaOperations);

        // Insert into the second slot
        subject.persistChanges(enhancement, accesses, sizeChanges, store);

        // Model deleting the second contract storage
        verify(store)
                .putSlot(
                        new SlotKey(CONTRACT_1, BYTES_2),
                        new SlotValue(tuweniToPbjBytes(UInt256.MAX_VALUE), Bytes.EMPTY, Bytes.EMPTY));

        // The new first key is BYTES_2
        verify(hederaOperations).updateStorageMetadata(CONTRACT_1, BYTES_2, 1);
        verify(store).adjustSlotCount(+1);
        verifyNoMoreInteractions(store);
        verifyNoMoreInteractions(hederaOperations);
    }

    @Test
    void zeroIntoEmptySlotJustRemovesSuperfluousPendingUpdate() {
        final var accesses = List.of(new StorageAccesses(
                CONTRACT_1, List.of(StorageAccess.newWrite(UInt256.valueOf(2L), UInt256.ZERO, UInt256.ZERO))));

        final var sizeChanges = List.of(new StorageSizeChange(CONTRACT_1, 0, 0));

        given(enhancement.nativeOperations()).willReturn(hederaNativeOperations);
        given(hederaNativeOperations.getAccount(CONTRACT_1)).willReturn(account);
        given(account.firstContractStorageKey()).willReturn(Bytes.EMPTY);

        // "Insert" zero into an empty slot
        subject.persistChanges(enhancement, accesses, sizeChanges, store);

        verify(store).removeSlot(new SlotKey(CONTRACT_1, BYTES_2));
        verifyNoMoreInteractions(store);
    }

    @Test
    void multipleInsertsUseLatestHeadPointer() {
        final var accesses = List.of(new StorageAccesses(
                CONTRACT_1,
                List.of(
                        StorageAccess.newWrite(UInt256.valueOf(2L), UInt256.ZERO, UInt256.MAX_VALUE),
                        StorageAccess.newWrite(UInt256.valueOf(3L), UInt256.ZERO, UInt256.MAX_VALUE))));

        final var sizeChanges = List.of(new StorageSizeChange(CONTRACT_1, 0, 2));

        given(enhancement.nativeOperations()).willReturn(hederaNativeOperations);
        given(hederaNativeOperations.getAccount(CONTRACT_1)).willReturn(account);
        given(account.firstContractStorageKey()).willReturn(BYTES_1);
        given(enhancement.operations()).willReturn(hederaOperations);
        given(store.getSlotValue(new SlotKey(CONTRACT_1, BYTES_1)))
                .willReturn(new SlotValue(tuweniToPbjBytes(UInt256.ONE), Bytes.EMPTY, Bytes.EMPTY));
        given(store.getSlotValue(new SlotKey(CONTRACT_1, BYTES_2)))
                .willReturn(new SlotValue(tuweniToPbjBytes(UInt256.ONE), Bytes.EMPTY, BYTES_1));

        // Should insert into the head of the existing storage list
        subject.persistChanges(enhancement, accesses, sizeChanges, store);

        // The first insert (BYTES_2)
        verify(store)
                .putSlot(
                        new SlotKey(CONTRACT_1, BYTES_2),
                        new SlotValue(tuweniToPbjBytes(UInt256.MAX_VALUE), Bytes.EMPTY, BYTES_1));
        verify(store)
                .putSlot(
                        new SlotKey(CONTRACT_1, BYTES_1),
                        new SlotValue(tuweniToPbjBytes(UInt256.ONE), BYTES_2, Bytes.EMPTY));
        // The second insert (BYTES_3)
        verify(store)
                .putSlot(
                        new SlotKey(CONTRACT_1, BYTES_3),
                        new SlotValue(tuweniToPbjBytes(UInt256.MAX_VALUE), Bytes.EMPTY, BYTES_2));
        verify(store)
                .putSlot(
                        new SlotKey(CONTRACT_1, BYTES_2),
                        new SlotValue(tuweniToPbjBytes(UInt256.ONE), BYTES_3, BYTES_1));

        // The new first key is BYTES_3
        verify(hederaOperations).updateStorageMetadata(CONTRACT_1, BYTES_3, 2);
        verify(store).adjustSlotCount(2);
        verifyNoMoreInteractions(store);
        verifyNoMoreInteractions(hederaOperations);
    }

    @Test
    void insertSlotIntoExistingStorage() {
        final var accesses = List.of(new StorageAccesses(
                CONTRACT_1, List.of(StorageAccess.newWrite(UInt256.valueOf(2L), UInt256.ZERO, UInt256.MAX_VALUE))));

        final var sizeChanges = List.of(new StorageSizeChange(CONTRACT_1, 0, 1));

        given(enhancement.nativeOperations()).willReturn(hederaNativeOperations);
        given(hederaNativeOperations.getAccount(CONTRACT_1)).willReturn(account);
        given(account.firstContractStorageKey()).willReturn(BYTES_1);
        given(enhancement.operations()).willReturn(hederaOperations);
        given(store.getSlotValue(new SlotKey(CONTRACT_1, BYTES_1)))
                .willReturn(new SlotValue(tuweniToPbjBytes(UInt256.ONE), Bytes.EMPTY, Bytes.EMPTY));

        // Should insert into the head of the existing storage list
        subject.persistChanges(enhancement, accesses, sizeChanges, store);

        verify(store)
                .putSlot(
                        new SlotKey(CONTRACT_1, BYTES_2),
                        new SlotValue(tuweniToPbjBytes(UInt256.MAX_VALUE), Bytes.EMPTY, BYTES_1));
        verify(store)
                .putSlot(
                        new SlotKey(CONTRACT_1, BYTES_1),
                        new SlotValue(tuweniToPbjBytes(UInt256.ONE), BYTES_2, Bytes.EMPTY));

        // The new first key is BYTES_2
        verify(hederaOperations).updateStorageMetadata(CONTRACT_1, BYTES_2, 1);
        verify(store).adjustSlotCount(+1);
        verifyNoMoreInteractions(store);
        verifyNoMoreInteractions(hederaOperations);
    }

    @Test
    void slotStillInsertedEvenWithMissingPointer() {
        final var accesses = List.of(new StorageAccesses(
                CONTRACT_1, List.of(StorageAccess.newWrite(UInt256.valueOf(2L), UInt256.ZERO, UInt256.MAX_VALUE))));

        final var sizeChanges = List.of(new StorageSizeChange(CONTRACT_1, 0, 1));

        given(enhancement.nativeOperations()).willReturn(hederaNativeOperations);
        given(hederaNativeOperations.getAccount(CONTRACT_1)).willReturn(account);
        given(account.firstContractStorageKey()).willReturn(BYTES_1);
        given(enhancement.operations()).willReturn(hederaOperations);
        // The next slot is missing (invariant failure, should be impossible)
        given(store.getSlotValue(new SlotKey(CONTRACT_1, BYTES_1))).willReturn(null);

        // Insert into the second slot
        subject.persistChanges(enhancement, accesses, sizeChanges, store);

        // Model deleting the second contract storage
        verify(store)
                .putSlot(
                        new SlotKey(CONTRACT_1, BYTES_2),
                        new SlotValue(tuweniToPbjBytes(UInt256.MAX_VALUE), Bytes.EMPTY, BYTES_1));

        // The new first key is BYTES_2
        verify(hederaOperations).updateStorageMetadata(CONTRACT_1, BYTES_2, 1);
        verify(store).adjustSlotCount(+1);
        verifyNoMoreInteractions(store);
        verifyNoMoreInteractions(hederaOperations);
    }
}
