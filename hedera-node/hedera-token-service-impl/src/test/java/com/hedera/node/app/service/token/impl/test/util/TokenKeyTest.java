// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.util;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.KeyList;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.node.app.service.token.impl.util.TokenKey;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import org.junit.jupiter.api.Test;

class TokenKeyTest {
    // The empty KeyList sentinel (KeyUtils.IMMUTABILITY_SENTINEL_KEY) — a "removed"/disabled key.
    private static final Key EMPTY_KEY =
            Key.newBuilder().keyList(KeyList.DEFAULT).build();
    // A structurally non-empty key (its bytes are irrelevant for the emptiness check).
    private static final Key USABLE_KEY =
            Key.newBuilder().ed25519(Bytes.wrap(new byte[32])).build();

    @Test
    void isPresentInitiallyTreatsEmptyKeyAsAbsentAndUsableKeyAsPresent() {
        final var allEmptyKeys = Token.newBuilder()
                .adminKey(EMPTY_KEY)
                .kycKey(EMPTY_KEY)
                .wipeKey(EMPTY_KEY)
                .supplyKey(EMPTY_KEY)
                .freezeKey(EMPTY_KEY)
                .feeScheduleKey(EMPTY_KEY)
                .pauseKey(EMPTY_KEY)
                .metadataKey(EMPTY_KEY)
                .build();
        final var allUsableKeys = Token.newBuilder()
                .adminKey(USABLE_KEY)
                .kycKey(USABLE_KEY)
                .wipeKey(USABLE_KEY)
                .supplyKey(USABLE_KEY)
                .freezeKey(USABLE_KEY)
                .feeScheduleKey(USABLE_KEY)
                .pauseKey(USABLE_KEY)
                .metadataKey(USABLE_KEY)
                .build();

        // An empty-KeyList key counts as "no key" (HIP-540 removal sentinel), so a removed key cannot
        // be updated; a usable key is present and can be rotated.
        for (final var tokenKey : TokenKey.values()) {
            assertThat(tokenKey.isPresentInitially(allEmptyKeys))
                    .as("%s should be absent when its key is the empty-KeyList sentinel", tokenKey)
                    .isFalse();
            assertThat(tokenKey.isPresentInitially(allUsableKeys))
                    .as("%s should be present when its key is non-empty", tokenKey)
                    .isTrue();
        }
    }
}
