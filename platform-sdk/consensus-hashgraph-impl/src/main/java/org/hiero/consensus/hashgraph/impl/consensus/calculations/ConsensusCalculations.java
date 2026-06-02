// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.consensus.calculations;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * This class defines the updateEvent method, which is needed to calculate consensus. It
 * defines the class ConsensusCalculationsMemos that holds the memoized function results
 * for each event. It defines the RoundState record, which holds the full round state,
 * the RoundStatePrev record, which holds those fields starting with "prev", and the
 * updateEventResults record, which is returned by updateEvent. This implements the
 * equations from the tech report Swirlds-TR-2016-01.<p>
 *
 * For a larger program to use the Hashgraph consensus algorithm, it should include this class.
 * It should define the parents array for each event, with references to its parents, and
 * ensure that ancient events are disconnected eventually, so the parent references to them
 * are set to null. Though this doesn't have to happen immediately when they become ancient. <p>
 *
 * For a hashgraph, this class should be instantiated once. The updateEvent method should be
 * called on each event (on the schedule described in the comments for updateEvent). If a
 * call to updateEvent returns a non-null value, then the event was a "keystone event"
 * (caused consensus to be reached), and the returned record will contain the list of all the
 * events that reached consensus in that round. Which might be an empty list if none reached
 * consensus. It will also return the RoundStatePrev record, which should be used to build a
 * new roundState.
 */
public class ConsensusCalculations {
    // The following are scratchpad variables rewritten by updateEvent each time it is called.
    // These are here to avoid allocating arrays too often.
    // They also include information updated only when updateEvent is first called on
    // a given pending round.

    /** the round state fields from the tech report, with names not starting with "prev" */
    public record RoundStateCurr(
            long pendingRound,
            long[] nodes, // NodeID for each node
            long[] stake,
            int coinInterval,
            boolean firstVotingRoundSee,
            boolean judgeCon1,
            int targetNumRoundsNonAncient,
            int numRoundsAddressBook) {}

    /** the round state fields from the tech report, with names starting with "prev" */
    public record RoundStatePrev(
            boolean prevJudgeCon1,
            ConsensusCalculationsMemos[] prevJudges,
            boolean prevJudgesCopied,
            long prevMinNonAncientRound,
            long prevNumCons,
            long prevMinJudgeBirthRound) {}

    /** The round state with info about nodes, weights, previous judges, per-round
     * setting, etc. The consensus state should include the full RoundState for the current
     * pending round, and the RoundStateCurr portion of it for the next few rounds.
     */
    public record RoundState(RoundStateCurr curr, RoundStatePrev prev) {}

    /**
     * A class with the per-event scratchpad data used by updateEvent, and visible only to it.
     * This is the memoized data for the functions marked to be memoized in the tech report.
     */
    public class ConsensusCalculationsMemos {
        // index into r.nodes for the creator of this event.
        private int creator;
        private ConsensusCalculationsMemos[] parentsSigned;
        private ConsensusCalculationsMemos event;
        private boolean[] ancestorJudge;
        private boolean prevJudgeDesc;
        private long gen;
        private ConsensusCalculationsMemos[] lastSee;
        private ConsensusCalculationsMemos[] stronglySeeP;
        private ConsensusCalculationsMemos firstSelfWitnessS;
        private long votingRound;
        private ConsensusCalculationsMemos firstWitnessS;
        private ConsensusCalculationsMemos[] stronglySeeS1;
        private ConsensusCalculationsMemos[] voteE;
        private boolean[] voteB;
        private boolean isConsensus;
        private long consensusOrder;
        private Instant consensusTimestamp;
        private boolean isPrevJudge;

        /**
         * Set the parents of an event. They should be in the same order as in the signed event that is gossiped.
         * It is ok if some or all of the ancient parents are replaced with null. When an event
         * expires, this should either be called with null, or every element of the array should be set to null.
         *
         * @param parentsSigned the array of parents
         */
        public void setParentsSigned(ConsensusCalculationsMemos[] parentsSigned) {
            this.parentsSigned = parentsSigned;
        }

        /**
         * Set the index of the creator of this event. This is not the nodeID. It is the index into
         * the array of nodes in the round state.
         *
         * @param creator the index of the creator of this event
         */
        public void setCreator(int creator) {
            this.creator = creator;
        }

