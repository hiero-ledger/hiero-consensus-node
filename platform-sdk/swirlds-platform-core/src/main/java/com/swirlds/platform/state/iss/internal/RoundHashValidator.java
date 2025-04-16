// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.iss.internal;

import static com.swirlds.common.utility.Threshold.SUPER_MAJORITY;
import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;

import com.swirlds.platform.metrics.IssMetrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.model.node.NodeId;

/**
 * <p>
 * Collects data, and validates this node's hash for a particular round once sufficient data has been collected.
 * This class is responsible for collecting the following:
 * </p>
 *
 * <ul>
 * <li>the hash computed by this node for the round</li>
 * <li>the hashes computed by other nodes for this round</li>
 * </ul>
 *
 * <p>
 * All of this data is reported asynchronously to this class by different threads, and so this class must be capable
 * of buffering that data until enough becomes available to reach a conclusion on the validity of the hash.
 * </p>
 */
public class RoundHashValidator {

    private static final Logger logger = LogManager.getLogger(RoundHashValidator.class);

    /**
     * The round number. Known at construction time.
     */
    private final long round;

    /**
     * An object capable of determining the consensus hash.
     */
    private final ConsensusHashFinder hashFinder;

    /**
     * The hash computed by this node. This data is collected after construction.
     */
    private Hash selfStateHash;

    /**
     * The validation status. Is {@link HashValidityStatus#UNDECIDED} until sufficient data is collected. Once decided
     * this value is never changed.
     */
    private HashValidityStatus status = HashValidityStatus.UNDECIDED;

    /**
     * Create an object that validates this node's hash for a round.
     *
     * @param round       the round number
     * @param roundWeight the total weight for this round
     * @param issMetrics  iss related metrics
     */
    public RoundHashValidator(final long round, final long roundWeight, @NonNull final IssMetrics issMetrics) {
        this.round = round;
        hashFinder = new ConsensusHashFinder(round, roundWeight, Objects.requireNonNull(issMetrics));
    }

    /**
     * Get the round that is being validated.
     */
    public long getRound() {
        return round;
    }

    /**
     * Get the hash that this node computed for the round if it is known, or null if it is not known.
     */
    public Hash getSelfStateHash() {
        return selfStateHash;
    }

    /**
     * Get the consensus hash if it is known, or null if it is unknown.
     */
    public Hash getConsensusHash() {
        if (hashFinder.getStatus() == ConsensusHashStatus.DECIDED) {
            return hashFinder.getConsensusHash();
        }
        return null;
    }

    /**
     * Get the consensus hash finder.
     * For read only uses after the hash status is no longer {@link HashValidityStatus#UNDECIDED}. Writing to
     * this object or reading it prior to the status becoming decided is not thread safe.
     */
    public ConsensusHashFinder getHashFinder() {
        return hashFinder;
    }

    /**
     * Report the hash computed for this round by this node. This method can be called as soon as the self hash
     * is known and does not need to wait for consensus.
     *
     * @param selfStateHash
     * 		the hash computed by this node
     * @return if the execution of this method caused us to reach a conclusion on the validity of the hash. After this
     * 		method returns true, then {@link #getStatus()} will return a value that is not
     *        {@link HashValidityStatus#UNDECIDED}.
     */
    public boolean reportSelfHash(@NonNull final Hash selfStateHash) {
        if (this.selfStateHash != null) {
            throw new IllegalStateException("self hash reported more than once");
        }
        this.selfStateHash = Objects.requireNonNull(selfStateHash, "selfStateHash must not be null");

        return decide();
    }

    /**
     * Report the hash computed by a node in the network. This method should be called only after the signature
     * transaction containing the hash reaches consensus and is handled on the handle-transaction thread. Signature
     * transactions created by this node should also be passed to this method the same way.
     *
     * @param nodeId
     * 		the node ID that is reporting the hash
     * @param nodeWeight
     * 		the weight of the node
     * @param stateHash
     * 		the hash of this round's state as computed by the node in question
     * @return if the execution of this method caused us to reach a conclusion on the validity of the hash. After this
     * 		method returns true, then {@link #getStatus()} will return a value that is not
     *        {@link HashValidityStatus#UNDECIDED}.
     */
    public boolean reportHashFromNetwork(
            @NonNull final NodeId nodeId, final long nodeWeight, @NonNull final Hash stateHash) {
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        Objects.requireNonNull(stateHash, "stateHash must not be null");
        hashFinder.addHash(nodeId, nodeWeight, stateHash);
        return decide();
    }

    /**
     * Given all data collected, make a decision about the hash for this round, if possible.
     *
     * @return if we are currently undecided and this method call causes us to become decided then return true
     */
    private boolean decide() {
        if (status != HashValidityStatus.UNDECIDED) {
            // Already decided, once decided we don't decide again
            return false;
        }

        if (hashFinder.getStatus() == ConsensusHashStatus.CATASTROPHIC_ISS) {
            // We don't need to wait for this node's hash if we detect a catastrophic ISS.
            status = HashValidityStatus.CATASTROPHIC_ISS;
            return true;
        }

        if (hashFinder.getStatus() == ConsensusHashStatus.DECIDED && selfStateHash != null) {
            if (hashFinder.getConsensusHash().equals(selfStateHash)) {
                status = HashValidityStatus.VALID;
            } else {
                status = HashValidityStatus.SELF_ISS;
            }
            return true;
        }

        // wait for more information
        return false;
    }

    /**
     * Called when we run out of time to collect additional data.
     *
     * @return if the execution of this method caused us to reach a conclusion on the validity of the hash. After this
     * 		method returns true, then {@link #getStatus()} will return a value that is not
     *        {@link HashValidityStatus#UNDECIDED}.
     */
    public boolean outOfTime() {
        if (status != HashValidityStatus.UNDECIDED) {
            // Already decided, once decided we don't decide again
            return false;
        }

        if (hashFinder.getStatus() == ConsensusHashStatus.DECIDED) {
            if (selfStateHash == null) {
                logger.warn(EXCEPTION.getMarker(), "self state hash for round {} was never reported", round);
                status = HashValidityStatus.LACK_OF_DATA;
            } else {
                // This should not be possible
                throw new IllegalStateException("The hash finder is decided and the self hash is known, "
                        + "a conclusion about this node's hash validity should have already been reached");
            }
        } else if (hashFinder.getStatus() == ConsensusHashStatus.UNDECIDED) {
            if (SUPER_MAJORITY.isSatisfiedBy(hashFinder.getHashReportedWeight(), hashFinder.getTotalWeight())) {
                // We have collected many signatures, but were still unable to find a consensus hash.
                status = HashValidityStatus.CATASTROPHIC_LACK_OF_DATA;
            } else {
                // Our lack of a consensus hash may have been the result of a failure to properly gather
                // signatures. We can't be sure if there is an ISS or not.
                status = HashValidityStatus.LACK_OF_DATA;
            }
        } else {
            // This should not be possible
            throw new IllegalStateException(
                    "The hash finder should have been reported as decided already, status = " + hashFinder.getStatus());
        }

        return true;
    }

    /**
     * Get the status of the validity of this node's hash for this round.
     *
     * @return a validity status, will be {@link HashValidityStatus#UNDECIDED} until
     * 		enough data has been gathered.
     */
    public HashValidityStatus getStatus() {
        return status;
    }

    /**
     * @return true if there is any disagreement between nodes on the hash for this round
     */
    public boolean hasDisagreement() {
        return hashFinder.hasDisagreement();
    }
}
