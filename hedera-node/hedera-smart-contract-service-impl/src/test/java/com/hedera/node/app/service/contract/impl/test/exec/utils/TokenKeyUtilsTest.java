// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.utils;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.token.TokenCreateTransactionBody;
import com.hedera.hapi.node.token.TokenUpdateTransactionBody;
import com.hedera.node.app.service.contract.impl.exec.utils.KeyValueWrapper;
import com.hedera.node.app.service.contract.impl.exec.utils.TokenKeyUtils;
import com.hedera.node.app.service.contract.impl.exec.utils.TokenKeyWrapper;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.List;
import org.junit.jupiter.api.Test;

class TokenKeyUtilsTest {
    private static final int ADMIN = 0x01;
    private static final int KYC = 0x02;
    private static final int FREEZE = 0x04;
    private static final int WIPE = 0x08;
    private static final int SUPPLY = 0x10;
    private static final int FEE_SCHEDULE = 0x20;
    private static final int PAUSE = 0x40;
    private static final int METADATA = 0x80;

    private static final Key KEY_A = ed25519Key((byte) 1);
    private static final Key KEY_B = ed25519Key((byte) 2);

    @Test
    void emptyListIsNotAnError() {
        final var update = TokenUpdateTransactionBody.newBuilder();
        final var create = TokenCreateTransactionBody.newBuilder();

        assertNull(TokenKeyUtils.addTokenKeys(List.of(), false, update));
        assertNull(TokenKeyUtils.addTokenKeys(List.of(), true, create));

        assertEquals(TokenUpdateTransactionBody.DEFAULT, update.build());
        assertEquals(TokenCreateTransactionBody.DEFAULT, create.build());
    }

    @Test
    void appliesEveryRoleOnUpdateBuilder() {
        final var builder = TokenUpdateTransactionBody.newBuilder();

        final var status = TokenKeyUtils.addTokenKeys(
                List.of(
                        entry(ADMIN, KEY_A),
                        entry(KYC, KEY_B),
                        entry(FREEZE, KEY_A),
                        entry(WIPE, KEY_B),
                        entry(SUPPLY, KEY_A),
                        entry(FEE_SCHEDULE, KEY_B),
                        entry(PAUSE, KEY_A),
                        entry(METADATA, KEY_B)),
                true,
                builder);

        assertNull(status);
        final var body = builder.build();
        assertEquals(KEY_A, body.adminKey());
        assertEquals(KEY_B, body.kycKey());
        assertEquals(KEY_A, body.freezeKey());
        assertEquals(KEY_B, body.wipeKey());
        assertEquals(KEY_A, body.supplyKey());
        assertEquals(KEY_B, body.feeScheduleKey());
        assertEquals(KEY_A, body.pauseKey());
        assertEquals(KEY_B, body.metadataKey());
    }

    @Test
    void appliesEveryRoleOnCreateBuilder() {
        final var builder = TokenCreateTransactionBody.newBuilder();

        final var status = TokenKeyUtils.addTokenKeys(
                List.of(
                        entry(ADMIN, KEY_A),
                        entry(KYC, KEY_B),
                        entry(FREEZE, KEY_A),
                        entry(WIPE, KEY_B),
                        entry(SUPPLY, KEY_A),
                        entry(FEE_SCHEDULE, KEY_B),
                        entry(PAUSE, KEY_A),
                        entry(METADATA, KEY_B)),
                true,
                builder);

        assertNull(status);
        final var body = builder.build();
        assertEquals(KEY_A, body.adminKey());
        assertEquals(KEY_B, body.kycKey());
        assertEquals(KEY_A, body.freezeKey());
        assertEquals(KEY_B, body.wipeKey());
        assertEquals(KEY_A, body.supplyKey());
        assertEquals(KEY_B, body.feeScheduleKey());
        assertEquals(KEY_A, body.pauseKey());
        assertEquals(KEY_B, body.metadataKey());
    }

    @Test
    void oneEntryMaySetSeveralRoles() {
        final var builder = TokenUpdateTransactionBody.newBuilder();

        assertNull(TokenKeyUtils.addTokenKeys(List.of(entry(ADMIN | SUPPLY | PAUSE, KEY_A)), false, builder));

        final var body = builder.build();
        assertEquals(KEY_A, body.adminKey());
        assertEquals(KEY_A, body.supplyKey());
        assertEquals(KEY_A, body.pauseKey());
        assertFalse(body.hasKycKey());
    }

    @Test
    void lastEntryWinsForARepeatedBit() {
        final var update = TokenUpdateTransactionBody.newBuilder();
        final var create = TokenCreateTransactionBody.newBuilder();
        final var entries = List.of(entry(SUPPLY, KEY_A), entry(SUPPLY, KEY_B));

        assertNull(TokenKeyUtils.addTokenKeys(entries, false, update));
        assertNull(TokenKeyUtils.addTokenKeys(entries, false, create));

        assertEquals(KEY_B, update.build().supplyKey());
        assertEquals(KEY_B, create.build().supplyKey());
    }

