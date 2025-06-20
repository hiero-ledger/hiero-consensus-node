// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.token.hip540;

/**
 * Enumerates the possible authorizing signatures for a HIP-540 test scenario.
 */
public enum AuthorizingSignature {
    EXTANT_ADMIN,
    EXTANT_NON_ADMIN,
    NEW_NON_ADMIN,
}
