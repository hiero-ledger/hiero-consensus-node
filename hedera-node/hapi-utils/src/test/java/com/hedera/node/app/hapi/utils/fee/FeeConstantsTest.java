// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.fee;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.protobuf.ByteString;
import com.hederahashgraph.api.proto.java.ContractFunctionResult;
import com.hederahashgraph.api.proto.java.ExchangeRate;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.KeyList;
import com.hederahashgraph.api.proto.java.SignatureMap;
import com.hederahashgraph.api.proto.java.SignaturePair;
import com.hederahashgraph.api.proto.java.SignedTransaction;
import com.hederahashgraph.api.proto.java.ThresholdKey;
import com.hederahashgraph.api.proto.java.Transaction;
import java.lang.reflect.InvocationTargetException;
import org.junit.jupiter.api.Test;

class FeeConstantsTest {

    private final Key leafKey =
            Key.newBuilder().setEd25519(ByteString.copyFromUtf8("abcd")).build();

    @Test
    void getTinybarsFromTinyCentsConverts() {
        final var exchangeRate =
                ExchangeRate.newBuilder().setHbarEquiv(1000).setCentEquiv(100).build();
        assertEquals(100, FeeConstants.getTinybarsFromTinyCents(exchangeRate, 10));
    }

    @Test
    void getContractFunctionSizeSumsComponents() {
        final var result = ContractFunctionResult.newBuilder()
                .setContractCallResult(ByteString.copyFromUtf8("contractCallResult"))
                .setBloom(ByteString.copyFromUtf8("Bloom"))
                .setErrorMessageBytes(ByteString.copyFromUtf8("Error"))
                .build();
        assertEquals(52, FeeConstants.getContractFunctionSize(result));
    }

    @Test
    void getSignatureCountReturnsPairCount() {
        final var sigMap = SignatureMap.newBuilder()
                .addSigPair(SignaturePair.newBuilder()
                        .setPubKeyPrefix(ByteString.copyFromUtf8("a"))
                        .build())
                .build();
        final var signedTxn = Transaction.newBuilder()
                .setSignedTransactionBytes(
                        SignedTransaction.newBuilder().setSigMap(sigMap).build().toByteString())
                .build();
        assertEquals(1, FeeConstants.getSignatureCount(signedTxn));
    }

    @Test
    void getSignatureCountReturnsZeroOnBadBytes() {
        final var signedTxn = Transaction.newBuilder()
                .setSignedTransactionBytes(ByteString.copyFromUtf8("Wrong value"))
                .build();
        assertEquals(0, FeeConstants.getSignatureCount(signedTxn));
    }

    @Test
    void getAccountKeyStorageSizeIsZeroForNullOrDefault() {
        assertEquals(0, FeeConstants.getAccountKeyStorageSize(null));
        assertEquals(0, FeeConstants.getAccountKeyStorageSize(Key.getDefaultInstance()));
    }

    @Test
    void getAccountKeyStorageSizeForSingleKey() {
        assertEquals(FeeConstants.KEY_SIZE, FeeConstants.getAccountKeyStorageSize(leafKey));
    }

    @Test
    void getAccountKeyStorageSizeForKeyList() {
        final var keyList = Key.newBuilder()
                .setKeyList(KeyList.newBuilder().addKeys(leafKey).addKeys(leafKey))
                .build();
        assertEquals(2 * FeeConstants.KEY_SIZE, FeeConstants.getAccountKeyStorageSize(keyList));
    }

    @Test
    void getAccountKeyStorageSizeForThresholdKey() {
        final var thresholdKey = Key.newBuilder()
                .setThresholdKey(ThresholdKey.newBuilder()
                        .setThreshold(1)
                        .setKeys(KeyList.newBuilder().addKeys(leafKey).addKeys(leafKey)))
                .build();
        assertEquals(
                2 * FeeConstants.KEY_SIZE + FeeConstants.INT_SIZE, FeeConstants.getAccountKeyStorageSize(thresholdKey));
    }

    @Test
    void constructorIsUnsupported() throws Exception {
        final var constructor = FeeConstants.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        assertThrows(InvocationTargetException.class, constructor::newInstance);
    }
}