    @Test
    void keyTypeZeroIsRejectedAndSetsNothing() {
        final var update = TokenUpdateTransactionBody.newBuilder();
        final var create = TokenCreateTransactionBody.newBuilder();

        assertEquals(INVALID_TRANSACTION_BODY, TokenKeyUtils.addTokenKeys(List.of(entry(0, KEY_A)), false, update));
        assertEquals(INVALID_TRANSACTION_BODY, TokenKeyUtils.addTokenKeys(List.of(entry(0, KEY_A)), true, create));

        assertEquals(TokenUpdateTransactionBody.DEFAULT, update.build());
        assertEquals(TokenCreateTransactionBody.DEFAULT, create.build());
    }

    @Test
    void onlyUndefinedBitsAreRejected() {
        for (int bit = 8; bit < 16; bit++) {
            final var entries = List.of(entry(1 << bit, KEY_A));
            assertEquals(
                    INVALID_TRANSACTION_BODY,
                    TokenKeyUtils.addTokenKeys(entries, true, TokenUpdateTransactionBody.newBuilder()),
                    "bit " + bit);
            assertEquals(
                    INVALID_TRANSACTION_BODY,
                    TokenKeyUtils.addTokenKeys(entries, false, TokenCreateTransactionBody.newBuilder()),
                    "bit " + bit);
        }
    }

    @Test
    void metadataBitIsOnlyDefinedWhenAsked() {
        final var withMetadata = TokenUpdateTransactionBody.newBuilder();
        final var withoutMetadata = TokenCreateTransactionBody.newBuilder();
        final var entries = List.of(entry(METADATA, KEY_A));

        assertNull(TokenKeyUtils.addTokenKeys(entries, true, withMetadata));
        assertEquals(INVALID_TRANSACTION_BODY, TokenKeyUtils.addTokenKeys(entries, false, withoutMetadata));

        assertEquals(KEY_A, withMetadata.build().metadataKey());
        assertFalse(withoutMetadata.build().hasMetadataKey());
    }

    @Test
    void metadataBitNextToADefinedBitIsIgnoredWithoutMetadata() {
        final var builder = TokenUpdateTransactionBody.newBuilder();

        assertNull(TokenKeyUtils.addTokenKeys(List.of(entry(SUPPLY | METADATA, KEY_A)), false, builder));

        final var body = builder.build();
        assertEquals(KEY_A, body.supplyKey());
        assertFalse(body.hasMetadataKey());
    }

    @Test
    void undefinedBitNextToADefinedBitIsIgnored() {
        final var builder = TokenCreateTransactionBody.newBuilder();

        assertNull(TokenKeyUtils.addTokenKeys(List.of(entry(SUPPLY | 0x100, KEY_A)), false, builder));

        assertEquals(KEY_A, builder.build().supplyKey());
    }

    @Test
    void rejectionIsReportedEvenWhenAnotherEntryIsValid() {
        final var builder = TokenUpdateTransactionBody.newBuilder();

        // The caller does not dispatch on a rejection, so the valid entry being applied to the builder is harmless
        assertEquals(
                INVALID_TRANSACTION_BODY,
                TokenKeyUtils.addTokenKeys(List.of(entry(SUPPLY, KEY_A), entry(0x100, KEY_B)), false, builder));
    }

    @Test
    void invalidKeyValueIsRejectedByThrowing() {
        final var invalidValue =
                new TokenKeyWrapper(SUPPLY, new KeyValueWrapper(false, null, new byte[0], new byte[0], null));

        assertThrows(
                IllegalArgumentException.class,
                () -> TokenKeyUtils.addTokenKeys(
                        List.of(invalidValue), false, TokenUpdateTransactionBody.newBuilder()));
        assertThrows(
                IllegalArgumentException.class,
                () -> TokenKeyUtils.addTokenKeys(
                        List.of(invalidValue), false, TokenCreateTransactionBody.newBuilder()));
    }

    @Test
    void selectsNoKeyOnlyWhenNoDefinedBitIsSet() {
        assertTrue(entry(0, KEY_A).selectsNoKey(TokenKeyWrapper.KEY_TYPES_167));
        assertTrue(entry(0x80, KEY_A).selectsNoKey(TokenKeyWrapper.KEY_TYPES_167));
        assertTrue(entry(0x100, KEY_A).selectsNoKey(TokenKeyWrapper.KEY_TYPES_16C));
        assertFalse(entry(0x80, KEY_A).selectsNoKey(TokenKeyWrapper.KEY_TYPES_16C));
        assertFalse(entry(0x101, KEY_A).selectsNoKey(TokenKeyWrapper.KEY_TYPES_167));
        assertFalse(entry(0x7F, KEY_A).selectsNoKey(TokenKeyWrapper.KEY_TYPES_167));
    }

    private static TokenKeyWrapper entry(final int keyType, final Key key) {
        return new TokenKeyWrapper(
                keyType, new KeyValueWrapper(false, null, key.ed25519OrThrow().toByteArray(), new byte[0], null));
    }

    private static Key ed25519Key(final byte fill) {
        final var bytes = new byte[32];
        java.util.Arrays.fill(bytes, fill);
        return Key.newBuilder().ed25519(Bytes.wrap(bytes)).build();
    }
}
