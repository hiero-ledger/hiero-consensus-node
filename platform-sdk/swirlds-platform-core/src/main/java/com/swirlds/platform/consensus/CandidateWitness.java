// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.consensus;

import static com.swirlds.logging.legacy.LogMarker.CONSENSUS_VOTING;

import com.swirlds.common.utility.IntReference;
import com.swirlds.platform.internal.EventImpl;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** A wrapper for a witness which holds additional metadata while an election to decide its fame is ongoing */
public final class CandidateWitness {
    private static final Logger logger = LogManager.getLogger(CandidateWitness.class);
    private final EventImpl witness;
    private final IntReference numUnknownFame;
    private final int electionIndex;
    private boolean decided;
    private boolean famous;

    /**
     * @param witness        the witness being voted on
     * @param numUnknownFame a counter that tracks how many witness in same round created as this witness still don't
     *                       have their fame decided
     * @param electionIndex  the index of the witness in the current election
     */
    public CandidateWitness(
            @NonNull final EventImpl witness, @NonNull final IntReference numUnknownFame, final int electionIndex) {
        this.witness = Objects.requireNonNull(witness);
        this.numUnknownFame = Objects.requireNonNull(numUnknownFame);
        this.electionIndex = electionIndex;
        this.decided = false;
        this.famous = false;
    }

    /**
     * @return the witness being voted on
     */
    public @NonNull EventImpl getWitness() {
        return witness;
    }

    /**
     * @return the index of the witness in the current election
     */
    public int getElectionIndex() {
        return electionIndex;
    }

    /**
     * @return true if this witnesses fame has been decided
     */
    public boolean isDecided() {
        return decided;
    }

    /**
     * @return true if this witnesses fame has NOT been decided
     */
    public boolean isNotDecided() {
        return !decided;
    }

    /**
     * @return true if this witness has had its fame decided and the decision is that its famous
     */
    public boolean isFamous() {
        return famous;
    }

    /**
     * Set a witness as being famous or not
     *
     * @param isFamous is this witness famous?
     */
    public void fameDecided(final boolean isFamous) {
        witness.setFamous(isFamous);
        witness.setFameDecided(true);

        numUnknownFame.decrement();

        this.decided = true;
        this.famous = isFamous;

        if (logger.isDebugEnabled(CONSENSUS_VOTING.getMarker())) {
            logger.debug(
                    CONSENSUS_VOTING.getMarker(),
                    "Fame decided for {}, election round {} unknown fame: {} ",
                    witness,
                    witness.getRoundCreated(),
                    numUnknownFame.get());

            if (numUnknownFame.equalsInt(0)) {
                logger.debug(CONSENSUS_VOTING.getMarker(), "Fame decided for round {}", witness.getRoundCreated());
            }
        }
    }
}
