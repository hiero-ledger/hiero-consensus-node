// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.throttle;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.fees.congestion.CongestionMultipliers;
import com.hedera.node.app.store.ReadableStoreFactory;
import com.hedera.node.app.workflows.TransactionInfo;
import com.swirlds.metrics.api.LongGauge;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class CongestionMetrics {
    private final CongestionMultipliers congestionMultipliers;
    private final LongGauge congestionMultiplierGauge;

    @Inject
    public CongestionMetrics(
            @NonNull final Metrics metrics, @NonNull final CongestionMultipliers congestionMultipliers) {
        requireNonNull(metrics);
        this.congestionMultipliers = requireNonNull(congestionMultipliers);

        final var config =
                new LongGauge.Config("app", "congestionMultiplier").withDescription("Current congestion multiplier");
        this.congestionMultiplierGauge = metrics.getOrCreate(config);
    }

    public void updateMultiplier(
            @NonNull final TransactionInfo txnInfo, @NonNull final ReadableStoreFactory storeFactory) {
        congestionMultiplierGauge.set(congestionMultipliers.maxCurrentMultiplier(txnInfo, storeFactory));
    }
}
