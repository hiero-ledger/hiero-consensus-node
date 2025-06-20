// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.token.hip540;

/**
 * Enumerates the possible non-admin key management actions in a HIP-540 test scenario.
 */
public enum ManagementAction {
    ADD,
    REMOVE,
    REPLACE,
    ZERO_OUT,
    REPLACE_WITH_INVALID,
}
