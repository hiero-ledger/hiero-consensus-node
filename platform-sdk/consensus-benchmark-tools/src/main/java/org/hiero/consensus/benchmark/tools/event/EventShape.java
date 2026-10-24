// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.benchmark.tools.event;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * What an event looks like, apart from its place in the graph. A fixed shape uses
 * {@link EmpiricalDistribution#constant(long)} for each quantity:
 *
 * <pre>{@code
 * // one other parent and one transaction of 445 bytes per event
 * EventShape shape = new EventShape(constant(1), constant(1), constant(445));
 * }</pre>
 *
 * @param otherParents         the number of other parents of an event, besides its self parent
 * @param transactionsPerEvent the number of transactions in an event
 * @param transactionSize      the size of a transaction in bytes, drawn for each transaction
 */
public record EventShape(
        @NonNull EmpiricalDistribution otherParents,
        @NonNull EmpiricalDistribution transactionsPerEvent,
        @NonNull EmpiricalDistribution transactionSize) {

    public EventShape {
        requireNonNull(otherParents);
        requireNonNull(transactionsPerEvent);
        requireNonNull(transactionSize);
    }
}
