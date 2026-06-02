// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.consensus.calculations;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * This class implements the Hashgraph consensus algorithm. It is self-contained, with
 * no dependencies other than on the standard Java libraries. The HashgraphInfo class
 * contains the subclass EventInfo and the record types RoundState, RoundStateCurr,
 * and RoundStatePrev. All of these have getters and no setters. This file implements the
 * equations from the tech report Swirlds-TR-2016-01.<p>
 *
 * A single HashgraphInfo should be instantiated for the hashgraph. If several hashgraphs
 * exist, such as for a simulation of multiple nodes, then there should be one per hashgraph.<p>
 *
 * An EventInfo should be instantiated for each event. The update methods for all the events are
 * called to calculate consensus. At some time many rounds after an event becomes
 * ancient, it should have its expire method called to clean up memory by erasing all its
 * references to even older events.<p>
 *
 * For a larger program to use the Hashgraph consensus algorithm, it should include this class.
 * It should instantiate a RoundState for the current pending round. It should instantiate the
 * RoundStateCurr for the next several rounds. After a round reaches consensus, its RoundStatePrev
 * is calculated, which can be combined with the RoundStateCurr to get the RoundState for the
 * next round.<p>
 *
 * The network's overall state should include the RoundState for the pending round, and the
 * RoundStateCurr for the next few rounds. An implementation might also include a "roster"
 * for these and for all the non-ancient previous rounds. The roster might contain info such as
 * the public keys for all the nodes, used to verify their signatures. This class doesn't
 * use rosters. It only uses the RoundState, and its two parts.<p>
 *
 * For a hashgraph, this class should be instantiated once. The EventInfo.update method should be
 * called on each event for each round, according to the schedule described in the comments
 * for the update method. <p>
 *
 * If a call to EventInfo.update returns a non-null value, then the event caused consensus to be
 * reached for that round (a "keystone event"). In that case, it doesn't return null.
 * The returned record will contain the list of all the events that reached consensus in that round.
 * Which might be an empty list if none reached consensus. It will also return the
 * RoundStatePrev record, which should be used to build the roundState for the next round.
 */
public class HashgraphInfo {
    // these fields are used in EventInfo.update and are updated the first time it is called
    // with any given pending round.
    private long pendingRound;
    private int numNodes;
    private long totalStake;
    private long minNonAncientRound;
    private int voteD; // must be 1 or 2

    /** info about a round that is known multiple rounds in advance */
    public record RoundStateCurr(
            long pendingRound,
            long[] nodes, // NodeID for each node
            long[] stake,
            int coinInterval,
            boolean firstVotingRoundSee,
            boolean judgeCon1,
            int targetNumRoundsNonAncient,
            int numRoundsAddressBook) {}

    /** info about a round that is only available when the previous round reaches consensus */
    public record RoundStatePrev(
            boolean prevJudgeCon1,
            EventInfo[] prevJudges,
            boolean prevJudgesCopied,
            long prevMinNonAncientRound,
            long prevNumCons,
            long prevMinJudgeBirthRound) {}

    /** The round state with info about nodes, weights, previous judges, per-round
     * setting, etc. The consensus state should include the full RoundState for the current
     * pending round, and the RoundStateCurr portion of it for the next few rounds.
     */
    public record RoundState(RoundStateCurr curr, RoundStatePrev prev) {}

    // scratchpad used in update, allocated here to reduce garbage generation and collection
    private ArrayList<EventInfo> parents = new ArrayList<>();
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
     * A class with the per-event scratchpad data.
     * This is the memoized data for the functions marked to be memoized in the tech report.
     */
    public static class EventInfo {
        private HashgraphInfo hashgraph;
        private int creator; //an index into nodes[], not the nodeID
        private EventInfo[] parentsSigned;
        private EventInfo event;
        private boolean[] ancestorJudge;
        private boolean prevJudgeDesc;
        private long gen;
        private EventInfo[] lastSee;
        private EventInfo[] stronglySeeP;
        private EventInfo firstSelfWitnessS;
        private long votingRound;
        private EventInfo firstWitnessS;
        private EventInfo[] stronglySeeS1;
        private EventInfo[] voteE;
        private boolean[] voteB;
        private boolean isConsensus;
        private long consensusOrder;
        private Instant consensusTimestamp;
        private boolean isPrevJudge;

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

