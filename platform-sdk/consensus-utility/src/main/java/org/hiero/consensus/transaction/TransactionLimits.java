// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.transaction;

/**
 * Configuration regarding transactions
 *
 * @param transactionMaxBytes          maximum number of bytes allowed in a transaction
 * @param maxTransactionBytesPerEvent  the maximum number of bytes that a single event may contain, not including the
 *                                     event headers. if a single transaction exceeds this limit, then the event will
 *                                     contain the single transaction only
 */
public record TransactionLimits(int transactionMaxBytes, int maxTransactionBytesPerEvent) {}
