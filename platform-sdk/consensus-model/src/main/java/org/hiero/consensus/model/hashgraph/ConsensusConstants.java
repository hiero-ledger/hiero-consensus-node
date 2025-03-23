// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.hashgraph;

/** Constants used for consensus */
public final class ConsensusConstants {
    private ConsensusConstants() {}

    /**
     * the consensus timestamp of a transaction is guaranteed to be at least this many nanoseconds
     * later than that of the transaction immediately before it in consensus order, and to be a
     * multiple of this (must be positive and a multiple of 10)
     */
    public static final long MIN_TRANS_TIMESTAMP_INCR_NANOS = 1_000;

    /** the number used to represent that a round has not been defined */
    public static final long ROUND_UNDEFINED = -1;
    /** represents a round of -infinity */
    public static final long ROUND_NEGATIVE_INFINITY = 0;
    /** the first round number */
    public static final long ROUND_FIRST = 1;
    /** value of the event mark when it is unmarked */
    public static final int EVENT_UNMARKED = 0;
    /** the consensus number of the first event ever to reach consensus */
    public static final long FIRST_CONSENSUS_NUMBER = 0;
    /** the consensus order set when an event has not reached consensus */
    public static final long NO_CONSENSUS_ORDER = -1;
}