        /**
         * Set whether this event is a judge in the previous round. This must be set for an event before calling
         * updateEvent on it. One way to do that is when round r reaches consensus and it is time to start on
         * round r+1, first set it to false for all events in r-1, then set it to true for all judges in r. In
         * that order, since an event might be in both sets.
         *
         * @param isPrevJudge true iff this event is a judge in the previous round
         */
        public void setIsPrevJudge(boolean isPrevJudge) {
            this.isPrevJudge = isPrevJudge;
        }

        /** True iff this event has reached consensus. (If false, it may still reach consensus later). */
        public boolean getIsConsensus() {
            return isConsensus;
        }
        /** The consensus order of this event, starting at 1 for genesis (or 0 if getIsConsensus is false). */
        public long getConsensusOrder() {
            return consensusOrder;
        }
        /** True iff this event is the descendent of at least one judge from the previous round. */
        public boolean getPrevJudgeDesc() {
            return prevJudgeDesc;
        }
        /** The consensus timestamp for this event (or null if getIsConsensus is false). */
        public Instant getConsensusTimestamp() {
            return consensusTimestamp;
        }
    }

    /** updateEvent returns this (or null if consensus wasn't yet reached). */
    public record UpdateEventResults(
            List<ConsensusCalculationsMemos> consensusEvents, RoundStatePrev nextRoundStatePrev) {}

    // these are used in updateEvent, and are updated the first time it is called with a new pending round.
    private long pendingRound;
    private int numNodes;
    private long totalStake;
    private long minNonAncientRound;
    private int voteD; // must be 1 or 2

    // scratchpad used in updateEvent, allocated here to reduce garbage generation and collection
    private ArrayList<ConsensusCalculationsMemos> parents = new ArrayList<>();
    // the largest size this list has ever had. Used to recover from massive branching.
    private int parentsMaxSize = 0;

    /** true iff n is a supermajority of the total stake */
    private boolean supermajority(long n) {
        return n > 2 * totalStake / 3;
    }

    /**
     * Given the round state for the current round, set the isConsensus fields for all the non-ancient events that
     * reached consensus in previous rounds. This is only needed after a restart or reconnect. In normal operation,
     * those events will already have been marked, when they reached consensus.
     *
     * @param currRoundState the current round state
     */
    public void setPrevIsConsensus(RoundState currRoundState) {
        // TODO
    }

