// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1261.utils;

public enum SimpleFeesChargePolicy {

    /** Unreadable bytes - payer charged 0, node charged with penalty fee */
    UNREADABLE_BYTES_ZERO_PAYER,

    /** Invalid txn at ingest - due diligence failed - payer charged 0, node charged 0 */
    INVALID_TXN_AT_INGEST_ZERO_PAYER,

    /** Invalid txn at pre-handle - due diligence failed - payer charged 0, node charged */
    INVALID_TXN_AT_PRE_HANDLE_ZERO_PAYER,

    /** Unhandled txn - payer charged Node + Network + Service fees*/
    UNHANDLED_TXN_FULL_CHARGE,

    /** Unhandled txn - payer charged Node + Network fees only*/
    UNHANDLED_TXN_NODE_AND_NETWORK_CHARGE,

    /** Successful handled txn - payer charged Node + Network + Service fees*/
    SUCCESS_TXN_FULL_CHARGE
}
