// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.utils;

import com.hedera.node.config.data.ContractsConfig;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Some utilities related to validation of contract transactions.
 */
public final class ValidationUtils {

    private ValidationUtils() {
        throw new UnsupportedOperationException("Utility Class");
    }

    /**
     * Returns the maximum gas limit for a transaction based on the provided {@link ContractsConfig}.
     * If the Gas Throttle is enabled we respect the maxGasPerSec value and take the minimum of that and
     * maxGasPerTransaction. If the Gas Throttle is disabled, we simply return maxGasPerTransaction.
     *
     * @param contractsConfig the configuration containing gas limits
     * @return the maximum gas limit
     */
    public static long getMaxGasLimit(@NonNull final ContractsConfig contractsConfig) {
        return contractsConfig.throttleThrottleByGas()
                ? Math.min(contractsConfig.maxGasPerTransaction(), contractsConfig.maxGasPerSec())
                : contractsConfig.maxGasPerTransaction();
    }
}
