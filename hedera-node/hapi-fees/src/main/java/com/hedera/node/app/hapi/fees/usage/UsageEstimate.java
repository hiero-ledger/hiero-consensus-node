// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage;

import static com.hedera.node.app.hapi.fees.usage.SingletonEstimatorUtils.ESTIMATOR_UTILS;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.HRS_DIVISOR;

import com.hederahashgraph.api.proto.java.FeeComponents;

public class UsageEstimate {
    static EstimatorUtils estimatorUtils = ESTIMATOR_UTILS;

    private long rbs;
    private long sbs;

    private final FeeComponents.Builder base;

    public UsageEstimate(final FeeComponents.Builder base) {
        this.base = base;
    }

    public void addRbs(final long amount) {
        rbs += amount;
    }

    public void addSbs(final long amount) {
        sbs += amount;
    }

    public FeeComponents.Builder base() {
        return base;
    }

    public FeeComponents build() {
        return base.setSbh(estimatorUtils.nonDegenerateDiv(sbs, HRS_DIVISOR))
                .setRbh(estimatorUtils.nonDegenerateDiv(rbs, HRS_DIVISOR))
                .build();
    }

    public long getRbs() {
        return rbs;
    }
}
