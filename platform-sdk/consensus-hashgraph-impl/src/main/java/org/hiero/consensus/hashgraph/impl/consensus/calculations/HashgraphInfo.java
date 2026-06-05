// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.consensus.calculations;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * This package, {@link org.hiero.consensus.hashgraph.impl.consensus.calculations ...consensus.calculations}, contains
 * a single file that does all the consensus calculations for the Hashgraph consensus algorithm. It is self-contained,
 * with no dependencies other than on the standard Java libraries. There is one class with several
 * inner classes and record types:
 * <ul>
 *   <li>{@link HashgraphInfo HashgraphInfo} has all the information about the hashgraph that is needed for the
 *   consensus calculations.
 *   <li> {@link EventInfo EventInfo} has all the information about an event that is needed for the
 *   consensus calculations.
 *   <li> {@link RoundInfo RoundInfo}, and {@link RoundInfoPrev RoundInfoPrev} together have all the
 *   information about a round that is needed for the consensus calculations.
 * </ul>
 * There are constructors and getters, but no setters. Other than that, there are only two public methods:
 * {@link EventInfo#update EventInfo.update()}, which updates an event with a set of calculations, and
 * {@link EventInfo#clear EventInfo.clear()}, which erases references in it when it is time to discard it.
 * For arrays passed to the constructors, the caller must never change any array elements.
 * This file implements the equations from the tech report Swirlds-TR-2026-01.
 * <p>
 * A single {@link HashgraphInfo HashgraphInfo} should be instantiated for the hashgraph. If several hashgraphs
 * exist, such as for a simulation of multiple nodes, then there should be one per hashgraph.
 * <p>
 * An {@link EventInfo EventInfo} should be instantiated for each event. The update method is called on all the events
 * to calculate consensus. At some time after an event becomes
 * ancient, it should have its {@link EventInfo#clear EventInfo.clear()} method called to clean up memory by erasing
 * all its references to older events. This can happen immediately after it becomes ancient, or
 * many rounds later when it expires, or at any other time after becoming ancient.
 * <p>
 * For a larger program to use the Hashgraph consensus algorithm, it should include this class.
 * It should instantiate a {@link RoundInfo RoundInfo} and {@link RoundInfoPrev RoundInfoPrev} for the pending round
 * (the round for which consensus is currently being calculated). After a round reaches consensus, a new
 * {@link RoundInfoPrev RoundInfoPrev} is calculated and returned by {@link EventInfo#update EventInfo.update()},
 * which can then be used for the next round.
 * <p>
 * The network's overall consensus state should include the {@link RoundInfoPrev RoundInfoPrev} for the pending round
 * (the round currently being calculated), and the {@link RoundInfo RoundInfo} for it and the next few rounds.
 * An implementation might also include a "roster" for these and for all the non-ancient previous rounds. The roster
 * might contain info such as the public keys for all the nodes, used to verify their signatures. This class doesn't
 * use rosters. It only uses the two round info records.
 * <p>
 * If a call to {@link EventInfo#update EventInfo.update} returns a non-null value, then the event caused consensus to
 * be reached for that round (a "keystone event"). In that case, it returns a record that contains
 * the list of all the events that reached consensus in that round. Which might be an empty list if none reached
 * consensus. It also contains a {@link RoundInfoPrev RoundInfoPrev} record, which should be used in further
 * calls to {@link EventInfo#update update()} in the next round.
 */
public class HashgraphInfo {
    // EventInfo.update uses these and updates them the first time it is called with any given pending round.
    private long pendingRound;
    private int numNodes;
    private long[] nodeIDs;
    private HashMap<Long, Integer> nodeIdToIndex;
    private long totalStake;
    private long minNonAncientRound;
    private int voteD; // must be 1 or 2
    private ArrayList<EventInfo> parents = new ArrayList<>();
    private EventInfo selfParent;
    private int parentsMaxSize = 0; // largest parents has ever been (used to recover from massive branching)
    private boolean nodesChanged; // true for round 1 and for any round where nodes[] differs from the round before it
    private int currMark = 0;
    private boolean roundDecided;

    // the following getters are just for debugging, monitoring, etc. Normal code should not rely on them.

    /* //for the moment, I'll comment these out to ensure our integration doesn't accidentally rely on them

    public long getPendingRound() {
        return pendingRound;
    }

    public int getNumNodes() {
        return numNodes;
    }

    public long[] getNodeIDs() {
        return nodeIDs;
    }

    public HashMap<Long, Integer> getNodeIdToIndex() {
        return nodeIdToIndex;
    }

    public long getTotalStake() {
        return totalStake;
    }

    public long getMinNonAncientRound() {
        return minNonAncientRound;
    }

    public int getVoteD() {
        return voteD;
    }

    public ArrayList<EventInfo> getParents() {
        return parents;
    }

    public EventInfo getSelfParent() {
        return selfParent;
    }

    public int getParentsMaxSize() {
        return parentsMaxSize;
    }

    public boolean isNodesChanged() {
        return nodesChanged;
    }

    public int getCurrMark() {
        return currMark;
    }

    public boolean isRoundDecided() {
        return roundDecided;
    }
    */

    /** Info about a round that might be known multiple rounds in advance. No element can be null. */
    public record RoundInfo(
            long pendingRound, // this record is round information for this round number
            long[] nodes, // NodeID for each node
            long[] stake,
            int coinInterval,
            boolean firstVotingRoundSee,
            boolean judgeCon1,
            int targetNumRoundsNonAncient,
            int numRoundsAddressBook) {}

    /** Info about a round that is only available when the previous round reaches consensus. No element can be null. */
    public record RoundInfoPrev(
            long pendingRound, // this record is round information for this round number, regarding the one before
            boolean prevJudgeCon1,
            EventInfo[] prevJudges,
            boolean prevJudgesCopied,
            long prevMinNonAncientRound,
            long prevNumCons,
            long prevMinJudgeBirthRound) {}

    /** true iff n is a supermajority of the total stake*/
    private boolean supermajority(long n) {
        return n > 2 * totalStake / 3;
    }

    /**
     * A class with the per-event scratchpad data used for calculating consensus. This is data for the functions
     * marked to be memoized in the tech report. Call {@link EventInfo#update EventInfo.update} to update the data
     * and call {@link EventInfo#clear EventInfo.clear} to clear the data.
     */
    public static class EventInfo {
        private HashgraphInfo hashgraph;
        private final long creatorNodeID; // nodeID of this event's creator
        private final Instant timeCreated;
        private EventInfo[] parentsSigned;
        private int creator; // index into the nodes array of this event's creator
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
        private boolean prevJudge;
        private long maxJudgeRound;
        // the following are used for graph searches in the hashgraph
        private long searchMark; // mark visited events so depth-first search backtracks when revisiting it
        private int searchCount; // number of judges that are descendents of this event
        private int searchParent; // index of the parent of this event currently being searched
        private EventInfo searchChild; // the child of this event through which it was reached

        /**
         * Constructor for the {@link EventInfo EventInfo} object for an event. The parents array should contain the
         * parents in the same order as they are listed in the signed event that is gossiped. If there is a self-parent
         * in the array, it must be in the first position. It is ok if the parents array is missing some or all of the
         * ancient parents. It is ok if the array contains some null elements. If an event has no non-ancient parents,
         * then it is ok to pass in null, or an array of all nulls, or an empty array.
         *
         * @param hashgraph   which hashgraph this event belongs to (if multiple hashgraphs are simulated in memory)
         * @param creator     the nodeID of the creator of this event
         * @param timeCreated when this event was created, as claimed by its creator node
         * @param parents     array of parents, in the same order as in the signed event that is gossiped.
         */
        public EventInfo(
                @NonNull HashgraphInfo hashgraph,
                long creator,
                @NonNull Instant timeCreated,
                long birthRound,
                EventInfo[] parents) {
            this.hashgraph = hashgraph;
            this.creatorNodeID = creator;
            this.timeCreated = timeCreated;
            this.birthRound = birthRound;
            this.parentsSigned = (parents != null) ? parents : new EventInfo[0];
        }

        /**
         * True iff this event has reached consensus. (If false, it may still reach consensus later).
         */
        public boolean getIsConsensus() {
            return isConsensus;
        }

        /**
         * The consensus order of this event, starting at 1 for genesis (or 0 if getIsConsensus is false).
         */
        public long getConsensusOrder() {
            return consensusOrder;
        }

        /**
         * The consensus timestamp for this event (or null if getIsConsensus is false).
         */
        public Instant getConsensusTimestamp() {
            return consensusTimestamp;
        }

        // the following getters are just for debugging, monitoring, etc. Normal code should not rely on them.

        /* //for the moment, I'll comment these out, to ensure our integration doesn't accidentally rely on them
        public HashgraphInfo getHashgraph() {
            return hashgraph;
        }

        public long getCreatorNodeID() {
            return creatorNodeID;
        }

        public Instant getTimeCreated() {
            return timeCreated;
        }

        public EventInfo[] getParentsSigned() {
            return parentsSigned;
        }

        public int getCreator() {
            return creator;
        }

        public long getBirthRound() {
            return birthRound;
        }

        public boolean[] getAncestorJudge() {
            return ancestorJudge;
        }

        public boolean getPrevJudgeDesc() {
            return prevJudgeDesc;
        }

        public long getGen() { // this is the dGen
            return gen;
        }

        public EventInfo[] getLastSee() {
            return lastSee;
        }

        public EventInfo[] getStronglySeeP() {
            return stronglySeeP;
        }

        public EventInfo getFirstSelfWitnessS() {
            return firstSelfWitnessS;
        }

        public long getVotingRound() {
            return votingRound;
        }

        public EventInfo getFirstWitnessS() {
            return firstWitnessS;
        }

        public EventInfo[] getStronglySeeS1() {
            return stronglySeeS1;
        }

        public EventInfo[] getVoteE() {
            return voteE;
        }

        public boolean[] getVoteB() {
            return voteB;
        }

        public boolean getPrevJudge() {
            return prevJudge;
        }

        public long getMaxJudgeRound() {
            return maxJudgeRound;
        }

        public long getSearchMark() {
            return searchMark;
        }

        public int getSearchCount() {
            return searchCount;
        }

        public int getSearchParent() {
            return searchParent;
        }

        public EventInfo getSearchChild() {
            return searchChild;
        }
        */

        /**
         * Erase all references from this event to its ancestor events. It should eventually be called on every event,
         * but only after it is ancient. It also sets to null the reference to the hashgraph, so after being cleared,
         * any future call to update will return null. This must be called eventually on every event, to allow the
         * garbage collector to free memory.
         * <p>
         * As an optimization, this also clears all references to all arrays and objects, not just to
         * {@link EventInfo EventInfo} objects.
         */
        public void clear() {
            // To reduce garbage collection, the arrays could be saved and reused for the next new event.
            hashgraph = null;
            parentsSigned = null;
            ancestorJudge = null;
            lastSee = null;
            stronglySeeP = null;
            firstSelfWitnessS = null;
            firstWitnessS = null;
            stronglySeeS1 = null;
            voteE = null;
            voteB = null;
            consensusTimestamp = null;
            searchChild = null;
        }

        /**
         * {@link EventInfo#update EventInfo.update} returns this (or null if consensus wasn't yet reached).
         */
        public record UpdateResults(EventInfo[] consensusEvents, RoundInfoPrev nextRoundInfoPrev) {}

        /**
         * This should be called for each event just after it is added to the hashgraph. When consensus is
         * reached for a round, then switch to the round info for the next round. Using it, call update on
         * all the judges that were just found, and their descendents. Stop updating them if one of those calls
         * reaches consensus.
         * <p>
         * When starting with an empty hashgraph after a reconnect or restart, there will be a period before all
         * the previous judges have been added to the hashgraph. Do not call this update function
         * during that period. Once all the judges in {@link RoundInfoPrev#prevJudges RoundInfoPrev.prevJudges}
         * have been added to the hashgraph, then call update on all the judges that were just added, and their
         * descendents. Stop updating them if one of those calls reaches consensus.
         * <p>
         * This is sometimes called on a batch of events, because it is the start of a new round,
         * or because the last of the previous judges was finally added after a restart. For such a
         * batch, it should be called on all those events in topological order. So if it is to be called
         * on both an event and its parent, the call on the parent must come first.
         * <p>
         * This will write to the private {@link EventInfo EventInfo} fields of this event, and sometimes of other
         * events. It may also write to some private fields of the {@link HashgraphInfo HashgraphInfo}.
         * For each event that reaches consensus, this will fill in its fields for the {@link EventInfo EventInfo}
         * getters {@link EventInfo#isConsensus isConsensus}, {@link EventInfo#getConsensusOrder getConsensusOrder},
         * and {@link EventInfo#getConsensusTimestamp getConsensusTimestamp}.
         * <p>
         * If the update of this event didn't reach consensus for this round, this will return null. If it did
         * reach consensus, this is a "keystone event". In that case, it returns an {@link UpdateResults UpdateResults}
         * that contains a (possibly empty) list of the events that reached consensus in this round, and the
         * {@link RoundInfoPrev RoundInfoPrev} that should be used for the next round.
         * <p>
         * When this method is called for the first time on a new hashgraph in memory, it can be passed
         * the {@link RoundInfo roundInfo} for the pending round at that time. In every future call, each call to it
         * must be passed a {@link RoundInfo RoundInfo} that either has the same
         * {@link RoundInfo#pendingRound RoundInfoCore.pendingRound} as in the previous call, or has a
         * {@link RoundInfo#pendingRound RoundInfoCore.pendingRound} that is one greater than in the previous call.
         *
         * @param roundInfo info about the pending round (e.g., the nodes, weights, various settings)
         * @param roundInfoPrev info about the pending round reflecting the previous round (e.g., judges, old settings)
         * @return the consensus results, or null if this event didn't decide this round
         */
        public UpdateResults update(@NonNull RoundInfo roundInfo, @NonNull RoundInfoPrev roundInfoPrev) {
            // make the names look more like the r and x in the tech report
            final EventInfo x = this;
            final RoundInfo r = roundInfo;
            final RoundInfoPrev rp = roundInfoPrev;
            final HashgraphInfo h = x.hashgraph;
            long parentRound;
            ArrayList<EventInfo> roundJudges;
            ArrayList<EventInfo> consensusEvents;
            long minJudgeBirthRound;

            if (hashgraph == null) {
                throw new IllegalArgumentException("Event was already cleared");
            }
            if (roundInfo.pendingRound != roundInfoPrev.pendingRound) {
                throw new IllegalArgumentException("roundInfo.pendingRound != roundInfoPrev.pendingRound");
            }
            if (roundInfo.pendingRound != h.pendingRound
                    && roundInfo.pendingRound != h.pendingRound + 1
                    && h.pendingRound != 0) {
                throw new IllegalArgumentException(
                        "roundInfo.pendingRound didn't match the last call, nor increment by 1");
            }

            // If this is the first time update has ever been called on an event for this hashgraph, and round > 1,
            // then set isConsensus for all the ancestors of the previous round's judges, according to judgeCons1 for it
            if (h.pendingRound == 0 && roundInfo.pendingRound > 1) {
                // events reach consensus when they are an ancestor of this many judges
                int targetCount = roundInfoPrev.prevJudgeCon1 ? 1 : roundInfoPrev.prevJudges.length;
                // mark used while searching from the first judge (later judges' marks are greater)
                int firstMark = h.currMark + 1;
                // calculate h.minNonAncientRound /**/
                for (EventInfo e : roundInfoPrev.prevJudges) { // depth-first search starting from each judge
                    h.currMark++;
                    e.searchChild = null; // backtracking up from this e means the search is done
                    do { // depth-first search starting from this judge
                        // e is ancestor of this many judges so far (1 if the mark is lower than the first judge's)
                        e.searchCount = (e.searchMark < firstMark) ? 1 : e.searchCount + 1;
                        e.searchMark = h.currMark;
                        e.searchParent = -1; // descend through the first parent first (index 0)
                        if (e.searchCount == targetCount) {
                            e.isConsensus = true; // SUCCESS: found an event that is an ancestor of enough judges
                        }
                        EventInfo eNew = null;
                        // while eNew is bad (null / ancient / marked), search until a good one is found or done
                        while (e != null
                                && (eNew == null
                                        || eNew.birthRound < h.minNonAncientRound
                                        || eNew.searchMark == h.currMark)) {
                            while (e != null && e.searchParent >= e.parentsSigned.length - 1) {
                                e = e.searchChild; // backtrack up until an event is found with an unexplored parent
                            }
                            if (e != null) {
                                e.searchParent++;
                            }
                            eNew = (e == null) ? null : e.parentsSigned[e.searchParent];
                        }
                        e = eNew; // move to the new event that was good
                    } while (e != null); // once we backtrack to null, the search from this judge is done
                }
            }

            // if this is a new round (or the first round called on this hashgraph), calculate all HashgraphInfo fields
            if (h.pendingRound != r.pendingRound) {
                h.pendingRound = r.pendingRound;
                h.numNodes = r.nodes.length;

                // set h.nodesChanged to true if the node array changed this round (or it's the first time called).
                if (h.nodeIDs == null || h.nodeIDs.length != r.nodes.length) {
                    h.nodesChanged = true;
                } else {
                    for (int i = 0; i < h.numNodes; i++) {
                        if (h.nodeIDs[i] != r.nodes[i]) {
                            h.nodesChanged = true;
                            break;
                        }
                    }
                }
                // if it did change, then update the nodeIdToIndex to map from nodeID to index
                if (h.nodesChanged) {
                    h.nodeIDs = r.nodes;
                    if (h.nodeIdToIndex == null) {
                        h.nodeIdToIndex = new HashMap<>();
                    } else {
                        h.nodeIdToIndex.clear();
                    }
                    for (int i = 0; i < h.numNodes; i++) {
                        h.nodeIdToIndex.put(h.nodeIDs[i], i);
                    }
                }

                // if this is the first time this event has updated, or if this round has a changed address book,
                // then recalculate the index for the creator
                if (x.lastSee == null || h.nodesChanged) {
                    x.creator = h.nodeIdToIndex.get(x.creatorNodeID);
                }

                // set isPrevJudge to true for the judges in the previous round
                for (EventInfo judge : rp.prevJudges) {
                    judge.prevJudge = true;
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
                    h.voteD =
                            (rp.prevJudgesCopied || (rp.prevJudgeCon1 && !r.judgeCon1) || !h.supermajority(t)) ? 2 : 1;
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
            // put in the h.parents array only those parents that are non-ancient descendents of judges in the prev
            // round
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
            h.selfParent =
                    (h.parents.isEmpty() || h.parents.getFirst().creator != x.creator) ? null : h.parents.getFirst();

            // function prevJudgeDesc
            // also set maxJudgeRound, which is the max round of all judges that are ancestors of x, or 1 if none.
            x.maxJudgeRound = x.prevJudge ? (r.pendingRound - 1) : 1;
            for (EventInfo parent : h.parents) {
                x.maxJudgeRound = Math.max(x.maxJudgeRound, parent.maxJudgeRound);
            }
            x.prevJudgeDesc = (x.maxJudgeRound >= r.pendingRound - 1); // use alg in comments in paper, not the equation

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
                    // find k = max(map(s1,votingRound))
                    long k = 1; // start at 1 to ensure max({}) = 1
                    for (EventInfo parent : h.parents) {
                        EventInfo y = parent.lastSee[m];
                        if (y != null && y.votingRound > k) {
                            k = y.votingRound;
                        }
                    }
                    EventInfo w = null; // find w = firstSelfWitness(r,first(s2))
                    EventInfo p = null; // find p = event in s3 with the max gen
                    boolean s2empty = true;
                    long maxGen = -1;
                    for (EventInfo parent : h.parents) {
                        EventInfo y = parent.lastSee[m];
                        // y is in s1
                        if (y != null && y.votingRound == k) {
                            // y is in s2
                            // w comes from first(s2), so only set it once
                            w = (w != null) ? w : y.firstSelfWitnessS;
                            s2empty = false;
                            if (y.firstSelfWitnessS == w) {
                                // y is in s3
                                if (y.gen > maxGen) {
                                    maxGen = y.gen;
                                    // p is the first max gen element in s3
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

            if (!h.roundDecided) {
                return null;
            }
            consensusEvents = new ArrayList<>();

            // function roundJudges
            roundJudges = new ArrayList<>();

            // function receivedEvents

            // function isReceived

            // function reachedCon

            // function timeCon

            // function before

            // function isConsensus

            // function consensusOrder

            // function consensusTimestamp

            // the round reached consensus, so prepare to move on by setting the old judges to false and the new to true
            for (EventInfo judge : rp.prevJudges) {
                judge.prevJudge = false;
            }
            for (EventInfo judge : roundJudges) {
                judge.prevJudge = true;
            }
            minJudgeBirthRound = 0;
            for (EventInfo judge : roundJudges) {
                minJudgeBirthRound = Math.max(minJudgeBirthRound, judge.birthRound);
            }

            return new UpdateResults(
                    consensusEvents.toArray(new EventInfo[0]), // consensusEvents
                    new RoundInfoPrev(
                            h.pendingRound + 1, // pendingRound
                            r.judgeCon1, // prevJudgeCon1
                            roundJudges.toArray(new EventInfo[0]), // prevJudges
                            false, // prevJudgesCopied /**/
                            h.minNonAncientRound, // prevMinNonAncientRound
                            rp.prevNumCons + consensusEvents.size(), // prevNumCons
                            minJudgeBirthRound)); // prevMinJudgeBirthRound
        }
    }
}
