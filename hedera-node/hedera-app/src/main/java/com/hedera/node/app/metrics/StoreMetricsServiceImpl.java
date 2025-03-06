// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.metrics;

import com.hedera.node.app.spi.metrics.StoreMetricsService;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.state.spi.metrics.StoreMetrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.EnumMap;
import java.util.Map;
import javax.inject.Singleton;

@Singleton
public class StoreMetricsServiceImpl implements StoreMetricsService {

    private final Map<StoreType, StoreMetricsImpl> storeMetricsMap;

    public StoreMetricsServiceImpl(@NonNull final Metrics metrics) {
        this.storeMetricsMap = new EnumMap<>(StoreType.class);
        storeMetricsMap.put(StoreType.TOPIC, new StoreMetricsImpl(metrics, "topics"));
        storeMetricsMap.put(StoreType.ACCOUNT, new StoreMetricsImpl(metrics, "accounts"));
        storeMetricsMap.put(StoreType.AIRDROP, new StoreMetricsImpl(metrics, "airdrops"));
        storeMetricsMap.put(StoreType.NFT, new StoreMetricsImpl(metrics, "nfts"));
        storeMetricsMap.put(StoreType.TOKEN, new StoreMetricsImpl(metrics, "tokens"));
        storeMetricsMap.put(
                StoreType.TOKEN_RELATION, new StoreMetricsImpl(metrics, "tokenAssociations", "token associations"));
        storeMetricsMap.put(StoreType.FILE, new StoreMetricsImpl(metrics, "files"));
        storeMetricsMap.put(StoreType.SLOT_STORAGE, new StoreMetricsImpl(metrics, "storageSlots", "storage slots"));
        storeMetricsMap.put(StoreType.CONTRACT, new StoreMetricsImpl(metrics, "contracts"));
        storeMetricsMap.put(StoreType.SCHEDULE, new StoreMetricsImpl(metrics, "schedules"));
        storeMetricsMap.put(StoreType.NODE, new StoreMetricsImpl(metrics, "nodes"));
    }

    @Override
    public StoreMetrics get(@NonNull final StoreType storeType, final long capacity) {
        final var metric = storeMetricsMap.get(storeType);
        metric.updateCapacity(capacity);
        return metric;
    }
}
