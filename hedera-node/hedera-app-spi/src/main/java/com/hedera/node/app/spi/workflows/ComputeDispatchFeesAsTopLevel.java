// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.workflows;

/**
 * Determines whether the fees for an internal dispatch should be instead
 * computed as a top-level transaction. Needed for exact mono-service
 * fidelity when computing fees of scheduled transactions.
 *
 * <p>(FUTURE) Remove this, the effect on fees is a few tinybars at most;
 * just enough to break differential testing.
 */
public enum ComputeDispatchFeesAsTopLevel {
    YES,
    NO
}
