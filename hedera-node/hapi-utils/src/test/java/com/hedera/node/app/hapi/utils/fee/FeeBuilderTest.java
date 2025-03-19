// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.fee;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Duration;
import com.hedera.hapi.node.base.FeeComponents;
import com.hedera.hapi.node.base.FeeData;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.KeyList;
import com.hedera.hapi.node.base.SignatureMap;
import com.hedera.hapi.node.base.SignaturePair;
import com.hedera.hapi.node.base.ThresholdKey;
import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.base.TransferList;
import com.hedera.hapi.node.contract.ContractFunctionResult;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.hapi.node.transaction.ExchangeRate;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.node.transaction.TransactionRecord;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import org.junit.jupiter.api.Test;

class FeeBuilderTest {
    private final Bytes contractCallResult = Bytes.wrap("contractCallResult");
    private final Bytes contractCreateResult = Bytes.wrap("contractCreateResult");
    private final Bytes bloom = Bytes.wrap("Bloom");
    private final Bytes error = Bytes.wrap("Error");
    private final AccountID accountId =
            AccountID.newBuilder().shardNum(0).realmNum(0).accountNum(1002).build();
    private final AccountID designatedNodeAccount =
            AccountID.newBuilder().shardNum(0).realmNum(0).accountNum(3).build();
    private final ContractFunctionResult contractFunctionResult = ContractFunctionResult.newBuilder()
            .contractCallResult(contractCallResult)
            .bloom(bloom)
            .errorMessage(error.asUtf8String())
            .build();
    private final AccountAmount accountAmount =
            AccountAmount.newBuilder().accountID(accountId).amount(1500L).build();
    private final TransferList transferList =
            TransferList.newBuilder().accountAmounts(accountAmount).build();
    private final TransactionRecord.Builder transactionRecordBuilder =
            TransactionRecord.newBuilder().memo("memo").transferList(transferList);
    private final FeeComponents feeComponents = FeeComponents.newBuilder()
            .bpr(10L)
            .bpt(10L)
            .gas(1234L)
            .constant(10L)
            .max(10L)
            .min(5L)
            .rbh(10L)
            .sbh(10L)
            .sbpr(10L)
            .tv(10L)
            .vpt(10L)
            .build();
    private final FeeData feeData = FeeData.newBuilder()
            .networkdata(feeComponents)
            .nodedata(feeComponents)
            .servicedata(feeComponents)
            .build();
    private final FeeData feeMatrices = FeeData.newBuilder()
            .networkdata(feeComponents)
            .nodedata(feeComponents)
            .servicedata(feeComponents)
            .build();
    private final Duration transactionDuration =
            Duration.newBuilder().seconds(30L).build();
    private final TransactionID transactionId =
            TransactionID.newBuilder().accountID(accountId).build();
    private final Bytes bodyBytes = TransactionBody.PROTOBUF.toBytes(TransactionBody.newBuilder()
            .memo("memo signed tx")
            .transactionValidDuration(transactionDuration)
            .nodeAccountID(designatedNodeAccount)
            .transactionID(transactionId)
            .build());
    private final Bytes CANONICAL_SIG = Bytes.wrap("0123456789012345678901234567890123456789012345678901234567890123");
    private final SignaturePair signPair =
            SignaturePair.newBuilder().ed25519(CANONICAL_SIG).build();
    private final SignatureMap signatureMap =
            SignatureMap.newBuilder().sigPair(signPair).build();
    private final Transaction signedTxn = Transaction.newBuilder()
            .signedTransactionBytes(SignedTransaction.PROTOBUF.toBytes(SignedTransaction.newBuilder()
                    .sigMap(signatureMap)
                    .bodyBytes(bodyBytes)
                    .build()))
            .build();
    private final ExchangeRate exchangeRate =
            ExchangeRate.newBuilder().hbarEquiv(1000).centEquiv(100).build();

    @Test
    void assertCalculateBPT() {
        assertEquals(236, FeeBuilder.calculateBpt());
    }

    @Test
    void assertGetTinybarsFromTinyCents() {
        var exchangeRate =
                ExchangeRate.newBuilder().centEquiv(10).hbarEquiv(100).build();
        assertEquals(100, FeeBuilder.getTinybarsFromTinyCents(exchangeRate, 10));
    }

    @Test
    void assertGetContractFunctionSize() {
        assertEquals(52, FeeBuilder.getContractFunctionSize(contractFunctionResult));
    }

    @Test
    void assertGetHoursFromSecWillReturnsZero() {
        assertEquals(0, FeeBuilder.getHoursFromSec(0));
    }

    @Test
    void assertGetHoursFromSecWillReturnsNonZero() {
        assertEquals(2, FeeBuilder.getHoursFromSec(7200));
    }

    @Test
    void assertGetTransactionRecordSizeContractCall() {
        var transactionRecord = transactionRecordBuilder
                .contractCallResult(contractFunctionResult)
                .build();
        assertEquals(220, FeeBuilder.getTransactionRecordSize(transactionRecord));
    }

    @Test
    void assertGetTransactionRecordSizeContractCreate() {
        var contractFunctionResult = ContractFunctionResult.newBuilder()
                .contractCallResult(contractCreateResult)
                .bloom(bloom)
                .errorMessage(error.asUtf8String())
                .build();
        var transactionRecord = transactionRecordBuilder
                .contractCreateResult(contractFunctionResult)
                .build();

        assertEquals(222, FeeBuilder.getTransactionRecordSize(transactionRecord));
    }

