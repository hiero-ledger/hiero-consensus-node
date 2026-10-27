// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.clpr;

/**
 * How one outbound sync attempt for a Channel went. It decides whether the dialed peer's failure tracking records a
 * success or a failure, and whether the Channel backs off before its next attempt.
 */
public enum SyncOutcome {
    /** A bundle moved in either direction. Counts as a success for the peer. */
    PROGRESS,
    /** The exchange followed the protocol but moved nothing. Neither a success nor a failure. */
    IDLE,
    /** The exchange failed, timed out, or the peer broke the protocol. Counts as a failure; the Channel backs off. */
    FAILURE,
    /** No exchange was attempted, e.g. the Channel is not syncable or no peer endpoint was available. */
    SKIPPED
}
