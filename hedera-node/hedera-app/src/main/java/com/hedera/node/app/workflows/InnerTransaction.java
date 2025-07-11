// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows;

/**
 * Indicates whether the transaction is an inner transaction.
 * Used in ingest and pre-handle workflows to determine if the
 * transaction is part of an atomic batch.
 */
public enum InnerTransaction {
    YES,
    NO
}
