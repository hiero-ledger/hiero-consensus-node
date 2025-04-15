// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token;

import static com.hedera.node.app.service.token.AliasUtils.asKeyFromAliasOrElse;
import static com.hedera.node.app.service.token.AliasUtils.recoverAddressFromPubKey;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class AliasUtilsTest {

    private static final String SAMPLE_EVM_ADDRESS = "0x1234567890123456789012345678901234567890";
    private static final String SAMPLE_ECDSA_PUBLIC_KEY =
            "3a2103af80b90d25145da28c583359beb47b21796b2fe1a23c1511e443e7a64dfdb27d";
    private static final String SAMPLE_ED25519_KEY =
            "0a220a20aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    // Invalid key formats
    private static final String INVALID_ECDSA_KEY =
            "3a2103af80b90d25145da28c583359beb47b21796b2fe1a23c1511e443e7a64dfdb27d00"; // Too long

    private static final long TEST_SHARD = 0L;
    private static final long TEST_REALM = 0L;
    private static final String TEST_ENTITY_NUM_ALIAS = "00000000000000000000000000000000000004d2"; // 1234 in hex
    private static final String TEST_NON_ZERO_SHARD_ALIAS =
            "00000001000000000000000000000000000004d2"; // shard=1, realm=0, num=1234
    private static final String TEST_NON_ZERO_REALM_ALIAS =
            "00000000000000010000000000000000000004d2"; // shard=0, realm=1, num=1234

    @Test
    void constructorThrowsUnsupportedOperationException() {
        assertThrows(UnsupportedOperationException.class, () -> {
            try {
                var constructor = AliasUtils.class.getDeclaredConstructor();
                constructor.setAccessible(true);
                constructor.newInstance();
            } catch (Exception e) {
                if (e.getCause() instanceof UnsupportedOperationException) {
                    throw e.getCause();
                }
                throw e;
            }
        });
    }

    @Test
    void isOfEvmAddressSizeReturnsTrueForEvmAddress() {
        var evmAddress = Bytes.wrap(HexFormat.of().parseHex(SAMPLE_EVM_ADDRESS.substring(2)));
        assertTrue(AliasUtils.isOfEvmAddressSize(evmAddress));
    }

    @Test
    void isOfEvmAddressSizeReturnsFalseForNonEvmAddress() {
        // An EVM address is _derived from_ the public key, but is not equal to it
        var nonEvmAddress = Bytes.wrap(HexFormat.of().parseHex(SAMPLE_ECDSA_PUBLIC_KEY));
        assertFalse(AliasUtils.isOfEvmAddressSize(nonEvmAddress));
    }

    @Test
    void extractEvmAddressReturnsInputWhenEvmAddress() {
        var evmAddress = Bytes.wrap(HexFormat.of().parseHex(SAMPLE_EVM_ADDRESS.substring(2)));
        var result = AliasUtils.extractEvmAddress(evmAddress);
        assertEquals(evmAddress, result);
    }

    @Test
    void extractEvmAddressReturnsAddressFromEcdsaKey() {
        var ecdsaKeyAlias = Bytes.wrap(HexFormat.of().parseHex(SAMPLE_ECDSA_PUBLIC_KEY));
        var result = AliasUtils.extractEvmAddress(ecdsaKeyAlias);
        assertNotNull(result);
        assertEquals(20, result.length());
    }

    @Test
    void extractEvmAddressReturnsNullForEd25519Key() {
        var ed25519KeyAlias = Bytes.wrap(HexFormat.of().parseHex(SAMPLE_ED25519_KEY));
        var result = AliasUtils.extractEvmAddress(ed25519KeyAlias);
        assertNull(result);
    }

    @Test
    void extractEvmAddressReturnsAddress() {
        var ecdsaKeyAlias = Bytes.wrap(HexFormat.of().parseHex(SAMPLE_ECDSA_PUBLIC_KEY));

        final var key = asKeyFromAliasOrElse(ecdsaKeyAlias, null);
        final var expectedAddress = recoverAddressFromPubKey(key.ecdsaSecp256k1OrThrow());

        var result = AliasUtils.extractEvmAddress(key);
        assertEquals(expectedAddress, result);
    }

    @Test
    void extractEvmAddressWithNull() {
        var result = AliasUtils.extractEvmAddress((Key) null);
        assertNull(result);
    }

    @Test
    void isEntityNumAliasReturnsTrueForZeroShardRealm() {
        var entityNumAlias = Bytes.wrap(HexFormat.of().parseHex(TEST_ENTITY_NUM_ALIAS));
        assertTrue(AliasUtils.isEntityNumAlias(entityNumAlias, TEST_SHARD, TEST_REALM));
    }

    @Test
    void isEntityNumAliasReturnsFalseForNonZeroShard() {
        var nonZeroShardAlias = Bytes.wrap(HexFormat.of().parseHex(TEST_NON_ZERO_SHARD_ALIAS));
        assertFalse(AliasUtils.isEntityNumAlias(nonZeroShardAlias, TEST_SHARD, TEST_REALM));
    }

    @Test
    void isEntityNumAliasReturnsFalseForNonZeroRealm() {
        var nonZeroRealmAlias = Bytes.wrap(HexFormat.of().parseHex(TEST_NON_ZERO_REALM_ALIAS));
        assertFalse(AliasUtils.isEntityNumAlias(nonZeroRealmAlias, TEST_SHARD, TEST_REALM));
    }

    @Test
    void isEntityNumAliasReturnsTrueForMatchingShardRealm() {
        var entityNumAlias = Bytes.wrap(HexFormat.of().parseHex(TEST_NON_ZERO_SHARD_ALIAS));
        assertTrue(AliasUtils.isEntityNumAlias(entityNumAlias, 1L, TEST_REALM));
    }

    @Test
    void isKeyAliasReturnsTrueForValidEcdsaKey() {
        var ecdsaKeyAlias = Bytes.wrap(HexFormat.of().parseHex(SAMPLE_ECDSA_PUBLIC_KEY));
        assertTrue(AliasUtils.isKeyAlias(ecdsaKeyAlias));
    }

    @Test
    void isKeyAliasReturnsFalseForInvalidEcdsaKey() {
        var invalidEcdsaKeyAlias = Bytes.wrap(HexFormat.of().parseHex(INVALID_ECDSA_KEY));
        assertFalse(AliasUtils.isKeyAlias(invalidEcdsaKeyAlias));
    }

    @Test
    void isKeyAliasReturnsFalseForEvmAddress() {
        var evmAddress = Bytes.wrap(HexFormat.of().parseHex(SAMPLE_EVM_ADDRESS.substring(2)));
        assertFalse(AliasUtils.isKeyAlias(evmAddress));
    }

    @Test
    void isSerializedProtoKeyReturnsTrueForValidEcdsaKey() {
        var ecdsaKeyAlias = Bytes.wrap(HexFormat.of().parseHex(SAMPLE_ECDSA_PUBLIC_KEY));
        assertTrue(AliasUtils.isSerializedProtoKey(ecdsaKeyAlias));
    }

    @Test
    void isSerializedProtoKeyReturnsFalseForInvalidEcdsaKey() {
        var invalidEcdsaKeyAlias = Bytes.wrap(HexFormat.of().parseHex(INVALID_ECDSA_KEY));
        assertFalse(AliasUtils.isSerializedProtoKey(invalidEcdsaKeyAlias));
    }

    @Test
    void isSerializedProtoKeyReturnsFalseForEvmAddress() {
        var evmAddress = Bytes.wrap(HexFormat.of().parseHex(SAMPLE_EVM_ADDRESS.substring(2)));
        assertFalse(AliasUtils.isSerializedProtoKey(evmAddress));
    }

    @Test
    void extractShardFromAddressAliasReturnsCorrectValue() {
        var alias = Bytes.wrap(HexFormat.of().parseHex(TEST_NON_ZERO_SHARD_ALIAS));
        assertEquals(1, AliasUtils.extractShardFromAddressAlias(alias));
    }

    @Test
    void extractShardFromAddressAliasReturnsZeroForZeroShard() {
        var alias = Bytes.wrap(HexFormat.of().parseHex(TEST_ENTITY_NUM_ALIAS));
        assertEquals(0, AliasUtils.extractShardFromAddressAlias(alias));
    }

    @Test
    void extractRealmFromAddressAliasReturnsZeroForZeroRealm() {
        var alias = Bytes.wrap(HexFormat.of().parseHex(TEST_ENTITY_NUM_ALIAS));
        assertEquals(0L, AliasUtils.extractRealmFromAddressAlias(alias));
    }

    @Test
    void extractIdFromAddressAliasReturnsCorrectValue() {
        var alias = Bytes.wrap(HexFormat.of().parseHex(TEST_ENTITY_NUM_ALIAS));
        assertEquals(1234L, AliasUtils.extractIdFromAddressAlias(alias));
    }

    @Test
    void extractIdFromAddressAliasReturnsCorrectValueForNonZeroShardRealm() {
        var alias = Bytes.wrap(HexFormat.of().parseHex(TEST_NON_ZERO_SHARD_ALIAS));
        assertEquals(1234L, AliasUtils.extractIdFromAddressAlias(alias));
    }

    @Test
    void isAliasReturnsTrueForAccountIdWithAlias() {
        var alias = Bytes.wrap(HexFormat.of().parseHex(SAMPLE_EVM_ADDRESS.substring(2)));
        var accountId = AccountID.newBuilder().alias(alias).build();
        assertTrue(AliasUtils.isAlias(accountId));
    }

    @Test
    void isAliasReturnsFalseForAccountIdWithAccountNum() {
        var accountId = AccountID.newBuilder().accountNum(1234L).build();
        assertFalse(AliasUtils.isAlias(accountId));
    }

    @Test
    void isAliasReturnsTrueForAccountIdWithEvmAddressAlias() {
        var evmAddress = Bytes.wrap(HexFormat.of().parseHex(SAMPLE_EVM_ADDRESS.substring(2)));
        var accountId = AccountID.newBuilder().alias(evmAddress).build();
        assertTrue(AliasUtils.isAlias(accountId));
    }

    @Test
    void asKeyFromAliasReturnsKeyForValidEcdsaKey() {
        var ecdsaKeyAlias = Bytes.wrap(HexFormat.of().parseHex(SAMPLE_ECDSA_PUBLIC_KEY));
        var expectedKey = asKeyFromAliasOrElse(ecdsaKeyAlias, null);
        var key = AliasUtils.asKeyFromAlias(ecdsaKeyAlias);
        assertNotNull(key);
        assertTrue(key.hasEcdsaSecp256k1());
        assertEquals(expectedKey.ecdsaSecp256k1OrThrow(), key.ecdsaSecp256k1OrThrow());
    }

    @Test
    void asKeyFromAliasThrowsHandleExceptionForInvalidEcdsaKey() {
        var invalidEcdsaKeyAlias = Bytes.wrap(HexFormat.of().parseHex(INVALID_ECDSA_KEY));
        var exception = assertThrows(HandleException.class, () -> AliasUtils.asKeyFromAlias(invalidEcdsaKeyAlias));
        assertEquals(ResponseCodeEnum.INVALID_ALIAS_KEY, exception.getStatus());
    }

    @Test
    void asKeyFromAliasThrowsHandleExceptionForEvmAddress() {
        var evmAddress = Bytes.wrap(HexFormat.of().parseHex(SAMPLE_EVM_ADDRESS.substring(2)));
        var exception = assertThrows(HandleException.class, () -> AliasUtils.asKeyFromAlias(evmAddress));
        assertEquals(ResponseCodeEnum.INVALID_ALIAS_KEY, exception.getStatus());
    }

    @Test
    void asKeyFromAliasPreCheckReturnsKeyForValidEcdsaKey() throws PreCheckException {
        var ecdsaKeyAlias = Bytes.wrap(HexFormat.of().parseHex(SAMPLE_ECDSA_PUBLIC_KEY));
        var expectedKey = asKeyFromAliasOrElse(ecdsaKeyAlias, null);
        var key = AliasUtils.asKeyFromAliasPreCheck(ecdsaKeyAlias);
        assertNotNull(key);
        assertTrue(key.hasEcdsaSecp256k1());
        assertEquals(expectedKey.ecdsaSecp256k1OrThrow(), key.ecdsaSecp256k1OrThrow());
    }

    @Test
    void asKeyFromAliasPreCheckThrowsPreCheckExceptionForInvalidEcdsaKey() {
        var invalidEcdsaKeyAlias = Bytes.wrap(HexFormat.of().parseHex(INVALID_ECDSA_KEY));
        var exception =
                assertThrows(PreCheckException.class, () -> AliasUtils.asKeyFromAliasPreCheck(invalidEcdsaKeyAlias));
        assertEquals(ResponseCodeEnum.INVALID_ALIAS_KEY, exception.responseCode());
    }

    @Test
    void asKeyFromAliasPreCheckThrowsPreCheckExceptionForEvmAddress() {
        var evmAddress = Bytes.wrap(HexFormat.of().parseHex(SAMPLE_EVM_ADDRESS.substring(2)));
        var exception = assertThrows(PreCheckException.class, () -> AliasUtils.asKeyFromAliasPreCheck(evmAddress));
        assertEquals(ResponseCodeEnum.INVALID_ALIAS_KEY, exception.responseCode());
    }
}
