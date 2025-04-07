// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state;

import static com.swirlds.base.units.UnitConstants.NANOSECONDS_TO_SECONDS;
import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;

import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.swirlds.platform.metrics.StateMetrics;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.transaction.ScopedSystemTransaction;

public class TransactionHandler {

    /** use this for all logging, as controlled by the optional data/log4j2.xml file */
    private static final Logger logger = LogManager.getLogger(TransactionHandler.class);

    /** The id of this node. */
    private final NodeId selfId;

    /** Stats relevant to the state operations. */
    private final StateMetrics stats;

    public TransactionHandler(final NodeId selfId, final StateMetrics stats) {
        this.selfId = selfId;
        this.stats = stats;
    }

    /**
     * Applies a consensus round to the state, handles any exceptions gracefully, and updates relevant statistics.
     *
     * @param round
     * 		the round to apply
     * @param consensusStateEventHandler
     * 		the consensusStateEventHandler to apply {@code round} to
     * @param stateRoot the state root to apply {@code round} to
     */
    public <T extends MerkleNodeState> Queue<ScopedSystemTransaction<StateSignatureTransaction>> handleRound(
            final ConsensusRound round,
            final ConsensusStateEventHandler<MerkleNodeState> consensusStateEventHandler,
            final T stateRoot) {
        final Queue<ScopedSystemTransaction<StateSignatureTransaction>> scopedSystemTransactions =
                new ConcurrentLinkedQueue<>();

        try {
            final Instant timeOfHandle = Instant.now();
            final long startTime = System.nanoTime();

            consensusStateEventHandler.onHandleConsensusRound(round, stateRoot, scopedSystemTransactions::add);

            final double secondsElapsed = (System.nanoTime() - startTime) * NANOSECONDS_TO_SECONDS;

            // Avoid dividing by zero
            if (round.getNumAppTransactions() == 0) {
                stats.consensusTransHandleTime(secondsElapsed);
            } else {
                stats.consensusTransHandleTime(secondsElapsed / round.getNumAppTransactions());
            }
            stats.consensusTransHandled(round.getNumAppTransactions());
            stats.consensusToHandleTime(
                    round.getReachedConsTimestamp().until(timeOfHandle, ChronoUnit.NANOS) * NANOSECONDS_TO_SECONDS);
        } catch (final Throwable t) {
            logger.error(
                    EXCEPTION.getMarker(),
                    "error invoking ConsensusStateEventHandler.onHandleConsensusRound() [ nodeId = {} ] with round {}",
                    selfId,
                    round.getRoundNum(),
                    t);
        }
        return scopedSystemTransactions;
    }
}