    @Test
    void assertGetTransactionRecordSizeWhenTxRecordIsNull() {
        assertEquals(0, FeeBuilder.getTransactionRecordSize(null));
    }

    @Test
    void assertGetTxRecordUsageRBH() {
        var transactionRecord = transactionRecordBuilder
                .contractCallResult(contractFunctionResult)
                .build();

        assertEquals(220, FeeBuilder.getTxRecordUsageRbh(transactionRecord, 100));
    }

    @Test
    void assertGetTxRecordUsageRBHWhenTxRecordIsNull() {
        assertEquals(0, FeeBuilder.getTxRecordUsageRbh(null, 100));
    }

    @Test
    void assertGetFeeObjectWithMultiplier() {
        var result = FeeBuilder.getFeeObject(feeData, feeMatrices, exchangeRate, 10);
        assertEquals(100, result.nodeFee());
        assertEquals(100, result.networkFee());
        assertEquals(100, result.serviceFee());
    }

    @Test
    void assertGetFeeObject() {
        var result = FeeBuilder.getFeeObject(feeData, feeMatrices, exchangeRate);
        assertEquals(10, result.nodeFee());
        assertEquals(10, result.networkFee());
        assertEquals(10, result.serviceFee());
    }

    @Test
    void assertGetTotalFeeforRequest() {
        var result = FeeBuilder.getTotalFeeforRequest(feeData, feeMatrices, exchangeRate);
        assertEquals(30, result);
    }

    @Test
    void assertGetComponentFeeInTinyCents() {
        var feeComponents = FeeComponents.newBuilder()
                .bpr(10L)
                .bpt(10L)
                .gas(1234L)
                .constant(10L)
                .max(2000000L)
                .min(1600000L)
                .rbh(10L)
                .sbh(10L)
                .sbpr(10L)
                .tv(10L)
                .vpt(10L)
                .build();
        var result = FeeBuilder.getComponentFeeInTinyCents(feeComponents, feeComponents);
        assertEquals(1600, result);
    }

    @Test
    void assertGetBaseTransactionRecordSize() {
        var cryptoTransfer = CryptoTransferTransactionBody.newBuilder()
                .transfers(transferList)
                .build();
        var txBody = TransactionBody.newBuilder()
                .memo("memotx")
                .cryptoTransfer(cryptoTransfer)
                .build();
        var result = FeeBuilder.getBaseTransactionRecordSize(txBody);
        assertEquals(170, result);
    }

    @Test
    void assertGetSignatureCount() {
        assertEquals(1, FeeBuilder.getSignatureCount(signedTxn));
    }

    @Test
    void assertGetSignatureCountReturnsZero() {
        Transaction signedTxn = Transaction.newBuilder()
                .signedTransactionBytes(Bytes.wrap("Wrong value"))
                .build();
        assertEquals(0, FeeBuilder.getSignatureCount(signedTxn));
    }

    @Test
    void assertGetSignatureSize() {
        assertEquals(68, FeeBuilder.getSignatureSize(signedTxn));
    }

    @Test
    void assertGetSignatureSizeReturnsZero() {
        Transaction signedTxn = Transaction.newBuilder()
                .signedTransactionBytes(Bytes.wrap("Wrong value"))
                .build();
        assertEquals(0, FeeBuilder.getSignatureSize(signedTxn));
    }

    @Test
    void assertCalculateKeysMetadata() {
        int[] countKeyMetatData = {0, 0};
        Key validKey = Key.newBuilder()
                .ed25519(
                        Bytes.wrap(
                                "a479462fba67674b5a41acfb16cb6828626b61d3f389fa611005a45754130e5c749073c0b1b791596430f4a54649cc8a3f6d28147dd4099070a5c3c4811d1771"))
                .build();

        Key validKey1 = Key.newBuilder()
                .ed25519(
                        Bytes.wrap(
                                "a479462fba67674b5a41acfb16cb6828626b61d3f389fa611005a45754130e5c749073c0b1b791596430f4a54649cc8a3f6d28147dd4099070a5c3c4811d1771"))
                .build();
        Key validED25519Keys = Key.newBuilder()
                .keyList(KeyList.newBuilder().keys(validKey, validKey1).build())
                .build();
        assertEquals(
                countKeyMetatData.length, FeeBuilder.calculateKeysMetadata(validED25519Keys, countKeyMetatData).length);
        assertEquals(4, FeeBuilder.calculateKeysMetadata(validED25519Keys, countKeyMetatData)[0]);
    }

    @Test
    void assertCalculateKeysMetadataThresholdKey() {
        int[] countKeyMetatData = {0, 0};
        KeyList thresholdKeyList = KeyList.newBuilder()
                .keys(
                        Key.newBuilder().ed25519(Bytes.wrap("aaaaaaaa")).build(),
                        Key.newBuilder()
                                .ed25519(Bytes.wrap("bbbbbbbbbbbbbbbbbbbbb"))
                                .build())
                .build();
        ThresholdKey thresholdKey =
                ThresholdKey.newBuilder().keys(thresholdKeyList).threshold(2).build();
        Key validED25519Keys = Key.newBuilder().thresholdKey(thresholdKey).build();
        assertEquals(
                countKeyMetatData.length, FeeBuilder.calculateKeysMetadata(validED25519Keys, countKeyMetatData).length);
        assertEquals(2, FeeBuilder.calculateKeysMetadata(validED25519Keys, countKeyMetatData)[1]);
    }
}
