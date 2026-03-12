// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.data;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import com.swirlds.config.api.validation.annotation.Max;
import com.swirlds.config.api.validation.annotation.Min;

/**
 * Native coin configuration properties.
 * @param decimals the number of decimal places for the native coin
 */
@ConfigData("nativeCoin")
public record NativeCoinConfig(
        @ConfigProperty(defaultValue = "8") @Min(0) @Max(MAX_DECIMALS)
        int decimals) {
    /**
     * Maximum number of decimal places supported. Matches the 18-decimal precision of Ethereum wei.
     */
    public static final int MAX_DECIMALS = 18;
}
