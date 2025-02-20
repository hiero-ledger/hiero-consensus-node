// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.fee;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hederahashgraph.api.proto.java.AccountAmount;
import com.hederahashgraph.api.proto.java.CryptoCreateTransactionBody;
import com.hederahashgraph.api.proto.java.CryptoDeleteTransactionBody;
import com.hederahashgraph.api.proto.java.Duration;
import com.hederahashgraph.api.proto.java.FeeComponents;
import com.hederahashgraph.api.proto.java.FeeData;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.ResponseType;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransactionRecord;
import com.hederahashgraph.api.proto.java.TransferList;
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
        final var networkFee = feeBuilder().setBpt(154L).setVpt(2L).setRbh(3L).build();
        final var nodeFee =
                feeBuilder().setBpt(154L).setVpt(1L).setBpr(FeeBuilder.INT_SIZE).build();
        final var serviceFee = feeBuilder().setRbh(6L);

        final var defaultCryptoCreate = CryptoCreateTransactionBody.getDefaultInstance();
        var feeData = CryptoFeeBuilder.getCryptoCreateTxFeeMatrices(
                txBuilder().setCryptoCreateAccount(defaultCryptoCreate).build(), sigValueObj);
        assertEquals(networkFee, feeData.getNetworkdata());
        assertEquals(nodeFee, feeData.getNodedata());
        assertEquals(serviceFee.build(), feeData.getServicedata());

        final var cryptoCreate = CryptoCreateTransactionBody.newBuilder()
                .setAutoRenewPeriod(Duration.newBuilder().setSeconds(1000L))
                .setNewRealmAdminKey(Key.getDefaultInstance());
        feeData = CryptoFeeBuilder.getCryptoCreateTxFeeMatrices(
                txBuilder().setCryptoCreateAccount(cryptoCreate).build(), sigValueObj);
        assertEquals(networkFee, feeData.getNetworkdata());
        assertEquals(nodeFee, feeData.getNodedata());
        assertEquals(serviceFee.setRbh(25L).build(), feeData.getServicedata());
    }

    @Test
    void getsCorrectCryptoDeleteTxFeeMatrices() {
        final var sigValueObj = new SigValueObj(5, 3, 20);
        final var networkFee = feeBuilder().setBpt(144L).setVpt(5L).setRbh(1L).build();
        final var nodeFee =
                feeBuilder().setBpt(144L).setVpt(3L).setBpr(FeeBuilder.INT_SIZE).build();
        final var serviceFee = feeBuilder().setRbh(6L).build();

        final var defaultCryptoDelete = CryptoDeleteTransactionBody.getDefaultInstance();
        var feeData = subject.getCryptoDeleteTxFeeMatrices(
                txBuilder().setCryptoDelete(defaultCryptoDelete).build(), sigValueObj);
        assertEquals(networkFee, feeData.getNetworkdata());
        assertEquals(nodeFee, feeData.getNodedata());
        assertEquals(serviceFee, feeData.getServicedata());
    }

    @Test
    void getsCorrectCostTransactionRecordQueryFeeMatrices() {
        assertEquals(FeeData.getDefaultInstance(), CryptoFeeBuilder.getCostTransactionRecordQueryFeeMatrices());
    }

    @Test
    void getsCorrectTransactionRecordQueryFeeMatrices() {
        assertEquals(
                FeeData.getDefaultInstance(),
                subject.getTransactionRecordQueryFeeMatrices(null, ResponseType.COST_ANSWER));

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
        assertEquals(FeeData.getDefaultInstance(), CryptoFeeBuilder.getCostCryptoAccountRecordsQueryFeeMatrices());
    }

    @Test
    void getsCorrectCostCryptoAccountInfoQueryFeeMatrices() {
        assertEquals(FeeData.getDefaultInstance(), CryptoFeeBuilder.getCostCryptoAccountInfoQueryFeeMatrices());
    }

    private void assertQueryFee(final FeeData feeData, final long expectedBpr) {
        final var expectedBpt = FeeBuilder.BASIC_QUERY_HEADER + FeeBuilder.BASIC_TX_ID_SIZE;
        final var nodeFee = feeBuilder().setBpt(expectedBpt).setBpr(expectedBpr).build();

        assertEquals(FeeComponents.getDefaultInstance(), feeData.getServicedata());
        assertEquals(FeeComponents.getDefaultInstance(), feeData.getNetworkdata());
        assertEquals(nodeFee, feeData.getNodedata());
    }

    private TransactionRecord recordWithMemo() {
        return txRecordBuilder().setMemo("0123456789").build();
    }

    private TransactionRecord recordWithTransferList() {
        final var transferList = TransferList.newBuilder();
        for (int i = -5; i <= 5; i++) {
            transferList.addAccountAmounts(AccountAmount.newBuilder().setAmount(i));
        }
        return txRecordBuilder().setTransferList(transferList).build();
    }

    private TransactionRecord.Builder txRecordBuilder() {
        return TransactionRecord.newBuilder();
    }

    private TransactionBody.Builder txBuilder() {
        return TransactionBody.newBuilder();
    }

    private FeeComponents.Builder feeBuilder() {
        return FeeComponents.newBuilder().setConstant(FeeBuilder.FEE_MATRICES_CONST);
    }
}
