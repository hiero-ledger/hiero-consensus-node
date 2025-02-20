// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage.util;

import com.hedera.node.app.hapi.fees.usage.BaseTransactionMeta;
import com.hedera.node.app.hapi.fees.usage.SigUsage;
import com.hedera.node.app.hapi.fees.usage.state.UsageAccumulator;
import javax.inject.Inject;

public class UtilOpsUsage {

    @Inject
    public UtilOpsUsage() {
        // Default constructor
    }

    public void prngUsage(
            final SigUsage sigUsage,
            final BaseTransactionMeta baseMeta,
            final UtilPrngMeta utilPrngMeta,
            final UsageAccumulator accumulator) {
        accumulator.resetForTransaction(baseMeta, sigUsage);
        var baseSize = utilPrngMeta.getMsgBytesUsed();
        accumulator.addBpt(baseSize);
    }
}
