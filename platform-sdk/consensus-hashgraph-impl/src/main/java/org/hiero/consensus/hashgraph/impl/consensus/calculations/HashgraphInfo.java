// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.consensus.calculations;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * This class implements the Hashgraph consensus algorithm. It is self-contained, with
 * no dependencies other than on the standard Java libraries. The HashgraphInfo class
 * contains the inner class EventInfo and the record types RoundInfo, RoundInfoCore,
 * and RoundInfoPrev. All 5 of these have getters but no setters. After the caller instantiates
 * these classes and records, passing arrays to the constructors, the contents of those arrays must
 * never be changed. This file implements the equations from the tech report Swirlds-TR-2026-01.<p>
 *
 * A single HashgraphInfo should be instantiated for the hashgraph. If several hashgraphs
 * exist, such as for a simulation of multiple nodes, then there should be one per hashgraph.<p>
 *
 * An EventInfo should be instantiated for each event. The update methods for all the events are
 * called to calculate consensus. At some time after an event becomes
 * ancient, it should have its clear method called to clean up memory by erasing all its
 * references to even older events. This can happen immediately after it becomes ancient, or
 * many rounds later when it expires, or at any other time after becoming ancient.<p>
 *
 * For a larger program to use the Hashgraph consensus algorithm, it should include this class.
 * It should instantiate a RoundInfo for the pending round (the round for which consensus is
 * currently being calculated). It should instantiate the RoundInfoCore for the next several rounds.
 * After a round reaches consensus, its RoundInfoPrev is calculated, which can be combined with the
 * RoundInfoCore to form the RoundInfo for the next round.<p>
 *
 * The network's overall consensus state should include the RoundInfo for the pending round (the round currently
 * being calculated), and the RoundInfoCore for the next few rounds. An implementation might also include a "roster"
 * for these and for all the non-ancient previous rounds. The roster might contain info such as
 * the public keys for all the nodes, used to verify their signatures. This class doesn't
 * use rosters. It only uses the RoundInfo, and its two parts.<p>
 *
 * For a hashgraph, this class should be instantiated once. The EventInfo.update method should be
 * called on each event for each round, according to the schedule described in the comments
 * for the update method. <p>
 *
 * If a call to EventInfo.update returns a non-null value, then the event caused consensus to be
 * reached for that round (a "keystone event"). In that case, it returns a record that contains
 * the list of all the events that reached consensus in that round.
 * Which might be an empty list if none reached consensus. It also contains a
 * RoundInfoPrev record, which should be used to build the RoundInfo for the next round.
 */
public class HashgraphInfo {
    // these fields are used in EventInfo.update and are updated the first time it is called
    // with any given pending round.
    private long pendingRound;
    private int numNodes;
    private long[] nodeIDs;
    private Map<Long, Integer> nodeIdToIndex;
    private long totalStake;
    private long minNonAncientRound;
    private int voteD; // must be 1 or 2
    private ArrayList<EventInfo> parents = new ArrayList<>();
    private EventInfo selfParent;
    private int parentsMaxSize = 0; //largest parents has ever been (used to recover from massive branching)
    private boolean nodesChanged; //true for round 1 and for any round where nodes[] differs from the round before it

    private boolean roundDecided;

    /** info about a round that is known multiple rounds in advance */
    public record RoundInfoCore(
            long pendingRound,
            long[] nodes, // NodeID for each node
            long[] stake,
            int coinInterval,
            boolean firstVotingRoundSee,
            boolean judgeCon1,
            int targetNumRoundsNonAncient,
            int numRoundsAddressBook) {}

    /** info about a round that is only available when the previous round reaches consensus */
    public record RoundInfoPrev(
            boolean prevJudgeCon1,
            EventInfo[] prevJudges,
            boolean prevJudgesCopied,
            long prevMinNonAncientRound,
            long prevNumCons,
            long prevMinJudgeBirthRound) {}

    /** The round info with info about nodes, weights, previous judges, per-round
     * setting, etc. The consensus state should include the full RoundInfo for the
     * pending round, and the RoundInfoCore portion of it for the next few rounds.
     */
    public record RoundInfo(RoundInfoCore curr, RoundInfoPrev prev) {}

    /** true iff n is a supermajority of the total stake */
    private boolean supermajority(long n) {
        return n > 2 * totalStake / 3;
    }

    /**
     * Given the round info for the pending round, set the isConsensus fields for all the non-ancient events that
     * reached consensus in previous rounds. This is only needed after a restart or reconnect. In normal operation,
     * those events will already have been marked, when they reached consensus.
     *
     * @param currRoundInfo round info for the pending round (the round currently being calculated)
     */
    public void setPrevIsConsensus(RoundInfo currRoundInfo) {
        // TODO
    }

