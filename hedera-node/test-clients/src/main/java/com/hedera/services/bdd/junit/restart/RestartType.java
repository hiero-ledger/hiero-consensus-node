// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.restart;

/**
 * The types of restarts to be covered by a {@link RestartHapiTest}.
 */
public enum RestartType {
    /**
     * The "restart" is from genesis.
     */
    GENESIS,
    /**
     * The restart uses the same software version as the saved state.
     */
    SAME_VERSION,
    /**
     * The restart uses a later software version than the saved state.
     */
    UPGRADE_BOUNDARY,
}