        /** EventInfo.update returns this (or null if consensus wasn't yet reached). */
        public record UpdateResults(
                List<EventInfo> consensusEvents, RoundStatePrev nextRoundStatePrev) {}

        /**
         * Mark this event as expired. It should eventually be called on every event, but only after it is ancient.
         * After being expired, any future call to updateEvent will return null.
         */
        public void expireEvent() {
            //to reduce garbage collection, these arrays could be saved and reused for the next new event.
            hashgraph = null;
            parentsSigned = null;
            ancestorJudge = null;
            lastSee = null;
            stronglySeeP = null;
            stronglySeeS1 = null;
            voteE = null;
            voteB = null;
        }

        /**
         * Instantiate the EventInfo object for an event. It is ok if the parents array is missing some or all
         * of the ancient parents. It is ok if the array contains some null elements. If an event has no
         * non-ancient parents, then it is ok to pass in null, or an array of all nulls, or an empty array.
         *
         * @param hashgraph which hashgraph this event belongs to (if multiple hashgraphs are simulated in memory)
         * @param creatorIndex the index into round state nodes[] (not the nodeID) of the creator of this event
         * @param parents array of parents, in the same order as in the signed event that is gossiped.
         */
        public EventInfo(@NonNull HashgraphInfo hashgraph, int creatorIndex, EventInfo[] parents) {
            this.hashgraph = hashgraph;
            this.creator = creatorIndex;
            this.parentsSigned = (parents != null) ? parents : new EventInfo[0];
        }

