// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.metrics;

import com.swirlds.platform.internal.EventImpl;

/**
 * Collection of metrics related to consensus
 */
public interface ConsensusMetrics {
    /**
     * Update a statistics accumulator when an gossiped event has been added to the hashgraph.
     *
     * @param event
     * 		an event
     */
    void addedEvent(EventImpl event);

    /** Update a statistics accumulator when a coin round has occurred. */
    void coinRound();

    /**
     * Update a statistics accumulator to receive the time when a gossiped event
     * is the last famous witness in its round.
     *
     * @param event
     * 		an event
     */
    void lastFamousInRound(EventImpl event);

    // this might not need to be a separate method
    // we could just update the stats in consensusReached(EventImpl event) when event.lastInRoundReceived()==true

    /**
     * Update a statistics accumulator a round has reached consensus.
     */
    void consensusReachedOnRound();

    /**
     * Update a statistics accumulator when an event reaches consensus
     *
     * @param event
     * 		an event
     */
    void consensusReached(EventImpl event);

    /**
     * Update a statistics accumulator with dot product time. This is used
     * in the consensus impl to track performance of strong seen ancestor searches
     * in the parent round of a given event.
     *
     * @param nanoTime
     * 		a time interval, in nanoseconds
     */
    void dotProductTime(long nanoTime);

    /**
     * Update a statistics accumulator with the number of witnesses that are strongly seen by events added to the
     * hashgraph whose parents have the same round created. If more than a strong majority are seen, the round
     * created is incremented.
     *
     * @param numSeen
     * 		the number of witnesses strongly seen
     */
    void witnessesStronglySeen(final int numSeen);

    /**
     * Called each time an event is assigned a newer round created than it's parents based on the number of witness
     * in the parent round created that it strongly sees.
     */
    void roundIncrementedByStronglySeen();

    /**
     * Returns the average difference in creation time and consensus time for self events.
     *
     * @return the weighted mean
     */
    double getAvgSelfCreatedTimestamp();

    /**
     * Returns the average difference in creation time and consensus time for other events.
     *
     * @return weighted mean
     */
    double getAvgOtherReceivedTimestamp();

    /**
     * Report the weight of the judges when a round comes to consensus.
     *
     * @param weight the weight of the judges
     */
    void judgeWeights(long weight);
}
