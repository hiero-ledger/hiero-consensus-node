// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.dsl.entities;

/**
 * Enumerates the types of keys that can be associated with a token.
 */
public enum SpecTokenKey {
    ADMIN_KEY,
    KYC_KEY,
    FREEZE_KEY,
    WIPE_KEY,
    SUPPLY_KEY,
    FEE_SCHEDULE_KEY,
    PAUSE_KEY,
    METADATA_KEY,
}