    /**
     * A class with the per-event scratchpad data.
     * This is the memoized data for the functions marked to be memoized in the tech report.
     */
    public static class EventInfo {
        private HashgraphInfo hashgraph;
        private final long creatorNodeID; //nodeID of this event's creator
        private EventInfo[] parentsSigned;
        private int creator; //index into the nodes array of this event's creator
        private final long birthRound;
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

        /** True iff this event is the descendent of at least one judge from the previous round. */
        public boolean getPrevJudgeDesc() {
            return prevJudgeDesc;
        }
        /** True iff this event has reached consensus. (If false, it may still reach consensus later). */
        public boolean getIsConsensus() {
            return isConsensus;
        }
        /** The consensus order of this event, starting at 1 for genesis (or 0 if getIsConsensus is false). */
        public long getConsensusOrder() {
            return consensusOrder;
        }
        /** The consensus timestamp for this event (or null if getIsConsensus is false). */
        public Instant getConsensusTimestamp() {
            return consensusTimestamp;
        }

        /**
         * Instantiate the EventInfo object for an event. The parents array should contain the parents in the same
         * order as they are listed in the signed event that is gossiped. If there is a self-parent in the array, it
         * must be in the first position. It is ok if the parents array is missing some or all of the ancient parents.
         * It is ok if the array contains some null elements. If an event has no non-ancient parents, then it is ok
         * to pass in null, or an array of all nulls, or an empty array.
         *
         * @param hashgraph which hashgraph this event belongs to (if multiple hashgraphs are simulated in memory)
         * @param creator the nodeID of the creator of this event
         * @param parents array of parents, in the same order as in the signed event that is gossiped.
         */
        public EventInfo(@NonNull HashgraphInfo hashgraph, long creator, long birthRound, EventInfo[] parents) {
            this.hashgraph = hashgraph;
            this.creatorNodeID = creator;
            this.birthRound = birthRound;
            this.parentsSigned = (parents != null) ? parents : new EventInfo[0];
        }

