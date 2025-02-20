// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.validation;

import static com.hedera.node.app.spi.validation.ExpiryMeta.NA;
import static org.junit.jupiter.api.Assertions.*;

import com.hedera.hapi.node.base.AccountID;
import org.junit.jupiter.api.Test;

class ExpiryMetaTest {
    @Test
    void detectsExplicitExpiry() {
        final var withExpiry = new ExpiryMeta(1L, NA, null);
        final var withoutExpiry = new ExpiryMeta(NA, NA, null);
        assertTrue(withExpiry.hasExplicitExpiry());
        assertFalse(withoutExpiry.hasExplicitExpiry());
    }

    @Test
    void detectsRenewPeriod() {
        final var withRenewPeriod = new ExpiryMeta(NA, 1L, null);
        final var withoutRenewPeriod =
                new ExpiryMeta(2L, NA, AccountID.newBuilder().accountNum(1L).build());
        assertTrue(withRenewPeriod.hasAutoRenewPeriod());
        assertFalse(withoutRenewPeriod.hasAutoRenewPeriod());
    }

    @Test
    void detectsRenewNum() {
        final var withRenewNum =
                new ExpiryMeta(NA, 1L, AccountID.newBuilder().accountNum(1L).build());
        final var withoutRenewNum = new ExpiryMeta(2L, NA, null);
        assertTrue(withRenewNum.hasAutoRenewAccountId());
        assertFalse(withoutRenewNum.hasAutoRenewAccountId());
    }

    @Test
    void detectsFullAutoRenewSpec() {
        final var withFullSpec =
                new ExpiryMeta(NA, 1L, AccountID.newBuilder().accountNum(1L).build());
        final var withoutFullSpec =
                new ExpiryMeta(2L, NA, AccountID.newBuilder().accountNum(1L).build());
        assertTrue(withFullSpec.hasFullAutoRenewSpec());
        assertFalse(withoutFullSpec.hasFullAutoRenewSpec());
    }
}