    /**
     * This should be called for each event, just before it is added to the hashgraph. When consensus is
     * reached for a round, it should then be called on all existing events for which prevJudgeDesc
     * is true. (It may set some of them to false). <p>
     *
     * This must be passed the complete round state for the pending round. <p>
     *
     * When there is a reconnect (or during PCES replay for a restart), there will be a period before all
     * the previous judges have been received. Do not call updateEvent during that period.  It should only
     * be called when all the judges from the previous round have been added to the hashgraph. At that point,
     * it should be called on all the appropriate events as if it is starting a new round. <p>
     *
     * This could be passed the true round state for the next round. Or it could be passed an empty
     * round state for the next round. Either way, it will fill in the "prev" fields in it if this
     * event reaches consensus (is a keystone event). If it is just a blank round state, then the "prev"
     * fields must eventually be copied to the real round state, when it exists. <p>
     *
     * Update the consensus calculations for event x, for round r. If x is a keystone event
     * (so it decides the round), then return a list of the events that reached consensus,
     * and fill in all the "prev" fields in nextR, which is the round state for the next round. <p>
     *
     * This will only read (not write) r.
     * This will only write (not read) nextR. It will only write the fields with names starting with "prev".
     * For each event that reaches consensus, this will fill in its fields isConsensus,
     * consensusOrder, and consensusTimestamp.
     *
     * @param roundState the round state for the current pending round
     * @param event the event to update
     * @return list of events that reached consensus, or null if this event didn't decide this round.
     */
    public UpdateEventResults updateEvent(@NonNull RoundState roundState, @NonNull ConsensusCalculationsMemos event) {
        // make the names look more like the r and x in the tech report
        final ConsensusCalculationsMemos x = event;
        final RoundStateCurr r = roundState.curr;
        final RoundStatePrev rp = roundState.prev;
        long parentRound;

        if (pendingRound != r.pendingRound) {
            pendingRound = r.pendingRound;
            numNodes = r.nodes.length;

            // function totalStake
            totalStake = 0;
            for (long s : r.stake) {
                totalStake += s;
            }

            // function minNonAncientRound
            minNonAncientRound =
                    Math.max(rp.prevMinNonAncientRound, rp.prevMinJudgeBirthRound - r.targetNumRoundsNonAncient);

            // function voteD
            {
                long t = 0;
                for (ConsensusCalculationsMemos judge : rp.prevJudges) {
                    t += r.stake[judge.creator];
                }
                voteD = (rp.prevJudgesCopied || (rp.prevJudgeCon1 && !r.judgeCon1) || !supermajority(t)) ? 2 : 1;
            }
        }

        // instantiate memos fields if they are null, or the array is the wrong size.
        if (x.ancestorJudge == null || x.ancestorJudge.length != numNodes) {
            x.ancestorJudge = new boolean[numNodes]; // only the first rp.prevJudges.length elements will be used
        }
        if (x.lastSee == null || x.lastSee.length != numNodes) {
            x.lastSee = new ConsensusCalculationsMemos[numNodes];
        }
        if (x.stronglySeeP == null || x.stronglySeeP.length != numNodes) {
            x.stronglySeeP = new ConsensusCalculationsMemos[numNodes];
        }
        if (x.stronglySeeS1 == null || x.stronglySeeS1.length != numNodes) {
            x.stronglySeeS1 = new ConsensusCalculationsMemos[numNodes];
        }
        if (x.voteE == null || x.voteE.length != numNodes) {
            x.voteE = new ConsensusCalculationsMemos[numNodes];
        }
        if (x.voteB == null || x.voteB.length != numNodes) {
            x.voteB = new boolean[numNodes];
        }

        // function parents
        // put in the parents array only those parents that are non-ancient descendents of judges in the prev round
        {
            // shrink to recover after branching
            if (parentsMaxSize > numNodes && x.parentsSigned.length < numNodes) {
                parents = new ArrayList<>(numNodes);
                parentsMaxSize = 0;
            }
            parents.clear();
            for (int i = 0; i < x.parentsSigned.length; i++) {
                if (x.parentsSigned[i] != null && x.parentsSigned[i].prevJudgeDesc) {
                    parents.add(x.parentsSigned[i]);
                }
            }
            parentsMaxSize = Math.max(parentsMaxSize, x.parentsSigned.length);
        }

        // function prevJudgeDesc
        x.prevJudgeDesc = x.isPrevJudge || pendingRound == 1;
        for (ConsensusCalculationsMemos parent : parents) {
            x.prevJudgeDesc |= parent.prevJudgeDesc;
        }

        // function ancestorJudge (for each y that is the index of the judge in prevJudge(r))
        for (int i = 0; i < rp.prevJudges.length; i++) {
            x.ancestorJudge[i] = (x == rp.prevJudges[i]);
            for (ConsensusCalculationsMemos parent : x.parentsSigned) {
                if (parent.ancestorJudge[i]) {
                    x.ancestorJudge[i] = true;
                    break;
                }
            }
        }

        // function gen
        {
            long t = 0;
            for (ConsensusCalculationsMemos parent : parents) {
                t = Math.max(t, parent.gen);
            }
            x.gen = t + 1;
        }

        // function parentRound
        if (parents.isEmpty()) {
            parentRound = r.pendingRound - 1;
        } else {
            parentRound = 0;
            for (ConsensusCalculationsMemos parent : parents) {
                parentRound = Math.max(parentRound, parent.votingRound);
            }
        }

        // function lastSee

        // function seeThru

        // function stronglySeeP

        // function votingRound

        // function firstSelfWitnessS

        // function firstWitnessS

        // function stronglySeeS1

        // function witness

        // function firstVote

        // function stakeAgrees

        // function topVote

        // function vote

        // function roundDecided

        // function roundJudges

        // function receivedEvents

        // function isReceived

        // function reachedCon

        // function timeCon

        // function before

        // function isConsensus

        // function consensusOrder

        // function consensusTimestamp

        return new UpdateEventResults(null, new RoundStatePrev(false, null, false, 0, 0, 0));
    }
}
