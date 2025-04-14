// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.config;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;

/**
 * Configuration regarding transactions
 *
 * @param transactionMaxBytes          maximum number of bytes allowed in a transaction
 * @param maxTransactionBytesPerEvent  the maximum number of bytes that a single event may contain, not including the
 *                                     event headers. if a single transaction exceeds this limit, then the event will
 *                                     contain the single transaction only
 * @param maxTransactionCountPerEvent  the maximum number of transactions that a single event may contain
 * @param throttleTransactionQueueSize Stop accepting new non-system transactions into the 4 transaction queues if any
 *                                     of them have more than this many.
 */
@ConfigData("transaction")
public record TransactionConfig(
        @ConfigProperty(defaultValue = "6144") int transactionMaxBytes,
        @ConfigProperty(defaultValue = "245760") int maxTransactionBytesPerEvent,
        @ConfigProperty(defaultValue = "245760") int maxTransactionCountPerEvent,
        @ConfigProperty(defaultValue = "100000") int throttleTransactionQueueSize) {}
