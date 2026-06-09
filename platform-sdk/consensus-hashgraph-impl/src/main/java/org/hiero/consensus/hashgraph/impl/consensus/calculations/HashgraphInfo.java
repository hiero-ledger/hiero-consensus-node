// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.consensus.calculations;

import com.swirlds.config.api.validation.annotation.Max;
import com.swirlds.config.api.validation.annotation.Min;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;

/**
 * This package contains a single file that does all the consensus calculations for the Hashgraph consensus algorithm.
 * It is self-contained, with no dependencies other than on the standard Java libraries. In this package named
 * {@link org.hiero.consensus.hashgraph.impl.consensus.calculations ...consensus.calculations}
 * there is one class with several inner classes and record types:
 * <ul>
 *   <li>{@link HashgraphInfo HashgraphInfo} has all the information about the hashgraph that is needed for the
 *   consensus calculations.
 *   <li> {@link EventInfo EventInfo} has all the information about an event that is needed for the
 *   consensus calculations.
 *   <li> {@link RoundInfo RoundInfo}, and {@link RoundInfoPrev RoundInfoPrev} together have all the
 *   information about a round that is needed for the consensus calculations.
 * </ul>
 * WARNING: For arrays passed to the constructors, the caller must never change any array elements. The arrays must
 * be treated as immutable objects.
 * <p>
 * There are constructors and getters, but no setters, and no public fields. Other than that, there are only 3 public
 * methods: the static method {@link HashgraphInfo#minNonAncientRound HashgraphInfo.minNonAncientRound()},
 * which gives the minimum birth round that is not ancient,
 * {@link EventInfo#update EventInfo.update()}, which updates an event with the consensus calculations, and
 * {@link EventInfo#clear EventInfo.clear()}, which erases references in it when it is time to discard it.
 * This file implements the equations from the tech report Swirlds-TR-2026-01. Search this file for "/-" to
 * find all the function equations from that paper that are implemented.
 * <p>
 * A single {@link HashgraphInfo HashgraphInfo} should be instantiated for the hashgraph. If several hashgraphs
 * exist, such as for a simulation of multiple nodes, then there should be one per hashgraph.
 * <p>
 * An {@link EventInfo EventInfo} should be instantiated for each event. The
 * {@link EventInfo#update EventInfo.update()} method is called on all the events to calculate consensus. At some
 * time after an event becomes ancient, it should have its {@link EventInfo#clear EventInfo.clear()} method called to
 * clean up memory by erasing all its references to older events. This can happen immediately after it becomes
 * ancient, or many rounds later when it expires, or at any other time after becoming ancient.
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
public final class HashgraphInfo {
    /** for round 1 (the genesis round) use this as the RoundInfoPrev record */
    public final RoundInfoPrev firstRoundInfoPrev
            = new RoundInfoPrev(1, false, new EventInfo[0],
            false, 0, 0, 0);

    // EventInfo.update uses these and updates them the first time it is called with any given pending round.
    private long pendingRound;
    private int numNodes;
    private long[] nodeIDs;
    private HashMap<Long, Integer> nodeIdToIndex;
    private long totalStake;
    private long minNonAncientRound;
    private int voteD; // must be 1 or 2
    private ArrayList<EventInfo> parents = new ArrayList<>(); //used as scratchpad during update of an event
    private int parentsMaxSize = 0; // largest parents has ever been (used to recover from massive branching)
    private boolean nodesChanged; // true for round 1 and for any round where nodes[] differs from the round before it
    private int currMark = 0;
    private boolean roundDecided;
    private long supermajorityThreshold; // stake more than this is a supermajority
    //the following are used for tracking candidates for judge in the current round (to speed up topVote & stakeAgrees)
    private int candCount; // how many candidates found so far during the pending round
    private ArrayList<ArrayList<Integer>> candIndex; // for each node, the index into cand* for each candidate
    private EventInfo[] candEventInfo; // for each node, the list of candidate events
    private long[] candStake; // the total stake of all votes for each candidate event

    // the following getters are just for debugging, monitoring, testing, etc. Normal code should not rely on them.
    /* //for the moment, I'll comment these out to ensure our integration doesn't accidentally rely on them
    public long getPendingRound() {return pendingRound;}
    public int getNumNodes() {return numNodes;}
    public long[] getNodeIDs() {return nodeIDs;}
    public EventInfo[] getfirstVote() {return firstVote;}
    public HashMap<Long, Integer> getNodeIdToIndex() {return nodeIdToIndex;}
    public long getTotalStake() {return totalStake;}
    public long getMinNonAncientRound() {return minNonAncientRound;}
    public int getVoteD() {return voteD;}
    public ArrayList<EventInfo> getParents() {return parents;}
    public int getParentsMaxSize() {return parentsMaxSize;}
    public boolean getNodesChanged() {return nodesChanged;}
    public int getCurrMark() {return currMark;}
    public boolean getRoundDecided() {return roundDecided;}
    public long getSupermajorityThreshold() {return supermajorityThreshold;}
    public ArrayList<ArrayList<Integer>> getCandIndex(){return candIndex};
    public EventInfo[] getCandEventInfo(){return candEventInfo;}
    public long[] getCandStake(){return candStake;};
    */

    /**
     * the minimum birth round that counts as non-ancient, during the time when these two infos
     * correspond to the pending round
     *
     * @param roundInfo info about the pending round (e.g., the nodes, weights, various settings)
     * @param roundInfoPrev info about the pending round regarding the previous round
     * @return the minimum birth round that counts as non-ancient
     */
    public static long minNonAncientRound(@NonNull RoundInfo roundInfo, @NonNull RoundInfoPrev roundInfoPrev) {
        return Math.max(
                roundInfoPrev.prevMinNonAncientRound,
                roundInfoPrev.prevMinJudgeBirthRound - roundInfo.targetNumRoundsNonAncient);
    }

    /**
     * Set isConsensus to true for each event in the hashgraph where it is false, and where the event is an ancestor
     * of all the given judges (or of at least one judge, if judgeCons1 is true). Add them to consensusEvents
     * (if consensusEvents is not null).
     */
    private void graphSearch(@NonNull EventInfo[] judges, boolean judgeCon1, ArrayList<EventInfo> consensusEvents) {
        // mark used while searching from the first judge (later judges' marks are greater)
        int firstMark = currMark + 1;
        // events reach consensus when they are an ancestor of this many judges
        int targetCount = judgeCon1 ? 1 : judges.length;
        for (EventInfo judge : judges) { // depth-first search starting from each judge
            EventInfo x = judge, nextX;
            if (x.isConsensus) {continue;}
            currMark++;
            x.searchChild = null; // backtracking up from this judge means the search is done
            while (x != null) { // depth-first search starting from this judge
                // x is ancestor of this many judges so far (1 if the mark is lower than the first judge's)
                x.searchCount = (x.searchMark < firstMark) ? 1 : x.searchCount + 1;
                x.searchMark = currMark;
                x.searchParent = -1; // descend through the first parent first (index 0)
                if (x.searchCount == targetCount) {
                    x.isConsensus = true;
                    if (consensusEvents != null) {
                        consensusEvents.add(x);
                    }
                }
                nextX = null;
                // while nextX is bad (null / ancient / marked / isConsensus), search until a good one is found or done
                while (x != null
                        && (nextX == null
                        || nextX.birthRound < minNonAncientRound
                        || nextX.searchMark == currMark
                        || nextX.isConsensus)) {
                    while (x != null && x.searchParent >= x.parentsSigned.length - 1) {
                        x = x.searchChild; // backtrack up until an event is found with an unexplored parent
                    }
                    if (x != null) {
                        x.searchParent++;
                    }
                    nextX = (x == null) ? null : x.parentsSigned[x.searchParent];
                }
                x = nextX; // move to the new event that was good (or null if done searching from this judge)
            }
        }
    }

    /** Info about a round that might be known multiple rounds in advance. No element can be null. */
    public record RoundInfo(
            @Min(1L) @Max(Long.MAX_VALUE) long pendingRound, // info used in this round
            @NonNull long[] nodes, // NodeID for each node
            @NonNull long[] stake,
            @Min(1) @Max(Integer.MAX_VALUE) int coinInterval, // how often coin flips happen. 10 is a good value.
            boolean firstVotingRoundSee,
            boolean judgeCon1,
            @Min(1) @Max(Integer.MAX_VALUE) int targetNumRoundsNonAncient,
            @Min(1) @Max(Integer.MAX_VALUE) int numRoundsAddressBook) {}

    /**
     * Info about a round that is only available when the previous round reaches consensus. No element can be null.
     * For the first round (round 1) the parameters should be {1,false,[],false,0,0,0}, where [] is an empty array.
     */
    public record RoundInfoPrev(
            @Min(1L) @Max(Long.MAX_VALUE) long pendingRound, // info used in this round, describing the previous round
            boolean prevJudgeCon1,
            @NonNull EventInfo[] prevJudges,
            boolean prevJudgesCopied,
            long prevMinNonAncientRound,
            long prevNumCons,
            long prevMinJudgeBirthRound) {}

    /**
     * A class with the per-event scratchpad data used for calculating consensus. This is data for the functions
     * marked to be memoized in the tech report. Call {@link EventInfo#update EventInfo.update} to update the data
     * and call {@link EventInfo#clear EventInfo.clear} to clear the data.
     */
    public static final class EventInfo {
        private HashgraphInfo hashgraph;
        private final long creatorNodeID; // nodeID of this event's creator
        private final Instant timeCreated;
        private EventInfo[] parentsSigned;
        private EventInfo selfParent; // self-parent or null if not a descendent of judges in the previous round
        private final int coin;
        private int creator; // index into the nodes array of this event's creator
        private final long birthRound;
        private boolean[] ancestorJudge;
        private boolean prevJudgeDesc;
        private long gen; // also called dGen
        private EventInfo[] lastSee;
        private EventInfo[] stronglySeeP;
        private EventInfo firstSelfWitnessS;
        private long votingRound;
        private EventInfo firstWitnessS;
        private EventInfo[] stronglySeeS1;
        private EventInfo[] voteE;
        private int[] voteIndex; // index into h.candEventInfo of the candidate corresponding to voteE
        private boolean[] voteB;
        private boolean isConsensus;
        private long consensusOrder;
        private Instant consensusTimestamp;
        private boolean prevJudge;
        private long maxJudgeRound;
        private int candIndex; // index into h.cand* for candidate events (can be anything for non-candidates)
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
         * then it is ok to pass in an array of all nulls, or an empty array.
         *
         * @param hashgraph   which hashgraph this event belongs to (if multiple hashgraphs are simulated in memory)
         * @param creator     the nodeID of the creator of this event
         * @param timeCreated when this event was created, as claimed by its creator node
         * @param birthRound  birth round of this event (which was the pending round at the moment it was created)
         * @param coin        the coin flip results for this event (in the range 0 to numNodes, inclusive)
         * @param parents     array of parents, in the same order as in the signed event that is gossiped.
         */
        public EventInfo(
                @NonNull HashgraphInfo hashgraph,
                long creator,
                @NonNull Instant timeCreated,
                long birthRound,
                int coin,
                @NonNull EventInfo[] parents) {
            this.hashgraph = hashgraph;
            this.creatorNodeID = creator;
            this.timeCreated = timeCreated;
            this.birthRound = birthRound;
            this.parentsSigned = parents;
            this.coin = coin;
            this.isConsensus = false;
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

        // the following getters are just for debugging, monitoring, testing, etc. Normal code should not rely on them.
        /* //for the moment, I'll comment these out to ensure our integration doesn't accidentally rely on them
        public HashgraphInfo getHashgraph() {return hashgraph;}
        public long getCreatorNodeID() {return creatorNodeID;}
        public Instant getTimeCreated() {return timeCreated;}
        public EventInfo[] getParentsSigned() {return parentsSigned;}
        public EventInfo getSelfParent() {return selfParent;}
        public int coin() {return coin;}
        public int getCreator() {return creator;}
        public long getBirthRound() {return birthRound;}
        public boolean[] getAncestorJudge() {return ancestorJudge;}
        public boolean getPrevJudgeDesc() {return prevJudgeDesc;}
        public long getGen() { return gen;}
        public EventInfo[] getLastSee() {return lastSee;}
        public EventInfo[] getStronglySeeP() {return stronglySeeP;}
        public EventInfo getFirstSelfWitnessS() {return firstSelfWitnessS;}
        public long getVotingRound() {return votingRound;}
        public EventInfo getFirstWitnessS() {return firstWitnessS;}
        public EventInfo[] getStronglySeeS1() {return stronglySeeS1;}
        public EventInfo[] getVoteE() {return voteE;}
        public EventInfo[] getVoteIndex() {return getVoteIndex;}
        public boolean[] getVoteB() {return voteB;}
        public boolean getPrevJudge() {return prevJudge;}
        public long getMaxJudgeRound() {return maxJudgeRound;}
        public long getSearchMark() {return searchMark;}
        public int getSearchCount() {return searchCount;}
        public int getSearchParent() {return searchParent;}
        public EventInfo getSearchChild() {return searchChild;}
        */

        /**
         * Erase all references from this event to its ancestor events. It should eventually be called on every event,
         * but only after it is ancient. It also sets to null the reference to the hashgraph, so after being cleared,
         * any future call to update will return null. This must be called eventually on every event, to allow the
         * garbage collector to free memory. A cleared object shouldn't be reused, because many scalar fields are not
         * reset here.
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
            voteIndex = null;
            voteB = null;
            consensusTimestamp = null;
            searchChild = null;
        }

        /**
         * {@link EventInfo#update EventInfo.update} returns this (or null if consensus wasn't yet reached).
         */
        public record UpdateResults(
                @NonNull EventInfo[] consensusEvents,
                @NonNull RoundInfoPrev nextRoundInfoPrev) {}

        /**
         * This should be called for each event just after it is added to the hashgraph. If it returns a non-null
         * result, then consensus has now been reached for that round. At that point, switch to the round info for the
         * next round. Using it, call update on all the judges that were just found, and their descendents.
         * Stop updating them if one of those calls reaches consensus.
         * <p>
         * When starting with an empty hashgraph after a reconnect or restart, there will be a period before all
         * the previous judges have been added to the hashgraph. Do not call this update function
         * during that period. Once all the previous round's judges have been added, it will be possible
         * to instantiate the {@link RoundInfoPrev RoundInfoPrev} record, because all the references for
         * {@link RoundInfoPrev#prevJudges RoundInfoPrev.prevJudges} will be known.
         * At that point, call update() on all the judges that were just added, and their
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
         * A new hashgraph starting from scratch at genesis should be started with
         * {@link RoundInfo#pendingRound RoundInfo.pendingRound} == 1.
         *
         * @param roundInfo info about the pending round (e.g., the nodes, weights, various settings)
         * @param roundInfoPrev info about the pending round reflecting the previous round (e.g., judges, old settings)
         * @return the consensus results, or null if this event didn't decide this round
         */
        public UpdateResults update(@NonNull RoundInfo roundInfo, @NonNull RoundInfoPrev roundInfoPrev) {
            // The following 2 variables make the names look more like the tech report.
            // Each "x" there becomes "this" here. "f(r,x)" becomes "f". "f(r,x,m)" becomes "f[m]".
            // "r" becomes either "r" or "rp", using the latter for fields with names starting with "prev".
            // "h" is used instead of this in some cases, to reduce computation, memory usage and garbage collection.
            final RoundInfo r = roundInfo;
            final RoundInfoPrev rp = roundInfoPrev;
            final HashgraphInfo h = hashgraph;
            long parentRound;
            ArrayList<EventInfo> roundJudges;
            ArrayList<EventInfo> consensusEvents;
            EventInfo[] roundJudgesArray;
            EventInfo[] consensusEventsArray;
            long minJudgeBirthRound;
            boolean witness;
            boolean prevJudgesCopied; // true iff judges for this round copied from the previous, rather than elected

            if (hashgraph == null) {
                throw new IllegalArgumentException("Event was already cleared");
            }
            if (roundInfo.pendingRound != roundInfoPrev.pendingRound) {
                throw new IllegalArgumentException("roundInfo.pendingRound != roundInfoPrev.pendingRound ("
                                              + roundInfo.pendingRound + " != " + roundInfoPrev.pendingRound + ")");
            }
            if (r.pendingRound != h.pendingRound
                    && r.pendingRound != h.pendingRound + 1
                    && h.pendingRound != 0) {
                throw new IllegalArgumentException("roundInfo.pendingRound should be " + h.pendingRound
                                + " or " + (h.pendingRound + 1) + ", not " + roundInfo.pendingRound);
            }

            // If this is the first time update has ever been called on this hashgraph.
            if (h.pendingRound == 0) {
                h.pendingRound = r.pendingRound;
                h.graphSearch(roundInfoPrev.prevJudges, rp.prevJudgeCon1, null);
            }

            // if this is a new round (or the first called on this hashgraph), calculate the HashgraphInfo fields
            if (h.pendingRound != r.pendingRound) {
                h.pendingRound = r.pendingRound;
                h.numNodes = r.nodes.length;

                // if the numbers of nodes changed this round (or it's the first time called), prep cand data structures
                if (h.nodeIDs == null || h.nodeIDs.length != r.nodes.length) {
                    h.candCount = h.numNodes; //start with all the null votes
                    h.candIndex = new ArrayList<ArrayList<Integer>>(h.numNodes);
                    h.candEventInfo = new EventInfo[2 * h.numNodes];
                    h.candStake = new long[2 * h.numNodes];
                    for (int i = 0; i < h.numNodes; i++) {
                        ArrayList<Integer> list = new ArrayList<Integer>(2);
                        list.add(i);
                        h.candIndex.add(list);
                        h.candEventInfo[i] = null; // index m represents a vote that node m have a judge of null
                        h.candStake[i] = 0L;
                    }
                }

                // if r.nodes changed this round (or it's the first time called), then store it, create nodeIdToIndex
                h.nodesChanged = false;
                if (!Arrays.equals(h.nodeIDs, r.nodes)) {
                    h.nodeIDs = r.nodes; //no defensive copy: we require the caller to never change arrays in r/rp/h/x
                    h.nodesChanged = true;
                    if (h.nodeIdToIndex == null) {
                        h.nodeIdToIndex = new HashMap<>();
                    } else {
                        h.nodeIdToIndex.clear();
                    }
                    for (int i = 0; i < h.numNodes; i++) {
                        h.nodeIdToIndex.put(h.nodeIDs[i], i);
                    }
                }

                // function minNonAncientRound /------------------------------------------------------------------
                h.minNonAncientRound = HashgraphInfo.minNonAncientRound(r, rp);

                // if this is the first time this event has been updated, or if this round has a changed address book,
                // then recalculate the index for the creator.
                // The index is -1 if the creator is not in this round's address book.
                if (lastSee == null || h.nodesChanged) {
                    Integer index = h.nodeIdToIndex.get(creatorNodeID);
                    creator = (index == null) ? -1 : index;
                }

                // set isPrevJudge to true for the judges in the previous round
                for (EventInfo judge : rp.prevJudges) {
                    judge.prevJudge = true;
                }

                // function totalStake /--------------------------------------------------------------------------
                h.totalStake = 0;
                for (long s : r.stake) {
                    h.totalStake += s;
                }

                // function supermajority /-----------------------------------------------------------------------
                h.supermajorityThreshold = 2 * h.totalStake / 3;

                { // function voteD  /----------------------------------------------------------------------------
                    long totalStake = 0;
                    for (EventInfo judge : rp.prevJudges) {
                        totalStake += r.stake[judge.creator];
                    }
                    h.voteD = (rp.prevJudgesCopied || (rp.prevJudgeCon1 && !r.judgeCon1)
                                    || (totalStake <= h.supermajorityThreshold)) ? 2 : 1;
                }
            }

            // instantiate fields if they are null, or the array is the wrong size.
            if (ancestorJudge == null || ancestorJudge.length != h.numNodes) {
                ancestorJudge = new boolean[h.numNodes]; // only the first rp.prevJudges.length elements will be used
            }
            if (lastSee == null || lastSee.length != h.numNodes) {
                lastSee = new EventInfo[h.numNodes];
            }
            if (stronglySeeP == null || stronglySeeP.length != h.numNodes) {
                stronglySeeP = new EventInfo[h.numNodes];
            }
            if (stronglySeeS1 == null || stronglySeeS1.length != h.numNodes) {
                stronglySeeS1 = new EventInfo[h.numNodes];
            }
            if (voteE == null || voteE.length != h.numNodes) {
                voteE = new EventInfo[h.numNodes];
            }
            if (voteIndex == null || voteIndex.length != h.numNodes) {
                voteIndex = new int[h.numNodes];
            }
            if (voteB == null || voteB.length != h.numNodes) {
                voteB = new boolean[h.numNodes];
            }

            // function parents  /--------------------------------------------------------------------------------
            // put in the h.parents list only parents that are non-ancient descendents of judges in the prev round
            if (h.parentsMaxSize > h.numNodes && parentsSigned.length < h.numNodes) {
                // shrink to recover after branching
                h.parents = new ArrayList<>(h.numNodes);
                h.parentsMaxSize = 0;
            }
            h.parents.clear();
            for (EventInfo parent : parentsSigned) {
                if (parent != null && parent.prevJudgeDesc) {
                    h.parents.add(parent);
                }
            }
            h.parentsMaxSize = Math.max(h.parentsMaxSize, parentsSigned.length);
            selfParent = (h.parents.isEmpty() || h.parents.getFirst().creator != creator)
                            ? null : h.parents.getFirst();

            // function prevJudgeDesc /---------------------------------------------------------------------------
            // also set maxJudgeRound, which is the max round of all judges that are ancestors of x, or 1 if none.
            maxJudgeRound = prevJudge ? (r.pendingRound - 1) : 1;
            for (EventInfo parent : h.parents) {
                maxJudgeRound = Math.max(maxJudgeRound, parent.maxJudgeRound);
            }
            prevJudgeDesc = (maxJudgeRound >= r.pendingRound - 1); // use alg in paper comments, not equations

            // function ancestorJudge  /--------------------------------------------------------------------------
            // (for each y that is the index of the judge in prevJudge(r))
            for (int i = 0; i < rp.prevJudges.length; i++) {
                ancestorJudge[i] = (this == rp.prevJudges[i]);
                for (EventInfo parent : parentsSigned) {
                    if (parent.ancestorJudge[i]) {
                        ancestorJudge[i] = true;
                        break;
                    }
                }
            }

            { // function gen /-----------------------------------------------------------------------------------
                long t = 0;
                for (EventInfo parent : h.parents) {
                    t = Math.max(t, parent.gen);
                }
                gen = t + 1;
            }

            // function parentRound /-----------------------------------------------------------------------------
            if (h.parents.isEmpty()) {
                parentRound = r.pendingRound - 1;
            } else {
                parentRound = 0;
                for (EventInfo parent : h.parents) {
                    parentRound = Math.max(parentRound, parent.votingRound);
                }
            }

            // function lastSee /---------------------------------------------------------------------------------
            for (int m = 0; m < h.numNodes; m++) {
                if (m == creator) {
                    lastSee[m] = this;
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
                    lastSee[m] = s2empty ? null : p;
                }
            }

            // function stronglySeeP /----------------------------------------------------------------------------
            for (int m = 0; m < h.numNodes; m++) {
                EventInfo y, z, p = selfParent;
                //y = seeThru(r,x,m,m)
                if (creator == m) {
                    y = (p == null) ? null : p.firstSelfWitnessS;
                } else {
                    y = lastSee[m];
                    z = (y == null) ? null : y.lastSee[m];
                    y = (z == null) ? null : z.firstSelfWitnessS;
                }
                if (y == null || y.votingRound != parentRound) {
                    stronglySeeP[m] = null;
                } else {
                    long s = 0;
                    for (int mp = 0; mp < h.numNodes; mp++) {
                        EventInfo yp;
                        // function seeThru /---------------------------------------------------------------------
                        //yp = seeThru(r,x,m,mp)
                        if (m == mp && creator == m) {
                            yp = (p == null) ? null : p.firstSelfWitnessS;
                        } else {
                            yp = lastSee[mp];
                            z = (yp == null) ? null : yp.lastSee[m];
                            yp = (z == null) ? null : z.firstSelfWitnessS;
                        }
                        s += (yp == null) ? 0 : r.stake[mp];
                    }
                    stronglySeeP[m] = (s >= h.supermajorityThreshold) ? y : null;
                }
            }

            { // function votingRound /---------------------------------------------------------------------------
                long p = parentRound;
                if (r.pendingRound == p + 1) {
                    boolean b = true;
                    for (int y = 0; y < rp.prevJudges.length; y++) {
                        b = b && (this != rp.prevJudges[y]) && ancestorJudge[y];
                    }
                    if (r.pendingRound == p + 1) {
                        votingRound = b ? p + 1 : p;
                    } else if ((r.pendingRound == p) && r.firstVotingRoundSee && (h.voteD == 1)) { //if q
                        long s = 0;
                        for (int m = 0; m < r.nodes.length; m++) {
                            s += (((creator == m) && (selfParent != null)
                                    && (selfParent.votingRound == p))
                                    || ((creator != m) && (lastSee[m] != null)
                                    && (lastSee[m].votingRound == p)))
                                    ? r.stake[m] : 0;
                        }
                        votingRound = (s >= h.supermajorityThreshold) ? p + 1 : p;
                    } else { //not q
                        long s = 0;
                        for (int m = 0; m < r.nodes.length; m++) {
                            s += (stronglySeeP[m] != null) ? r.stake[m] : 0;
                        }
                        votingRound = (s >= h.supermajorityThreshold) ? p + 1 : p;
                    }
                }
            }

            // function firstSelfWitnessS /-----------------------------------------------------------------------
            {
                EventInfo p = selfParent;
                if (p == null || votingRound > p.votingRound) {
                    firstSelfWitnessS = this;
                } else {
                    firstSelfWitnessS = p.firstSelfWitnessS;
                }
            }

            // function firstWitnessS /---------------------------------------------------------------------------
            if (h.parents.isEmpty()) {
                firstWitnessS = null;
            } else if (votingRound != parentRound) {
                firstWitnessS = this;
            } else {
                for (EventInfo p : h.parents){
                    if (votingRound == p.votingRound) {
                        firstWitnessS = p.firstWitnessS;
                        break; // this break will happen because votingRound = parentRound at this point
                    }
                }
            }

            // function stronglySeeS1 /---------------------------------------------------------------------------
            if (firstWitnessS == null) {
                Arrays.fill(stronglySeeS1, null);
            } else {
                System.arraycopy(stronglySeeP, 0, stronglySeeS1, 0, h.numNodes);
            }

            // function witness /---------------------------------------------------------------------------------
            witness = (selfParent == null) || (votingRound > selfParent.votingRound);
            // if this event is a judge candidate for this round, then give it a new index and remember it
            if (witness && (h.pendingRound == r.pendingRound) && (creator >= 0)) {
                h.candCount++; // a new candidate has been found
                h.candIndex.get(creator).add(h.candCount);
                if (h.candCount > h.candStake.length) { // if too big for arrays, then double their sizes
                    h.candStake = Arrays.copyOf(h.candStake, h.candCount * 2);
                    h.candEventInfo = Arrays.copyOf(h.candEventInfo, h.candCount * 2);
                }
                h.candEventInfo[h.candCount - 1] = this;
            }

            // function stakeAgrees /-----------------------------------------------------------------------------
            // Instead of using the stakeAgrees function from the paper, use h.cand* fields for more efficiency.
            // Prepare for topVote by finding total stake for all votes for each candidate (including for null).
            Arrays.fill(h.candStake, 0, h.candCount, 0L);
            for (int voterCreator = 0; voterCreator < h.numNodes; voterCreator++) {
                for (int candCreator = 0; candCreator < h.numNodes; candCreator++) {
                    EventInfo voter = stronglySeeP[voterCreator];
                    if (voter != null) {
                        h.candStake[voter.voteIndex[candCreator]] += r.stake[candCreator];
                    }
                }
            }

            // function vote /------------------------------------------------------------------------------------
            for (int m = 0; m < h.numNodes; m++) { // find which candidate created by m to vote for (or null for none)
                long i = h.pendingRound + h.voteD;
                voteE[m] = null; // default if not overridden before the "continue"
                voteB[m] = false; // default if not overridden before the "continue"
                if (!witness || votingRound < i) {
                    continue;
                }
                if (votingRound == i) {
                    // function firstVote /-------------------------------------------------------------------------------
                    EventInfo firstVote;
                    if (h.voteD == 2) { // vote for any witness strongly seen by a witness that you strongly see.
                        firstVote = null;
                        for (int mp = 0; mp < h.numNodes; mp++) {
                            EventInfo t = stronglySeeS1[mp];
                            if (t != null) {
                                EventInfo s = t.stronglySeeS1[m];
                                if (s != null) {
                                    firstVote = s;
                                    break;
                                }
                            }
                        }
                    } else { // voteD = 1. Vote for any witness you can see. (Or the branch seen first, if branching)
                        EventInfo z = lastSee[m];
                        if (z == null) {
                            firstVote = null;
                        } else {
                            EventInfo v = z.firstSelfWitnessS;
                            if (v.votingRound == votingRound - 1) {
                                EventInfo y = v.selfParent;
                                if (y != null && y.votingRound == votingRound - 1) {
                                    firstVote = y.firstSelfWitnessS;
                                } else {
                                    firstVote = null;
                                }
                            } else {
                                firstVote = null;
                            }
                        }
                    }
                    voteE[m] = firstVote;
                    continue;
                } // end of firstVote

                // function topVote /---------------------------------------------------------------------------------
                int bestIndex = 0;
                long bestStake = -1;
                for (int index : h.candIndex.get(m)) {
                    long stake = h.candStake[index];
                    if (stake > bestStake) {
                        bestStake = stake;
                    }
                }
                EventInfo v = h.candEventInfo[bestIndex];
                boolean s = (bestStake > h.supermajorityThreshold);

                //end of topVote, continuing vote
                boolean q = (0 == ((votingRound - h.pendingRound) % r.coinInterval));
                if (!q) { //if not a coin round, vote whatever vote had the majority collected
                    voteE[m] = v;
                    voteB[m] = s;
                    continue;
                }
                if (s) { //if a coin round and collect a supermajority, vote that way, but don't decide
                    voteE[m] = v;
                    continue;
                }
                int mp = coin;
                if ((mp == h.numNodes) || (h.pendingRound != birthRound)) { // coin==h.numNodes means vote for null
                    continue; // if the coin chose null then vote null. (Or if birth round isn't pending round)
                }
                EventInfo w = stronglySeeS1[mp];
                if (w == null) { // if the coin chose a voter that wasn't collected, then vote null
                    continue;
                }
                voteE[m] = w.voteE[m]; // vote the same as the vote collected from the voter that the coin chose
            }
            for (int m = 0; m < h.numNodes; m++) {
                voteIndex[m] = ((voteE[m] == null) ? m : voteE[m].voteIndex[m]);
            }
            //end vote

            // function roundDecided /----------------------------------------------------------------------------
            h.roundDecided = witness;
            for (int m = 0; m < h.numNodes; m++) {
                h.roundDecided = h.roundDecided && voteB[m];
                if (!h.roundDecided) {
                    break;
                }
            }
            if (!h.roundDecided) {
                return null;
            }

            // function roundJudges /-----------------------------------------------------------------------------
            roundJudges = new ArrayList<>();
            long s = 0; //total stake of all the elected judges
            for (int m = 0; m < h.numNodes; m++) {
                if (voteE[m] != null) {
                    roundJudges.add(voteE[m]);
                    s += r.stake[voteE[m].creator];
                }
            }
            prevJudgesCopied = (s <= h.supermajorityThreshold);
            if (prevJudgesCopied) { // if not a supermajority, copy previous judges. This is VERY rare.
                roundJudges.clear();
                for (EventInfo judge : rp.prevJudges) {
                    if (judge.creator != -1) { //add only the previous judges that are in the current address book
                        roundJudges.add(judge);
                    }
                }
            }
            roundJudgesArray = roundJudges.toArray(new EventInfo[0]);

            // function receivedEvents /--------------------------------------------------------------------------

            // function isReceived /------------------------------------------------------------------------------

            // function reachedCon /------------------------------------------------------------------------------

            // function timeCon /---------------------------------------------------------------------------------

            // function before /----------------------------------------------------------------------------------

            // function isConsensus /-----------------------------------------------------------------------------
            consensusEvents = new ArrayList<>();
            h.graphSearch(roundJudgesArray, r.judgeCon1, consensusEvents);
            consensusEventsArray = consensusEvents.toArray(new EventInfo[0]);

            // function consensusOrder /--------------------------------------------------------------------------

            // function consensusTimestamp /----------------------------------------------------------------------

            // the round reached consensus, so set the old judges to false and the new to true
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
                    consensusEventsArray, // consensusEvents
                    new RoundInfoPrev(
                            h.pendingRound + 1, // pendingRound
                            r.judgeCon1, // prevJudgeCon1
                            roundJudgesArray, // prevJudges
                            prevJudgesCopied, // prevJudgesCopied
                            h.minNonAncientRound, // prevMinNonAncientRound
                            rp.prevNumCons + consensusEvents.size(), // prevNumCons
                            minJudgeBirthRound)); // prevMinJudgeBirthRound
        }
    }
}
