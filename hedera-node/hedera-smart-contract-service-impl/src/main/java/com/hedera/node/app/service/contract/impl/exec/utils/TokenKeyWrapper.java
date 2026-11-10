// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.utils;

public record TokenKeyWrapper(int keyType, KeyValueWrapper key) {

    /**
     * Key type bits that select a token key at the 0x167 address: admin, KYC, freeze, wipe, supply, fee schedule
     * and pause.
     */
    public static final int KEY_TYPES_167 = 0x7F;

    /**
     * Key type bits that select a token key at the 0x16c address: admin, KYC, freeze, wipe, supply, fee schedule, pause and metadata key
     */
    public static final int KEY_TYPES_16C = 0xFF;

    public boolean isUsedForAdminKey() {
        return (keyType & 1) != 0;
    }

    public boolean isUsedForKycKey() {
        return (keyType & 2) != 0;
    }

    public boolean isUsedForFreezeKey() {
        return (keyType & 4) != 0;
    }

    public boolean isUsedForWipeKey() {
        return (keyType & 8) != 0;
    }

    public boolean isUsedForSupplyKey() {
        return (keyType & 16) != 0;
    }

    public boolean isUsedForFeeScheduleKey() {
        return (keyType & 32) != 0;
    }

    public boolean isUsedForPauseKey() {
        return (keyType & 64) != 0;
    }

    public boolean isUsedForMetadataKey() {
        return (keyType & 128) != 0;
    }

    /**
     * Returns whether none of the bits of this key type selects a token key.
     *
     * @param definedKeyTypes the key type bits that select a token key
     * @return true if no defined bit is set, including when the key type is zero
     */
    public boolean selectsNoKey(final int definedKeyTypes) {
        return (keyType & definedKeyTypes) == 0;
    }
}
