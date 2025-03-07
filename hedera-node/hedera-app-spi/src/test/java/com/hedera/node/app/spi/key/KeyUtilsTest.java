// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.key;

import static com.hedera.node.app.hapi.utils.keys.Secp256k1Utils.ECDSA_SECP256K1_COMPRESSED_KEY_LENGTH;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.KeyList;
import com.hedera.hapi.node.base.ThresholdKey;
import com.hedera.node.app.hapi.utils.keys.KeyUtils;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.UUID;
import org.apache.commons.lang3.ArrayUtils;
import org.junit.jupiter.api.Test;

public class KeyUtilsTest {
    @Test
    void returnsEmptyIfKeyIsNull() {
        assertTrue(KeyUtils.isEmpty(null));
        assertFalse(KeyUtils.isValid(null));
    }

    @Test
    void checksEmptyKeys() {
        assertTrue(KeyUtils.isEmpty(Key.DEFAULT));
        assertTrue(KeyUtils.isEmpty(Key.newBuilder().keyList(KeyList.DEFAULT).build()));
        assertTrue(KeyUtils.isEmpty(
                Key.newBuilder().keyList(KeyList.newBuilder().keys().build()).build()));
        assertTrue(KeyUtils.isEmpty(
                Key.newBuilder().thresholdKey(ThresholdKey.DEFAULT).build()));
        assertTrue(KeyUtils.isEmpty(Key.newBuilder()
                .thresholdKey(ThresholdKey.newBuilder().threshold(1))
                .keyList(KeyList.DEFAULT)
                .build()));
        assertTrue(KeyUtils.isEmpty(Key.newBuilder()
                .thresholdKey(ThresholdKey.newBuilder().threshold(1))
                .keyList(KeyList.newBuilder().keys().build())
                .build()));
        assertTrue(KeyUtils.isEmpty(Key.newBuilder().ed25519(Bytes.EMPTY).build()));
        assertTrue(KeyUtils.isEmpty(Key.newBuilder().ecdsaSecp256k1(Bytes.EMPTY).build()));
        assertTrue(
                KeyUtils.isEmpty(Key.newBuilder().contractID(ContractID.DEFAULT).build()));
        assertTrue(KeyUtils.isEmpty(
                Key.newBuilder().delegatableContractId(ContractID.DEFAULT).build()));
        assertTrue(
                KeyUtils.isEmpty(Key.newBuilder().contractID(ContractID.DEFAULT).build()));
        assertTrue(KeyUtils.isEmpty(
                Key.newBuilder().delegatableContractId(ContractID.DEFAULT).build()));
    }

    @Test
    void returnInvalidIfEmpty() {
        assertFalse(KeyUtils.isValid(Key.DEFAULT));
        assertFalse(KeyUtils.isValid(Key.newBuilder().keyList(KeyList.DEFAULT).build()));
        assertFalse(KeyUtils.isValid(
                Key.newBuilder().keyList(KeyList.newBuilder().keys().build()).build()));
        assertFalse(KeyUtils.isValid(
                Key.newBuilder().thresholdKey(ThresholdKey.DEFAULT).build()));
        assertFalse(KeyUtils.isValid(Key.newBuilder()
                .thresholdKey(ThresholdKey.newBuilder().threshold(1))
                .keyList(KeyList.DEFAULT)
                .build()));
        assertFalse(KeyUtils.isValid(Key.newBuilder()
                .thresholdKey(ThresholdKey.newBuilder().threshold(1))
                .keyList(KeyList.newBuilder().keys().build())
                .build()));
        assertFalse(KeyUtils.isValid(Key.newBuilder().ed25519(Bytes.EMPTY).build()));
        assertFalse(
                KeyUtils.isValid(Key.newBuilder().ecdsaSecp256k1(Bytes.EMPTY).build()));
        assertFalse(
                KeyUtils.isValid(Key.newBuilder().contractID(ContractID.DEFAULT).build()));
        assertFalse(KeyUtils.isValid(
                Key.newBuilder().delegatableContractId(ContractID.DEFAULT).build()));
        assertFalse(
                KeyUtils.isValid(Key.newBuilder().contractID(ContractID.DEFAULT).build()));
        assertFalse(KeyUtils.isValid(
                Key.newBuilder().delegatableContractId(ContractID.DEFAULT).build()));
    }

