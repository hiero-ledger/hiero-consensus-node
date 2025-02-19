/*
 * Copyright (C) 2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
