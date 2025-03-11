// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.restart;

/**
 * The types of startup assets available for a {@link RestartHapiTest}.
 */
public enum StartupAssets {
    /**
     * No network override is present.
     */
    NONE,
    /**
     * A network override with only the network roster is present.
     */
    ROSTER_ONLY,
}
