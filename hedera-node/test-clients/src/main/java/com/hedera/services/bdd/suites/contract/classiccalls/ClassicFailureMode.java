// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.classiccalls;

/**
 * Enumerates reasons a classic HTS call might fail.
 */
public enum ClassicFailureMode {
    NO_REASON_TO_FAIL,
    INVALID_ACCOUNT_ID_FAILURE,
    INVALID_TOKEN_ID_FAILURE,
    INVALID_NFT_ID_FAILURE,
}