        /**
         * This should be called for each event, just before it is added to the hashgraph. When consensus is
         * reached for a round, it should then be called on all existing events for which prevJudgeDesc
         * is true. (It may set some of them to false). <p>
         *
         * This must be passed the complete round state for the pending round. <p>
         *
         * When there is a reconnect (or during PCES replay for a restart), there will be a period before all
         * the previous judges have been added to the hashgraph. Do not call updateEvent during that period.
         * It should only be called when all the judges from the previous round have been added to the hashgraph.
         * At that point, it should be called on all the non-ancient events in the hashgraph (not just those
         * with prevJudgeDesc==true, as it normally does for a new round).<p>
         *
         * This is sometimes called on a batch of events, because it is the start of a new round,
         * or because the last of the previous judges was finally added after a reset. For such a
         * batch, it should be called on all those events in topological order. So if it is to be called
         * on both an event and its parent, the call on the parent must come first.<p>
         *
         * This will only read (not write) roundState.
         * This will write to the EventInfo fields for this event, and perhaps other events.
         * For each event that reaches consensus, this will fill in its fields isConsensus,
         * consensusOrder, and consensusTimestamp.
         *
         * If the update of this event didn't reach consensus for this round, this will return null. If it did
         * reach consensus, this is a "keystone event". In that case, it returns an UpdateResults that contains
         * a (possibly empty) list of the events that reached consensus in this round, and the RoundStatePrev that
         * should be used for the next round.
         *
         * @param roundState the round state for the current pending round
         * @return the consensus results, or null if this event didn't decide this round
         */
        public UpdateResults update(@NonNull RoundState roundState) {
            // make the names look more like the r and x in the tech report
            final EventInfo x = this;
            final RoundStateCurr r = roundState.curr;
            final RoundStatePrev rp = roundState.prev;
            final HashgraphInfo h = hashgraph;
            long parentRound;

            if (hashgraph == null) {
                return null; //this event is expired
            }
            if (h.pendingRound != r.pendingRound) {
                h.pendingRound = r.pendingRound;
                h.numNodes = r.nodes.length;

                //set isPrevJudge to true for the judges in the previous round
                for (EventInfo judge : rp.prevJudges) {
                    judge.isPrevJudge = true;
                }

                // function totalStake
                h.totalStake = 0;
                for (long s : r.stake) {
                    h.totalStake += s;
                }

                // function minNonAncientRound
                h.minNonAncientRound =
                        Math.max(rp.prevMinNonAncientRound, rp.prevMinJudgeBirthRound - r.targetNumRoundsNonAncient);

                // function voteD
                {
                    long t = 0;
                    for (EventInfo judge : rp.prevJudges) {
                        t += r.stake[judge.creator];
                    }
                    h.voteD = (rp.prevJudgesCopied || (rp.prevJudgeCon1 && !r.judgeCon1) || !h.supermajority(t)) ? 2 : 1;
                }
            }

            // instantiate memos fields if they are null, or the array is the wrong size.
            if (x.ancestorJudge == null || x.ancestorJudge.length != h.numNodes) {
                x.ancestorJudge = new boolean[h.numNodes]; // only the first rp.prevJudges.length elements will be used
            }
            if (x.lastSee == null || x.lastSee.length != h.numNodes) {
                x.lastSee = new EventInfo[h.numNodes];
            }
            if (x.stronglySeeP == null || x.stronglySeeP.length != h.numNodes) {
                x.stronglySeeP = new EventInfo[h.numNodes];
            }
            if (x.stronglySeeS1 == null || x.stronglySeeS1.length != h.numNodes) {
                x.stronglySeeS1 = new EventInfo[h.numNodes];
            }
            if (x.voteE == null || x.voteE.length != h.numNodes) {
                x.voteE = new EventInfo[h.numNodes];
            }
            if (x.voteB == null || x.voteB.length != h.numNodes) {
                x.voteB = new boolean[h.numNodes];
            }

            // function parents
            // put in the parents array only those parents that are non-ancient descendents of judges in the prev round
            {
                // shrink to recover after branching
                if (h.parentsMaxSize > h.numNodes && x.parentsSigned.length < h.numNodes) {
                    h.parents = new ArrayList<>(h.numNodes);
                    h.parentsMaxSize = 0;
                }
                h.parents.clear();
                for (int i = 0; i < x.parentsSigned.length; i++) {
                    if (x.parentsSigned[i] != null && x.parentsSigned[i].prevJudgeDesc) {
                        h.parents.add(x.parentsSigned[i]);
                    }
                }
                h.parentsMaxSize = Math.max(h.parentsMaxSize, x.parentsSigned.length);
            }

            // function prevJudgeDesc
            x.prevJudgeDesc = x.isPrevJudge || h.pendingRound == 1;
            for (EventInfo parent : h.parents) {
                x.prevJudgeDesc |= parent.prevJudgeDesc;
            }

            // function ancestorJudge (for each y that is the index of the judge in prevJudge(r))
            for (int i = 0; i < rp.prevJudges.length; i++) {
                x.ancestorJudge[i] = (x == rp.prevJudges[i]);
                for (EventInfo parent : x.parentsSigned) {
                    if (parent.ancestorJudge[i]) {
                        x.ancestorJudge[i] = true;
                        break;
                    }
                }
            }

            // function gen
            {
                long t = 0;
                for (EventInfo parent : h.parents) {
                    t = Math.max(t, parent.gen);
                }
                x.gen = t + 1;
            }

            // function parentRound
            if (h.parents.isEmpty()) {
                parentRound = r.pendingRound - 1;
            } else {
                parentRound = 0;
                for (EventInfo parent : h.parents) {
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

            // if roundDecided is false, return null

            // function roundJudges

            // function receivedEvents

            // function isReceived

            // function reachedCon

            // function timeCon

            // function before

            // function isConsensus

            // function consensusOrder

            // function consensusTimestamp

            //set isPrevJudge to false for the judges in the previous round
            for (EventInfo judge : rp.prevJudges) {
                judge.isPrevJudge = true;
            }

            //TODO set isPrevJudge to true for roundJudges (the judges that were just found)

            return new UpdateResults(
                    null,
                    new RoundStatePrev(false,
                            null,
                            false,
                            0,
                            0,
                            0));
        }
    }
}
