// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.utils.contracts.precompile;

import java.math.BigInteger;

/** All key types in one place for easy review. */
public enum TokenKeyType {
    ADMIN_KEY(1),
    KYC_KEY(2),
    FREEZE_KEY(4),
    WIPE_KEY(8),
    SUPPLY_KEY(16),
    FEE_SCHEDULE_KEY(32),
    PAUSE_KEY(64),
    METADATA_KEY(128);

    private final int value;

    TokenKeyType(final int value) {
        this.value = value;
    }

    public int value() {
        return value;
    }

    public BigInteger asBigInteger() {
        return BigInteger.valueOf(value);
    }
}
