// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.metrics;

import com.hedera.node.app.spi.store.StoreMetrics;
import edu.umd.cs.findbugs.annotations.NonNull;

public interface StoreMetricsService {

    enum StoreType {
        TOPIC,
        ACCOUNT,
        AIRDROP,
        NFT,
        TOKEN,
        TOKEN_RELATION,
        FILE,
        SLOT_STORAGE,
        CONTRACT,
        SCHEDULE,
        NODE
    }

    StoreMetrics get(@NonNull StoreType storeType, long capacity);
}
