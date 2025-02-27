// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.utils;

public record TokenKeyWrapper(int keyType, KeyValueWrapper key) {
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
}
