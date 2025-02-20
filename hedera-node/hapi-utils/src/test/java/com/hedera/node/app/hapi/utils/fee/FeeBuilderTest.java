// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.fee;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.protobuf.ByteString;
import com.hederahashgraph.api.proto.java.AccountAmount;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ContractFunctionResult;
import com.hederahashgraph.api.proto.java.CryptoTransferTransactionBody;
import com.hederahashgraph.api.proto.java.Duration;
import com.hederahashgraph.api.proto.java.ExchangeRate;
import com.hederahashgraph.api.proto.java.FeeComponents;
import com.hederahashgraph.api.proto.java.FeeData;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.KeyList;
import com.hederahashgraph.api.proto.java.SignatureMap;
import com.hederahashgraph.api.proto.java.SignaturePair;
import com.hederahashgraph.api.proto.java.SignedTransaction;
import com.hederahashgraph.api.proto.java.ThresholdKey;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransactionID;
import com.hederahashgraph.api.proto.java.TransactionRecord;
import com.hederahashgraph.api.proto.java.TransferList;
import org.junit.jupiter.api.Test;

class FeeBuilderTest {
    private final ByteString contractCallResult = ByteString.copyFromUtf8("contractCallResult");
    private final ByteString contractCreateResult = ByteString.copyFromUtf8("contractCreateResult");
    private final ByteString bloom = ByteString.copyFromUtf8("Bloom");
    private final ByteString error = ByteString.copyFromUtf8("Error");
    private final AccountID accountId = AccountID.newBuilder()
            .setShardNum(0)
            .setRealmNum(0)
            .setAccountNum(1002)
            .build();
    private final AccountID designatedNodeAccount = AccountID.newBuilder()
            .setShardNum(0)
            .setRealmNum(0)
            .setAccountNum(3)
            .build();
    private final ContractFunctionResult contractFunctionResult = ContractFunctionResult.newBuilder()
            .setContractCallResult(contractCallResult)
            .setBloom(bloom)
            .setErrorMessageBytes(error)
            .build();
    private final AccountAmount accountAmount =
            AccountAmount.newBuilder().setAccountID(accountId).setAmount(1500L).build();
    private final TransferList transferList =
            TransferList.newBuilder().addAccountAmounts(accountAmount).build();
    private final TransactionRecord.Builder transactionRecordBuilder =
            TransactionRecord.newBuilder().setMemo("memo").setTransferList(transferList);
    private final FeeComponents feeComponents = FeeComponents.newBuilder()
            .setBpr(10L)
            .setBpt(10L)
            .setGas(1234L)
            .setConstant(10L)
            .setMax(10L)
            .setMin(5L)
            .setRbh(10L)
            .setSbh(10L)
            .setSbpr(10L)
            .setTv(10L)
            .setVpt(10L)
            .build();
    private final FeeData feeData = FeeData.newBuilder()
            .setNetworkdata(feeComponents)
            .setNodedata(feeComponents)
            .setServicedata(feeComponents)
            .build();
    private final FeeData feeMatrices = FeeData.newBuilder()
            .setNetworkdata(feeComponents)
            .setNodedata(feeComponents)
            .setServicedata(feeComponents)
            .build();
    private final Duration transactionDuration =
            Duration.newBuilder().setSeconds(30L).build();
    private final TransactionID transactionId =
            TransactionID.newBuilder().setAccountID(accountId).build();
    private final ByteString bodyBytes = TransactionBody.newBuilder()
            .setMemo("memo signed tx")
            .setTransactionValidDuration(transactionDuration)
            .setNodeAccountID(designatedNodeAccount)
            .setTransactionID(transactionId)
            .build()
            .toByteString();
    private final ByteString CANONICAL_SIG =
            ByteString.copyFromUtf8("0123456789012345678901234567890123456789012345678901234567890123");
    private final SignaturePair signPair =
            SignaturePair.newBuilder().setEd25519(CANONICAL_SIG).build();
    private final SignatureMap signatureMap =
            SignatureMap.newBuilder().addSigPair(signPair).build();
    private final Transaction signedTxn = Transaction.newBuilder()
            .setSignedTransactionBytes(SignedTransaction.newBuilder()
                    .setSigMap(signatureMap)
                    .setBodyBytes(bodyBytes)
                    .build()
                    .toByteString())
            .build();
    private final ExchangeRate exchangeRate =
            ExchangeRate.newBuilder().setHbarEquiv(1000).setCentEquiv(100).build();

