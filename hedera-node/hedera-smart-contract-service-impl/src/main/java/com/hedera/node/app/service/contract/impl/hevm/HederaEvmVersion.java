// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.hevm;

import java.util.Map;

public enum HederaEvmVersion {
    /**
     * EVM version 0.30
     */
    VERSION_030("v0.30"),
    /**
     * EVM version 0.34
     */
    VERSION_034("v0.34"),
    /**
     * EVM version 0.38
     */
    VERSION_038("v0.38"),
    /**
     * EVM version 0.46
     */
    VERSION_046("v0.46"),
    /**
     * EVM version 0.50
     */
    VERSION_050("v0.50"), /* Cancun */
    /**
     * EVM version 0.51
     */
    VERSION_051("v0.51"), /* Hedera Account Service System Contract */
    /**
     * EVM version 0.65
     */
    VERSION_065("v0.65"), /* Alternate gas schedule */
    /**
     * EVM version 0.66
     */
    VERSION_066("v0.66"), /* Native libs verification; Disable alternate gas schedule */
    /**
     * EVM version 0.67
     */
    VERSION_067("v0.67"); /* Re-enable alternate gas schedule */

    /**
     * All supported EVM versions
     */
    public static final Map<String, HederaEvmVersion> EVM_VERSIONS = Map.of(
            VERSION_030.key(), VERSION_030,
            VERSION_034.key(), VERSION_034,
            VERSION_038.key(), VERSION_038,
            VERSION_046.key(), VERSION_046,
            VERSION_050.key(), VERSION_050,
            VERSION_051.key(), VERSION_051,
            VERSION_065.key(), VERSION_065,
            VERSION_066.key(), VERSION_066,
            VERSION_067.key(), VERSION_067);

    HederaEvmVersion(String key) {
        this.key = key;
    }

    private final String key;

    public String key() {
        return key;
    }
}
