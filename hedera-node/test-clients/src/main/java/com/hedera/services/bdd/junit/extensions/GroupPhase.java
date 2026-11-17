// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.extensions;

/**
 * Where a {@link NetworkGroup} is in its lifecycle, in the order it passes them. A group only moves forward, and
 * skips the validation phases when validation is not enabled.
 */
public enum GroupPhase {
    /** Registered; waiting for its nodes to fit the budget. */
    WAITING_FOR_SLOT,
    /** Got a slot: its networks boot on its first test, and its tests run. */
    RUNNING_TESTS,
    /** Its tests are done; waiting for the validation test to take it. */
    AWAITING_VALIDATION,
    /** The validation test is validating its logs, then its streams (which freezes its networks). */
    VALIDATING,
    /** Its networks are being torn down. */
    TEARDOWN,
    /** Torn down, and its nodes released. */
    DONE
}
