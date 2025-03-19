// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.fee;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.Duration;
import com.hedera.hapi.node.base.FeeComponents;
import com.hedera.hapi.node.base.FeeData;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.ResponseType;
import com.hedera.hapi.node.base.TransferList;
import com.hedera.hapi.node.token.CryptoCreateTransactionBody;
import com.hedera.hapi.node.token.CryptoDeleteTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.node.transaction.TransactionRecord;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CryptoFeeBuilderTest {
    private CryptoFeeBuilder subject;

    @BeforeEach
    void setUp() {
        subject = new CryptoFeeBuilder();
    }

    @Test
    void getsCorrectCryptoCreateTxFeeMatrices() {
        final var sigValueObj = new SigValueObj(2, 1, 10);
        final var networkFee = feeBuilder().bpt(154L).vpt(2L).rbh(3L).build();
        final var nodeFee =
                feeBuilder().bpt(154L).vpt(1L).bpr(FeeBuilder.INT_SIZE).build();
        final var serviceFee = feeBuilder().rbh(6L);

        final var defaultCryptoCreate = CryptoCreateTransactionBody.DEFAULT;
        var feeData = CryptoFeeBuilder.getCryptoCreateTxFeeMatrices(
                txBuilder().cryptoCreateAccount(defaultCryptoCreate).build(), sigValueObj);
        assertEquals(networkFee, feeData.networkdata());
        assertEquals(nodeFee, feeData.nodedata());
        assertEquals(serviceFee.build(), feeData.servicedata());

        final var cryptoCreate = CryptoCreateTransactionBody.newBuilder()
                .autoRenewPeriod(Duration.newBuilder().seconds(1000L))
                .newRealmAdminKey(Key.DEFAULT);
        feeData = CryptoFeeBuilder.getCryptoCreateTxFeeMatrices(
                txBuilder().cryptoCreateAccount(cryptoCreate).build(), sigValueObj);
        assertEquals(networkFee, feeData.networkdata());
        assertEquals(nodeFee, feeData.nodedata());
        assertEquals(serviceFee.rbh(25L).build(), feeData.servicedata());
    }

    @Test
    void getsCorrectCryptoDeleteTxFeeMatrices() {
        final var sigValueObj = new SigValueObj(5, 3, 20);
        final var networkFee = feeBuilder().bpt(144L).vpt(5L).rbh(1L).build();
        final var nodeFee =
                feeBuilder().bpt(144L).vpt(3L).bpr(FeeBuilder.INT_SIZE).build();
        final var serviceFee = feeBuilder().rbh(6L).build();

        final var defaultCryptoDelete = CryptoDeleteTransactionBody.DEFAULT;
        var feeData = subject.getCryptoDeleteTxFeeMatrices(
                txBuilder().cryptoDelete(defaultCryptoDelete).build(), sigValueObj);
        assertEquals(networkFee, feeData.networkdata());
        assertEquals(nodeFee, feeData.nodedata());
        assertEquals(serviceFee, feeData.servicedata());
    }

    @Test
    void getsCorrectCostTransactionRecordQueryFeeMatrices() {
        assertEquals(FeeData.DEFAULT, CryptoFeeBuilder.getCostTransactionRecordQueryFeeMatrices());
    }

    @Test
    void getsCorrectTransactionRecordQueryFeeMatrices() {
        assertEquals(FeeData.DEFAULT, subject.getTransactionRecordQueryFeeMatrices(null, ResponseType.COST_ANSWER));

        final var transRecord = txRecordBuilder().build();
        var feeData = subject.getTransactionRecordQueryFeeMatrices(transRecord, ResponseType.COST_ANSWER);
        assertQueryFee(feeData, 148L);

        feeData = subject.getTransactionRecordQueryFeeMatrices(transRecord, ResponseType.ANSWER_STATE_PROOF);
        assertQueryFee(feeData, 2148L);

        feeData = subject.getTransactionRecordQueryFeeMatrices(recordWithMemo(), ResponseType.ANSWER_ONLY);
        assertQueryFee(feeData, 158L);

        feeData = subject.getTransactionRecordQueryFeeMatrices(
                recordWithTransferList(), ResponseType.COST_ANSWER_STATE_PROOF);
        assertQueryFee(feeData, 2500L);
    }

    @Test
    void getsCorrectCryptoAccountRecordsQueryFeeMatrices() {
        var feeData = subject.getCryptoAccountRecordsQueryFeeMatrices(null, ResponseType.COST_ANSWER);
        assertQueryFee(feeData, FeeBuilder.BASIC_QUERY_RES_HEADER);

        final List<TransactionRecord> transRecords = new ArrayList<>();
        transRecords.add(txRecordBuilder().build());
        feeData = subject.getCryptoAccountRecordsQueryFeeMatrices(transRecords, ResponseType.COST_ANSWER);
        assertQueryFee(feeData, 148L);

        feeData = subject.getCryptoAccountRecordsQueryFeeMatrices(transRecords, ResponseType.ANSWER_STATE_PROOF);
        assertQueryFee(feeData, 2148L);

        transRecords.add(recordWithMemo());
        feeData = subject.getCryptoAccountRecordsQueryFeeMatrices(transRecords, ResponseType.ANSWER_ONLY);
        assertQueryFee(feeData, 290L);

        transRecords.add(recordWithTransferList());
        feeData = subject.getCryptoAccountRecordsQueryFeeMatrices(transRecords, ResponseType.COST_ANSWER_STATE_PROOF);
        assertQueryFee(feeData, 2774L);
    }

    @Test
    void getsCorrectCostCryptoAccountRecordsQueryFeeMatrices() {
        assertEquals(FeeData.DEFAULT, CryptoFeeBuilder.getCostCryptoAccountRecordsQueryFeeMatrices());
    }

    @Test
    void getsCorrectCostCryptoAccountInfoQueryFeeMatrices() {
        assertEquals(FeeData.DEFAULT, CryptoFeeBuilder.getCostCryptoAccountInfoQueryFeeMatrices());
    }

    private void assertQueryFee(final FeeData feeData, final long expectedBpr) {
        final var expectedBpt = FeeBuilder.BASIC_QUERY_HEADER + FeeBuilder.BASIC_TX_ID_SIZE;
        final var nodeFee = feeBuilder().bpt(expectedBpt).bpr(expectedBpr).build();

        assertEquals(FeeComponents.DEFAULT, feeData.servicedata());
        assertEquals(FeeComponents.DEFAULT, feeData.networkdata());
        assertEquals(nodeFee, feeData.nodedata());
    }

    private TransactionRecord recordWithMemo() {
        return txRecordBuilder().memo("0123456789").build();
    }

    private TransactionRecord recordWithTransferList() {
        final var transferList = TransferList.newBuilder();
        List<AccountAmount> accountAmounts = new ArrayList<>();
        for (int i = -5; i <= 5; i++) {
            accountAmounts.add(AccountAmount.newBuilder().amount(i).build());
        }
        transferList.accountAmounts(accountAmounts);
        return txRecordBuilder().transferList(transferList).build();
    }

    private TransactionRecord.Builder txRecordBuilder() {
        return TransactionRecord.newBuilder();
    }

    private TransactionBody.Builder txBuilder() {
        return TransactionBody.newBuilder();
    }

    private FeeComponents.Builder feeBuilder() {
        return FeeComponents.newBuilder().constant(FeeBuilder.FEE_MATRICES_CONST);
    }
}
