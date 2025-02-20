// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.hedera.subprocess;

/**
 * Enumerates possible status for a condition we are awaiting.
 */
public enum ConditionStatus {
    REACHED,
    PENDING,
    UNREACHABLE,
}
