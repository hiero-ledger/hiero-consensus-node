// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.state;

import static com.hedera.node.app.service.contract.impl.test.TestHelpers.CALLED_CONTRACT_ID;
import static com.hedera.node.app.spi.workflows.HandleContext.DispatchMetadata.Type.EXPLICIT_WRITE_TRACING;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.contract.ContractNonceInfo;
import com.hedera.hapi.node.state.contract.SlotKey;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaOperations;
import com.hedera.node.app.service.contract.impl.exec.scope.SystemContractOperations;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater.Enhancement;
import com.hedera.node.app.service.contract.impl.infra.IterableStorageManager;
import com.hedera.node.app.service.contract.impl.infra.RentCalculator;
import com.hedera.node.app.service.contract.impl.infra.StorageSizeValidator;
import com.hedera.node.app.service.contract.impl.state.ContractStateStore;
import com.hedera.node.app.service.contract.impl.state.EvmFrameState;
import com.hedera.node.app.service.contract.impl.state.RentFactors;
import com.hedera.node.app.service.contract.impl.state.RootProxyWorldUpdater;
import com.hedera.node.app.service.contract.impl.state.StorageAccess;
import com.hedera.node.app.service.contract.impl.state.StorageAccesses;
import com.hedera.node.app.service.contract.impl.state.StorageSizeChange;
import com.hedera.node.app.service.contract.impl.state.TxStorageUsage;
import com.hedera.node.app.service.token.api.ContractChangeSummary;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.config.data.ContractsConfig;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.apache.tuweni.units.bigints.UInt256;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.BDDMockito;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RootProxyWorldUpdaterTest {
    private static final ContractID A_CONTRACT_ID =
            ContractID.newBuilder().contractNum(123L).build();
    private static final ContractID B_CONTRACTID =
            ContractID.newBuilder().contractNum(234L).build();
    private static final UInt256 A_KEY_BEING_ADDED = UInt256.fromHexString("0x1234");
    private static final UInt256 A_KEY_BEING_CHANGED = UInt256.fromHexString("0x2345");
    private static final UInt256 A_KEY_BEING_REMOVED = UInt256.fromHexString("0x3456");
    private static final UInt256 A_SECOND_KEY_BEING_ADDED = UInt256.fromHexString("0x4567");
    private static final UInt256 A_THIRD_KEY_BEING_ADDED = UInt256.fromHexString("0x7654");
    private static final UInt256 B_KEY_BEING_ADDED = UInt256.fromHexString("0x5678");
    private static final UInt256 B_KEY_BEING_REMOVED = UInt256.fromHexString("0x6789");
    private static final UInt256 B_SECOND_KEY_BEING_REMOVED = UInt256.fromHexString("0x7890");

    @Mock
    private RentCalculator rentCalculator;

    @Mock
    private IterableStorageManager storageManager;

    @Mock
    private StorageSizeValidator storageSizeValidator;

    @Mock
    private HandleContext context;

    @Mock
    private EvmFrameState evmFrameState;

    @Mock
    private HederaOperations hederaOperations;

    @Mock
    private HederaNativeOperations hederaNativeOperations;

    @Mock
    private SystemContractOperations systemContractOperations;

    @Mock
    private ContractStateStore store;

    private Enhancement enhancement;
    private RootProxyWorldUpdater subject;

    @BeforeEach
    void setup() {
        enhancement =
                new HederaWorldUpdater.Enhancement(hederaOperations, hederaNativeOperations, systemContractOperations);
        givenSubjectWith(HederaTestConfigBuilder.create().getOrCreateConfig(), enhancement);
    }

    @Test
    void refusesToReturnCommittedChangesWithoutSuccessfulCommit() {
        assertThrows(IllegalStateException.class, subject::getUpdatedContractNonces);
        assertThrows(IllegalStateException.class, subject::getCreatedContractIds);
    }

    @Test
    void performsAdditionalCommitActionsInOrder() {
        InOrder inOrder = BDDMockito.inOrder(storageSizeValidator, storageManager, rentCalculator, hederaOperations);

        final var aExpiry = 1_234_567;
        final var aSlotsUsedBeforeCommit = 101;
        final var sizeIncludingPendingRemovals = 123L;
        final var mockChangedKeys = Set.of(new SlotKey(A_CONTRACT_ID, Bytes.wrap("123")));
        // Three keys being removed in pending changes
        final var sizeExcludingPendingRemovals = sizeIncludingPendingRemovals - 3;
        given(context.dispatchMetadata())
                .willReturn(new HandleContext.DispatchMetadata(EXPLICIT_WRITE_TRACING, Boolean.FALSE));
        given(evmFrameState.getKvStateSize()).willReturn(sizeIncludingPendingRemovals);
        given(evmFrameState.getTxStorageUsage(true)).willReturn(new TxStorageUsage(pendingChanges(), mockChangedKeys));
        given(evmFrameState.getRentFactorsFor(A_CONTRACT_ID))
                .willReturn(new RentFactors(aSlotsUsedBeforeCommit, aExpiry));

        final var rentInTinycents = 666_666L;
        final var rentInTinybars = 111_111L;
        // A contract is allocating 2 slots net
        given(rentCalculator.computeFor(sizeExcludingPendingRemovals, 2, aSlotsUsedBeforeCommit, aExpiry))
                .willReturn(rentInTinycents);
        given(hederaOperations.valueInTinybars(rentInTinycents)).willReturn(rentInTinybars);
        given(hederaOperations.getStore()).willReturn(store);
        final var createdIds = new ArrayList<>(List.of(CALLED_CONTRACT_ID));
        final var updatedNonces = new ArrayList<>(List.of(new ContractNonceInfo(CALLED_CONTRACT_ID, 1L)));
        given(hederaOperations.summarizeContractChanges())
                .willReturn(new ContractChangeSummary(createdIds, updatedNonces));

        subject.commit();

        inOrder.verify(storageSizeValidator)
                .assertValid(sizeExcludingPendingRemovals, hederaOperations, expectedSizeChanges());
        inOrder.verify(hederaOperations).chargeStorageRent(A_CONTRACT_ID, rentInTinybars, true);
        inOrder.verify(storageManager).persistChanges(enhancement, pendingChanges(), expectedSizeChanges(), store);
        inOrder.verify(hederaOperations).commit();

        assertSame(createdIds, subject.getCreatedContractIds());
        assertSame(updatedNonces, subject.getUpdatedContractNonces());
        assertSame(mockChangedKeys, subject.getTxStorageUsage().changedKeys());
    }

    private void givenSubjectWith(@NonNull final Configuration configuration, @NonNull final Enhancement enhancement) {
        subject = new RootProxyWorldUpdater(
                enhancement,
                configuration.getConfigData(ContractsConfig.class),
                () -> evmFrameState,
                rentCalculator,
                storageManager,
                storageSizeValidator,
                context);
    }

    private List<StorageAccesses> pendingChanges() {
        return List.of(
                new StorageAccesses(
                        A_CONTRACT_ID,
                        List.of(
                                new StorageAccess(A_KEY_BEING_ADDED, UInt256.ZERO, UInt256.ONE),
                                new StorageAccess(A_KEY_BEING_CHANGED, UInt256.ONE, UInt256.MAX_VALUE),
                                new StorageAccess(A_KEY_BEING_REMOVED, UInt256.ONE, UInt256.ZERO),
                                new StorageAccess(A_SECOND_KEY_BEING_ADDED, UInt256.ZERO, UInt256.ONE),
                                new StorageAccess(A_THIRD_KEY_BEING_ADDED, UInt256.ZERO, UInt256.MAX_VALUE))),
                new StorageAccesses(
                        B_CONTRACTID,
                        List.of(
                                new StorageAccess(B_KEY_BEING_ADDED, UInt256.ZERO, UInt256.ONE),
                                new StorageAccess(B_KEY_BEING_REMOVED, UInt256.ONE, UInt256.ZERO),
                                new StorageAccess(B_SECOND_KEY_BEING_REMOVED, UInt256.ONE, UInt256.ZERO))));
    }

    private List<StorageSizeChange> expectedSizeChanges() {
        return List.of(new StorageSizeChange(A_CONTRACT_ID, 1, 3), new StorageSizeChange(B_CONTRACTID, 2, 1));
    }
}
