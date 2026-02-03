// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.fees;

import com.hedera.node.app.spi.workflows.QueryContext;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

public interface SimpleFeeContext {
    int numTxnSignatures();

    int numTxnBytes();

    @Nullable
    FeeContext feeContext();

    @Nullable
    QueryContext queryContext();

    /**
     * Returns the current utilization percentage of the high-volume throttle for the given functionality.
     * The utilization is expressed in thousandths of one percent (0 to 100,000), where 100,000 = 100%.
     *
     * <p>This is used for HIP-1313 high-volume pricing curve calculation.
     *
     * @param functionality the functionality to get the utilization for
     * @return the utilization percentage in thousandths of one percent (0 to 100,000),
     * or 0 if no high-volume throttle exists for the functionality or if not available
     */
    default int getHighVolumeThrottleUtilization(@NonNull com.hedera.hapi.node.base.HederaFunctionality functionality) {
        return 0;
    }
}
