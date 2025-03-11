// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage;

import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.BASIC_ENTITY_ID_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.LONG_SIZE;

public enum SingletonUsageProperties implements UsageProperties {
    USAGE_PROPERTIES;

    @Override
    public int accountAmountBytes() {
        return LONG_SIZE + BASIC_ENTITY_ID_SIZE;
    }

    @Override
    public int nftTransferBytes() {
        return LONG_SIZE + 2 * BASIC_ENTITY_ID_SIZE;
    }

    @Override
    public long legacyReceiptStorageSecs() {
        return 180;
    }
}
