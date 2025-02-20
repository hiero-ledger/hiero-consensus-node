// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit;

/**
 * Enumerates reasons a {@link EmbeddedHapiTest} is marked as such.
 */
public enum EmbeddedReason {
    /**
     * The test must skip the ingest workflow to submit its transactions, as they would always be rejected by
     * a node in normal operations.
     */
    MUST_SKIP_INGEST,
    /**
     * The test must directly access state to assert expectations that cannot be verified through the gRPC API.
     */
    NEEDS_STATE_ACCESS,
    /**
     * The test manipulates the software version of the simulated consensus event for a transaction.
     */
    MANIPULATES_EVENT_VERSION,
}
