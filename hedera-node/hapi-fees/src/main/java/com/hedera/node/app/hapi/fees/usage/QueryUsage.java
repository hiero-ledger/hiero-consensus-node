// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage;

import static com.hedera.node.app.hapi.fees.usage.SingletonEstimatorUtils.ESTIMATOR_UTILS;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.BASIC_QUERY_HEADER;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.BASIC_QUERY_RES_HEADER;

import com.hedera.hapi.node.base.FeeComponents;
import com.hedera.hapi.node.base.FeeData;
import com.hedera.hapi.node.base.ResponseType;

public class QueryUsage {
    private long sb = 0;
    private long tb = BASIC_QUERY_HEADER;
    private long rb = BASIC_QUERY_RES_HEADER;

    /* Once state proofs are supported, this will be needed to compute {@code rb}. */
    // Suppressing the warning that the field is not used
    @SuppressWarnings("java:S1068")
    private final ResponseType responseType;

    public QueryUsage(final ResponseType responseType) {
        this.responseType = responseType;
    }

    public FeeData get() {
        final var usage = FeeComponents.newBuilder().bpt(tb).bpr(rb).sbpr(sb).build();
        return ESTIMATOR_UTILS.withDefaultQueryPartitioning(usage);
    }

    public void addRb(final long amount) {
        rb += amount;
    }

    public void addTb(final long amount) {
        tb += amount;
    }

    public void addSb(final long amount) {
        sb += amount;
    }
}
