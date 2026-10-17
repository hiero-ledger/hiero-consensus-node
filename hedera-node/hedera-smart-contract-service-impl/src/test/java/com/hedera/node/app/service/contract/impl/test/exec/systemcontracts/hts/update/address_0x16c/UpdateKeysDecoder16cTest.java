// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.hts.update.address_0x16c;

import static com.hedera.node.app.service.contract.impl.test.TestHelpers.FUNGIBLE_TOKEN_HEADLONG_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.OWNER_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.entityIdFactory;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.lenient;

import com.esaulpaugh.headlong.abi.Address;
import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.AddressIdConverter;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.update.address_0x16c.UpdateDecoder;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.update.address_0x16c.UpdateKeysTranslator;
import java.math.BigInteger;
import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * GHSA-q776-f4m7-g642 regression tests for updateTokenKeys at 0x16c. This decoder overrides
 * decodeTokenUpdateKeys, so a fix applied only to the 0x167/common decoder does not reach it.
 */
@ExtendWith(MockitoExtension.class)
class UpdateKeysDecoder16cTest {
    private static final Address ZERO_ADDRESS = Address.wrap("0x0000000000000000000000000000000000000000");

    @Mock
    private HtsCallAttempt attempt;

    @Mock
    private AddressIdConverter addressIdConverter;

    @Mock
    private HederaNativeOperations nativeOperations;

    private final UpdateDecoder subject = new UpdateDecoder();

    @BeforeEach
    void setUp() {
        lenient().when(attempt.addressIdConverter()).thenReturn(addressIdConverter);
        lenient().when(attempt.nativeOperations()).thenReturn(nativeOperations);
        lenient().when(nativeOperations.entityIdFactory()).thenReturn(entityIdFactory);
        lenient()
                .when(addressIdConverter.convert(ZERO_ADDRESS))
                .thenReturn(AccountID.newBuilder().accountNum(0).build());
        lenient()
                .when(addressIdConverter.convert(FUNGIBLE_TOKEN_HEADLONG_ADDRESS))
                .thenReturn(OWNER_ID);
    }

    private static Tuple tokenKey(final long keyType) {
        return Tuple.of(
                BigInteger.valueOf(keyType),
                Tuple.of(Boolean.FALSE, FUNGIBLE_TOKEN_HEADLONG_ADDRESS, new byte[0], new byte[0], ZERO_ADDRESS));
    }

    private static Tuple inheritAccountKey(final long keyType) {
        return Tuple.of(
                BigInteger.valueOf(keyType),
                Tuple.of(Boolean.TRUE, ZERO_ADDRESS, new byte[0], new byte[0], ZERO_ADDRESS));
    }

    private static Tuple emptyKeyValue(final long keyType) {
        return Tuple.of(
                BigInteger.valueOf(keyType),
                Tuple.of(Boolean.FALSE, ZERO_ADDRESS, new byte[0], new byte[0], ZERO_ADDRESS));
    }

    private TransactionBody decode(final Tuple... tokenKeys) {
        final var encoded = Bytes.wrapByteBuffer(UpdateKeysTranslator.TOKEN_UPDATE_KEYS_16C.encodeCallWithArgs(
                FUNGIBLE_TOKEN_HEADLONG_ADDRESS, tokenKeys));
        lenient().when(attempt.input()).thenReturn(encoded);
        return subject.decodeTokenUpdateKeys(attempt);
    }

    @Test
    void emptyKeyListIsRejectedAt0x16c() {
        assertNull(decode());
    }

    @Test
    void zeroKeyTypeIsRejectedAt0x16c() {
        assertNull(decode(tokenKey(0)));
    }

    @Test
    void undefinedKeyTypeBitIsRejectedAt0x16c() {
        assertNull(decode(tokenKey(0x100)));
    }

    @Test
    void duplicateKeyTypesAreRejectedAt0x16c() {
        assertNull(decode(tokenKey(0x01), tokenKey(0x01)));
    }

    @Test
    void metadataKeyStillWorksAt0x16c() {
        final var body = decode(tokenKey(0x80));
        assertNotNull(body);
        assertNotNull(body.tokenUpdateOrThrow().metadataKey());
    }

    @Test
    void inheritAccountKeyOnlyEntryIsRejectedAt0x16c() {
        // inheritAccountKey is only resolved for token creation; on an update it would yield a key-less no-op
        assertNull(decode(inheritAccountKey(0x01)));
    }

    @Test
    void inheritAccountKeyAlongsideValidEntryIsRejectedAt0x16c() {
        assertNull(decode(tokenKey(0x01), inheritAccountKey(0x02)));
    }

    @Test
    void entryWithNoKeyMaterialDoesNotProduceAnUpdateAt0x16c() {
        // either rejected (null) or refused by the decoder; never a body that would dispatch an empty update
        try {
            assertNull(decode(emptyKeyValue(0x01)));
        } catch (final IllegalArgumentException expected) {
            // refused by the decoder
        }
    }
}
