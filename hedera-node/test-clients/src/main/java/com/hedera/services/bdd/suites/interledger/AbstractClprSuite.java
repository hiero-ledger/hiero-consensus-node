// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.interledger;

import com.hedera.services.bdd.suites.multinetwork.AbstractMultiNetworkSuite;

/**
 * Base class for CLPR suites that layers CLPR-specific defaults on top of multi-network defaults.
 * Defaults apply only when the corresponding system property is not already set.
 */
public abstract class AbstractClprSuite extends AbstractMultiNetworkSuite {

    public static void setConfigDefaults() {
        // Apply the multi-network defaults first
        AbstractMultiNetworkSuite.setConfigDefaults();
        // CLPR suites expect the service to be active even though multi-network defaults disable it.
        System.setProperty("clpr.clprEnabled", "true");
        System.setProperty("clpr.devModeEnabled", "true");
        System.setProperty("clpr.publicizeClprEndpoints", "true");
        // Keep the refresh loop fast for local harnesses
        System.setProperty("clpr.connectionFrequency", "500");
    }

    protected static void setIfAbsent(final String key, final String value) {
        if (System.getProperty(key) == null) {
            System.setProperty(key, value);
        }
    }
}