    @Test
    void extraValidationsForInvalidKeys() {
        assertFalse(KeyUtils.isValid(Key.newBuilder()
                .keyList(KeyList.newBuilder()
                        .keys(Key.newBuilder()
                                .ed25519(Bytes.wrap("test".getBytes()))
                                .build())
                        .build())
                .build()));
        assertFalse(KeyUtils.isValid(Key.newBuilder()
                .thresholdKey(ThresholdKey.newBuilder()
                        .threshold(2)
                        .keys(KeyList.newBuilder()
                                .keys(Key.newBuilder()
                                        .ed25519(Bytes.wrap("test".getBytes()))
                                        .build())
                                .build())
                        .build())
                .build()));
        assertFalse(KeyUtils.isValid(Key.newBuilder()
                .thresholdKey(ThresholdKey.newBuilder()
                        .threshold(1)
                        .keys(KeyList.newBuilder()
                                .keys(Key.newBuilder()
                                        .ed25519(Bytes.wrap("test".getBytes()))
                                        .build())
                                .build())
                        .build())
                .build()));
        assertFalse(KeyUtils.isValid(
                Key.newBuilder().ed25519(Bytes.wrap("test".getBytes())).build()));
        assertFalse(KeyUtils.isValid(
                Key.newBuilder().ecdsaSecp256k1(Bytes.wrap("0x02".getBytes())).build()));
        assertFalse(KeyUtils.isValid(Key.newBuilder()
                .contractID(ContractID.newBuilder().contractNum(-1).build())
                .build()));
        assertFalse(KeyUtils.isValid(Key.newBuilder()
                .delegatableContractId(ContractID.newBuilder().contractNum(-1).build())
                .build()));
    }

    @Test
    void checksValidKeys() {
        assertTrue(KeyUtils.isValid(Key.newBuilder()
                .keyList(KeyList.newBuilder()
                        .keys(Key.newBuilder()
                                .ed25519(Bytes.wrap("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa".getBytes()))
                                .build())
                        .build())
                .build()));
        assertTrue(KeyUtils.isValid(Key.newBuilder()
                .thresholdKey(ThresholdKey.newBuilder()
                        .threshold(1)
                        .keys(KeyList.newBuilder()
                                .keys(Key.newBuilder()
                                        .ed25519(Bytes.wrap("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa".getBytes()))
                                        .build())
                                .build())
                        .build())
                .build()));
        assertTrue(KeyUtils.isValid(Key.newBuilder()
                .ed25519(Bytes.wrap("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa".getBytes()))
                .build()));
        assertTrue(KeyUtils.isValid(Key.newBuilder()
                .ecdsaSecp256k1(Bytes.wrap(randomValidECDSASecp256K1Key()))
                .build()));
        assertTrue(KeyUtils.isValid(Key.newBuilder()
                .contractID(ContractID.newBuilder().contractNum(1).build())
                .build()));
        assertTrue(KeyUtils.isValid(Key.newBuilder()
                .delegatableContractId(ContractID.newBuilder().contractNum(1000).build())
                .build()));
    }

    public static byte[] randomValidECDSASecp256K1Key() {
        final var firstByte = new byte[] {0x02};
        final var randomBytes = randomUtf8Bytes(ECDSA_SECP256K1_COMPRESSED_KEY_LENGTH - 1);
        return ArrayUtils.addAll(firstByte, randomBytes);
    }

    public static byte[] randomUtf8Bytes(int n) {
        byte[] data = new byte[n];
        int i = 0;
        while (i < n) {
            byte[] rnd = UUID.randomUUID().toString().getBytes();
            System.arraycopy(rnd, 0, data, i, Math.min(rnd.length, n - 1 - i));
            i += rnd.length;
        }
        return data;
    }
}