    @Test
    void assertCalculateBPT() {
        assertEquals(236, FeeBuilder.calculateBpt());
    }

    @Test
    void assertGetTinybarsFromTinyCents() {
        var exchangeRate =
                ExchangeRate.newBuilder().setCentEquiv(10).setHbarEquiv(100).build();
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
                .setContractCallResult(contractFunctionResult)
                .build();
        assertEquals(220, FeeBuilder.getTransactionRecordSize(transactionRecord));
    }

    @Test
    void assertGetTransactionRecordSizeContractCreate() {
        var contractFunctionResult = ContractFunctionResult.newBuilder()
                .setContractCallResult(contractCreateResult)
                .setBloom(bloom)
                .setErrorMessageBytes(error)
                .build();
        var transactionRecord = transactionRecordBuilder
                .setContractCreateResult(contractFunctionResult)
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
                .setContractCallResult(contractFunctionResult)
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
                .setBpr(10L)
                .setBpt(10L)
                .setGas(1234L)
                .setConstant(10L)
                .setMax(2000000L)
                .setMin(1600000L)
                .setRbh(10L)
                .setSbh(10L)
                .setSbpr(10L)
                .setTv(10L)
                .setVpt(10L)
                .build();
        var result = FeeBuilder.getComponentFeeInTinyCents(feeComponents, feeComponents);
        assertEquals(1600, result);
    }

    @Test
    void assertGetBaseTransactionRecordSize() {
        var cryptoTransfer = CryptoTransferTransactionBody.newBuilder()
                .setTransfers(transferList)
                .build();
        var txBody = TransactionBody.newBuilder()
                .setMemo("memotx")
                .setCryptoTransfer(cryptoTransfer)
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
                .setSignedTransactionBytes(ByteString.copyFromUtf8("Wrong value"))
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
                .setSignedTransactionBytes(ByteString.copyFromUtf8("Wrong value"))
                .build();
        assertEquals(0, FeeBuilder.getSignatureSize(signedTxn));
    }

    @Test
    void assertCalculateKeysMetadata() {
        int[] countKeyMetatData = {0, 0};
        Key validKey = Key.newBuilder()
                .setEd25519(
                        ByteString.copyFromUtf8(
                                "a479462fba67674b5a41acfb16cb6828626b61d3f389fa611005a45754130e5c749073c0b1b791596430f4a54649cc8a3f6d28147dd4099070a5c3c4811d1771"))
                .build();

        Key validKey1 = Key.newBuilder()
                .setEd25519(
                        ByteString.copyFromUtf8(
                                "a479462fba67674b5a41acfb16cb6828626b61d3f389fa611005a45754130e5c749073c0b1b791596430f4a54649cc8a3f6d28147dd4099070a5c3c4811d1771"))
                .build();
        Key validED25519Keys = Key.newBuilder()
                .setKeyList(KeyList.newBuilder()
                        .addKeys(validKey)
                        .addKeys(validKey1)
                        .build())
                .build();
        assertEquals(
                countKeyMetatData.length, FeeBuilder.calculateKeysMetadata(validED25519Keys, countKeyMetatData).length);
        assertEquals(4, FeeBuilder.calculateKeysMetadata(validED25519Keys, countKeyMetatData)[0]);
    }

    @Test
    void assertCalculateKeysMetadataThresholdKey() {
        int[] countKeyMetatData = {0, 0};
        KeyList thresholdKeyList = KeyList.newBuilder()
                .addKeys(Key.newBuilder()
                        .setEd25519(ByteString.copyFromUtf8("aaaaaaaa"))
                        .build())
                .addKeys(Key.newBuilder()
                        .setEd25519(ByteString.copyFromUtf8("bbbbbbbbbbbbbbbbbbbbb"))
                        .build())
                .build();
        ThresholdKey thresholdKey = ThresholdKey.newBuilder()
                .setKeys(thresholdKeyList)
                .setThreshold(2)
                .build();
        Key validED25519Keys = Key.newBuilder().setThresholdKey(thresholdKey).build();
        assertEquals(
                countKeyMetatData.length, FeeBuilder.calculateKeysMetadata(validED25519Keys, countKeyMetatData).length);
        assertEquals(2, FeeBuilder.calculateKeysMetadata(validED25519Keys, countKeyMetatData)[1]);
    }
}