        /**
         * Erase all references from this event to its ancestor events. It should eventually be called on every event,
         * but only after it is ancient. After being cleared, any future call to update will return null. This must be
         * called eventually on every event, to allow the garbage collector to free memory.
         */
        public void clear() {
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

        /** EventInfo.update returns this (or null if consensus wasn't yet reached). */
        public record UpdateResults(
                List<EventInfo> consensusEvents, RoundInfoPrev nextRoundInfoPrev) {}

        /**
         * This should be called for each event, just before it is added to the hashgraph. When consensus is
         * reached for a round, it should then be called on all existing events for which prevJudgeDesc
         * is true. (It may set some of them to false). <p>
         *
         * This must be passed the complete round info for the pending round (the round currently being calculated). <p>
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
         * This will only read (not write) roundInfo.
         * This will write to the EventInfo fields for this event, and perhaps other events.
         * For each event that reaches consensus, this will fill in its fields isConsensus,
         * consensusOrder, and consensusTimestamp.<p>
         *
         * If the update of this event didn't reach consensus for this round, this will return null. If it did
         * reach consensus, this is a "keystone event". In that case, it returns an UpdateResults that contains
         * a (possibly empty) list of the events that reached consensus in this round and the RoundInfoPrev that
         * should be used for the next round. <p>
         *
         * When this method is called for the first time on a new hashgraph in memory, it can be passed
         * any roundInfo. In every future call, it must be passed a roundInfo that either has the same
         * pendingRound as the previous call or has a pendingRound that is one greater than the previous call.
         *
         * @param roundInfo the round info for the pending round (the round currently being calculated)
         * @return the consensus results, or null if this event didn't decide this round
         */
        public UpdateResults update(@NonNull RoundInfo roundInfo) {
            // make the names look more like the r and x in the tech report
            final EventInfo x = this;
            final RoundInfoCore r = roundInfo.curr;
            final RoundInfoPrev rp = roundInfo.prev;
            final HashgraphInfo h = x.hashgraph;
            long parentRound;
            ArrayList<EventInfo> roundJudges;
            long minJudgeBirthRound;

            if (hashgraph == null) {
                return null; //this event was already cleared
            }
            //if this is a new round (or the first round called on this hashgraph), calculate all the functions of round
            if (h.pendingRound != r.pendingRound) {
                h.pendingRound = r.pendingRound;
                h.numNodes = r.nodes.length;

                //set h.nodesChanged to true if the node array changed this round (or it's the first time called).
                if (h.nodeIDs == null || h.nodeIDs.length != r.nodes.length) {
                    h.nodesChanged = true;
                } else {
                    for (int i=0; i<h.numNodes; i++) {
                        if (h.nodeIDs[i] != r.nodes[i]) {
                            h.nodesChanged = true;
                            break;
                        }
                    }
                }
                //if it did change, then update the nodeIdToIndex to map from nodeID to index
                if (h.nodesChanged) {
                    h.nodeIDs = r.nodes;
                    if (h.nodeIdToIndex == null) {
                        h.nodeIdToIndex = new HashMap<>();
                    } else {
                        h.nodeIdToIndex.clear();
                    }
                    for (int i=0; i<h.numNodes; i++) {
                        h.nodeIdToIndex.put(h.nodeIDs[i], i);
                    }
                }

                //if this is the first time this event has updated, or if this round has a changed address book,
                //then recalculate the index for the creator
                if (x.lastSee == null || h.nodesChanged) {
                    x.creator = h.nodeIdToIndex.get(x.creatorNodeID);
                }

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
                    h.voteD = (rp.prevJudgesCopied || (rp.prevJudgeCon1 && !r.judgeCon1)
                            || !h.supermajority(t)) ? 2 : 1;
                }
            }

            // if this is a round where the address book changed, or if this is the first time this event has
            // ever been updated, then recalculate the creator index
            if (h.nodesChanged || x.lastSee == null) {
                x.creator = h.nodeIdToIndex.get(x.creatorNodeID);
            }

            // instantiate fields if they are null, or the array is the wrong size.
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
            // put in the h.parents array only those parents that are non-ancient descendents of judges in the prev round
            if (h.parentsMaxSize > h.numNodes && x.parentsSigned.length < h.numNodes) {
                // shrink to recover after branching
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
            h.selfParent = (h.parents.isEmpty()
                    || h.parents.getFirst().creator != x.creator) ? null : h.parents.getFirst();

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
            for (int m = 0; m < h.numNodes; m++) {
                if (m == x.creator) {
                    x.lastSee[m] = x;
                } else {
                    //find k = max(map(s1,votingRound))
                    long k = 1; //start at 1 to ensure max({}) = 1
                    for (EventInfo parent : h.parents) {
                        EventInfo y = parent.lastSee[m];
                        if (y != null && y.votingRound > k) {
                            k = y.votingRound;
                        }
                    }
                    //find w = firstSelfWitness(r,first(s2))
                    //find p = event in s3 with the max gen
                    EventInfo w = null;
                    EventInfo p = null;
                    boolean s2empty = true;
                    long maxGen = -1;
                    for (EventInfo parent : h.parents) {
                        EventInfo y = parent.lastSee[m];
                        //y is in s1
                        if (y != null && y.votingRound == k) {
                            //y is in s2
                            //w comes from first(s2), so only set it once
                            w = (w != null) ? w : y.firstSelfWitnessS;
                            s2empty = false;
                            if (y.firstSelfWitnessS == w) {
                                //y is in s3
                                if (y.gen > maxGen) {
                                    maxGen = y.gen;
                                    //p is the first max gen element in s3
                                    p = y;
                                }
                            }
                        }
                    }
                    x.lastSee[m] = s2empty ? null : p;
                }
            }

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
            //h.roundDecided = false; /**/

            if (!h.roundDecided) {
                return null;
            }

            // function roundJudges
            roundJudges = new ArrayList<EventInfo>;  /**/

            // function receivedEvents

            // function isReceived

            // function reachedCon

            // function timeCon

            // function before

            // function isConsensus

            // function consensusOrder

            // function consensusTimestamp

            //the round reached consensus, so prepare to move on by setting the old judges to false and the new to true
            for (EventInfo judge : rp.prevJudges) {
                judge.isPrevJudge = false;
            }
            for (EventInfo judge : roundJudges) {
                judge.isPrevJudge = true;
            }
            minJudgeBirthRound = 0;
            for (EventInfo judge : roundJudges) {
                minJudgeBirthRound = Math.max(minJudgeBirthRound, judge.birthRound);
            }

            return new UpdateResults(
                    null, //consensusEvents
                    new RoundInfoPrev(
                            r.judgeCon1, //prevJudgeCon1
                            roundJudges.toArray(new EventInfo[0]), //prevJudges
                            false,
                            0, //prevMinNonAncientRound
                            0,
                            minJudgeBirthRound)); //prevMinJudgeBirthRound
        }
    }
}
