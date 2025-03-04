// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.scope;

import static com.hedera.node.app.service.contract.impl.test.TestHelpers.DEFAULT_HEDERA_CONFIG;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.contract.ContractCreateTransactionBody;
import com.hedera.node.app.service.contract.impl.exec.gas.TinybarValues;
import com.hedera.node.app.service.contract.impl.exec.scope.HandleHederaOperations;
import com.hedera.node.app.service.contract.impl.exec.scope.QueryHederaOperations;
import com.hedera.node.app.service.contract.impl.state.ContractStateStore;
import com.hedera.node.app.spi.fixtures.ids.FakeEntityIdFactoryImpl;
import com.hedera.node.app.spi.records.BlockRecordInfo;
import com.hedera.node.app.spi.workflows.QueryContext;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.Collections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class QueryHederaOperationsTest {
    @Mock
    private QueryContext context;

    @Mock
    private ContractStateStore store;

    @Mock
    private BlockRecordInfo blockRecordInfo;

    @Mock
    private TinybarValues tinybarValues;

    private QueryHederaOperations subject;

    private ContractID contractID;

    private ContractID anotherContractID;

    @BeforeEach
    void setUp() {
        subject = new QueryHederaOperations(context, DEFAULT_HEDERA_CONFIG, tinybarValues);
        contractID = ContractID.newBuilder().contractNum(1234L).build();
        anotherContractID = ContractID.newBuilder().contractNum(1L).build();
    }

    @Test
    void beginningNewExtWorldScopeReturnsThis() {
        assertSame(subject, subject.begin());
    }

    @Test
    void commitIsNoop() {
        assertDoesNotThrow(subject::commit);
    }

    @Test
    void revertIsNoop() {
        assertDoesNotThrow(subject::revert);
    }

    @Test
    void createsStoreAsExpected() {
        given(context.createStore(ContractStateStore.class)).willReturn(store);

        assertSame(store, subject.getStore());
    }

    @Test
    void peekingAndUsingEntityNumbersNotSupported() {
        assertThrows(UnsupportedOperationException.class, subject::peekNextEntityNumber);
        assertThrows(UnsupportedOperationException.class, subject::useNextEntityNumber);
    }

    @Test
    void delegatesEntropyToBlockRecordInfo() {
        final var pretendEntropy = Bytes.fromHex("0123456789");
        given(context.blockRecordInfo()).willReturn(blockRecordInfo);
        given(blockRecordInfo.prngSeed()).willReturn(pretendEntropy);
        assertSame(pretendEntropy, subject.entropy());
    }

    @Test
    void returnsZeroEntropyIfNMinus3HashMissing() {
        given(context.blockRecordInfo()).willReturn(blockRecordInfo);
        assertSame(HandleHederaOperations.ZERO_ENTROPY, subject.entropy());
    }

    @Test
    void gasPriceInTinybarsHardcoded() {
        assertEquals(1L, subject.gasPriceInTinybars());
    }

    @Test
    void valueInTinybarsUsesOneToOneExchange() {
        given(tinybarValues.asTinybars(1L)).willReturn(2L);
        assertEquals(2L, subject.valueInTinybars(1L));
    }

    @Test
    void collectingAndRefundingFeesNotSupported() {
        assertThrows(UnsupportedOperationException.class, () -> subject.collectFee(AccountID.DEFAULT, 1234L));
        assertThrows(UnsupportedOperationException.class, () -> subject.refundFee(AccountID.DEFAULT, 1234L));
    }

    @Test
    void chargingStorageRentNotSupported() {
        assertThrows(UnsupportedOperationException.class, () -> subject.chargeStorageRent(anotherContractID, 2L, true));
    }

    @Test
    void creatingAndDeletingContractsNotSupported() {
        assertThrows(UnsupportedOperationException.class, subject::contractCreationLimit);
        assertThrows(UnsupportedOperationException.class, subject::accountCreationLimit);
        assertThrows(UnsupportedOperationException.class, () -> subject.createContract(1L, 2L, null));
        assertThrows(
                UnsupportedOperationException.class,
                () -> subject.createContract(1L, ContractCreateTransactionBody.DEFAULT, null));
        assertThrows(UnsupportedOperationException.class, () -> subject.deleteAliasedContract(Bytes.EMPTY));
        assertThrows(UnsupportedOperationException.class, () -> subject.deleteUnaliasedContract(1234L));
    }

    @Test
    void neverAnyModifiedAccountNumbers() {
        assertSame(Collections.emptyList(), subject.getModifiedAccountNumbers());
    }

    @Test
    void getOriginalSlotsUsedNotSupported() {
        assertThrows(UnsupportedOperationException.class, () -> subject.getOriginalSlotsUsed(contractID));
    }

    @Test
    void acceptsValidShardAndRealm() {
        final var calledContractId = new FakeEntityIdFactoryImpl(
                        DEFAULT_HEDERA_CONFIG.shard(), DEFAULT_HEDERA_CONFIG.realm())
                .newContractId(1L);
        assertSame(calledContractId, subject.shardAndRealmValidated(calledContractId));
    }

    @Test
    void summarizingContractChangesNotSupported() {
        assertThrows(UnsupportedOperationException.class, subject::summarizeContractChanges);
    }
}
