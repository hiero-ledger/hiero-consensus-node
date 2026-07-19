// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.consensus.calculations;

import com.swirlds.config.api.validation.annotation.Max;
import com.swirlds.config.api.validation.annotation.Min;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

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
@java.lang.SuppressWarnings("unused")
public final class HashgraphInfo {
    /** throw exceptions if update() is called on a round after it reaches consensus, or if it skips a round */
    public static boolean ENFORCE_ROUND_ADVANCE = false;
    /** for round 1 (the genesis round) use this as the RoundInfoPrev record */
    public static final RoundInfoPrev FIRST_ROUND_INFO_PREV =
            new RoundInfoPrev(1, false, new EventInfo[0], false, 1, 0, 0);

    private static final AtomicLong lastHashgraphInfoID = new AtomicLong(-1); // ID of last one instantiated
    private final long hashgraphInfoID = lastHashgraphInfoID.incrementAndGet(); // ID of this hashgraph
    // EventInfo.update uses these and updates them the first time it is called with any given pending round.
    private long lastEventID = 0; // the most recent unique ID generated for an event by generateEventID()
    private boolean newRound = true; // true iff update() has never been called for the pending round
    private boolean lastUpdateUsedCoin; // true iff the last round to reach consensus used a coin round
    private long[] benchmarks = new long[NUM_BENCHMARKS]; // total nanoseconds spent in various code sections
    private long pendingRound;
    private int numNodes;
    private long[] nodeIDs;
    private HashMap<Long, Integer> nodeIdToIndex;
    private long totalStake;
    private long minNonAncientRound;
    private int voteD; // must be 1 or 2
    private ArrayList<EventInfo> parents = new ArrayList<>(); // used as scratchpad during update of an event
    private ArrayList<EventInfo> judges = new ArrayList<>(); // used as scratchpad during update of an event
    private ArrayList<EventInfo> consensusEvents = new ArrayList<>(); // used as scratchpad during update of an event
    private Integer[] sortInd; // used as a scratchpad for each judge index, while sorting to calculate median
    private int parentsCapacity = 0; // capacity requested for parents list (it might actually be more than requested)
    private int judgesCapacity = 0; // capacity requested for judges list (it might actually be more than requested)
    private int consensusEventsCapacity = 0; // capacity requested for consensusEvents list (might be more)
    private boolean nodesChanged; // true for round 1 and for any round where nodes[] differs from the round before it
    private int currMark = 0;
    private boolean roundDecided;
    private long supermajorityThreshold; // stake more than this is a supermajority
    // the following are used for tracking candidates for judge in the current round (to speed up topVote & stakeAgrees)
    private int candCount; // how many candidates found so far during the pending round
    private ArrayList<ArrayList<Integer>> candIndex; // for each node, the index into cand* for each candidate
    private EventInfo[] candEventInfo; // all the judge candidate events for voting
    private long[] candStakeCollected; // the total stake of all votes collected, for each candidate event
    private static RoundInfo latestRoundInfo; // the latest roundInfo passed to update() for any hashgraph instance
    private static RoundInfoPrev latestRoundInfoPrev; // the most recent roundInfoPrev passed to any update()

    // define what each element in benchmarks[] currently means. Always at least 1. Elements 0/1 must never change
    private static final int BENCHMARK_UPDATE = 0; // time spent in update()
    private static final int BENCHMARK_UPDATE_COUNT = 1; // number of times update() was called
    private static final int BENCHMARK_SEARCH = 2; // graphSearch()
    private static final int BENCHMARK_LOOP1 = 3; // prevJudges parentsSigned (in ancestorJudge)
    private static final int BENCHMARK_LOOP2 = 4; // numNodes parents (in lastSee)
    private static final int BENCHMARK_LOOP3 = 5; // numNodes parents (in lastSee)
    private static final int BENCHMARK_LOOP4 = 6; // numNodes numNodes (in stronglySeeP)
    private static final int BENCHMARK_LOOP5 = 7; // numNodes numNodes (in stakeAgrees)
    private static final int BENCHMARK_LOOP6 = 8; // numNodes numNodes (in vote outside topVote (when voteD==2))
    private static final int BENCHMARK_LOOP7 = 9; // numNodes numNodes (in vote outside topVote (when voteD==1))
    private static final int BENCHMARK_LOOP8 = 10; // numNodes numNodes (in topVote first half)
    private static final int BENCHMARK_LOOP9 = 11; // numNodes numNodes (in topVote second half)
    public static final int NUM_BENCHMARKS = 1 + BENCHMARK_LOOP9; // number of elements in long[] getBenchmarks()

    /** generates a new unique ID for an event each time it is called */
    public long generateEventID() {
        return ++lastEventID;
    }

    /** true iff the last round to reach consensus used a coin round */
    public boolean isLastUpdateUsedCoin() {
        return lastUpdateUsedCoin;
    }

    /**
     * Total nanoseconds spent in various code sections since the last resetBenchmarks.
     * The exact length and meaning of the array elements may change in future versions. But there
     * will always be at least 1 element. And the first element is always the total time spent
     * in {@link EventInfo#update EventInfo.update()}.
     */
    public long[] getBenchmarks() {
        return benchmarks;
    }

    /** Reset the benchmark timers, so {@link HashgraphInfo#getBenchmarks HashgraphInfo.getBenchmarks()}
     *  only returns time spent since the last resetBencharks(). */
    void resetBenchmarks() {
        if (benchmarks == null) {
            benchmarks = new long[NUM_BENCHMARKS];
        } else {
            Arrays.fill(benchmarks, 0L);
        }
    }

    // the following getters are just for debugging, monitoring, testing, etc. Normal code should not rely on them.

    public static AtomicLong getLastHashgraphInfoID() {
        return lastHashgraphInfoID;
    }

    public long getHashgraphInfoID() {
        return hashgraphInfoID;
    }

    public boolean isNewRound() {
        return newRound;
    }

    public long getLastEventID() {
        return lastEventID;
    }

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

    public ArrayList<EventInfo> getJudges() {
        return judges;
    }

    public ArrayList<EventInfo> getJConsensusEvents() {
        return consensusEvents;
    }

    public int getParentsCapacity() {
        return parentsCapacity;
    }

    public int getJudgesCapacity() {
        return judgesCapacity;
    }

    public int getConsensusEventsCapacity() {
        return consensusEventsCapacity;
    }

    public int getCurrMark() {
        return currMark;
    }

    public long getSupermajorityThreshold() {
        return supermajorityThreshold;
    }

    public ArrayList<ArrayList<Integer>> getCandIndex() {
        return candIndex;
    }

    public EventInfo[] getCandEventInfo() {
        return candEventInfo;
    }

    public long[] getCandStakeCollected() {
        return candStakeCollected;
    }

    public boolean isNodesChanged() {
        return nodesChanged;
    }

    public boolean isRoundDecided() {
        return roundDecided;
    }

    public int getCandCount() {
        return candCount;
    }

    public static RoundInfo getLatestRoundInfo() {
        return latestRoundInfo;
    }

    public static RoundInfoPrev getLatestRoundInfoPrev() {
        return latestRoundInfoPrev;
    }

    /**
     * The minimum birth round that counts as non-ancient, during the time when these two infos
     * correspond to the pending round. <br>
     *
     * If the previous round had a minimum birth round among all the judges of 100, and targetNumRoundsNonAncient==10,
     * then the minimum non-ancient round is 90. Unless that would make it go backward, and be less than it was
     * during the previous round. In which case, it will just stay the same as the previous round.
     *
     * @param roundInfo info about the pending round (e.g., the nodes, weights, various settings)
     * @param roundInfoPrev info about the pending round regarding the previous round
     * @return the minimum birth round that counts as non-ancient
     */
    public static long minNonAncientRound(@NonNull RoundInfo roundInfo, @NonNull RoundInfoPrev roundInfoPrev) {
        // function minNonAncientRound /------------------------------------------------------------------
        return Math.max(
                roundInfoPrev.prevMinNonAncientRound,
                roundInfoPrev.prevMinJudgeBirthRound - roundInfo.targetNumRoundsNonAncient);
    }

    /** Info about a round that might be known multiple rounds in advance. No element can be null. */
    public record RoundInfo(
            @Min(1L) @Max(Long.MAX_VALUE) long pendingRound, // info used in this round
            @NonNull long[] nodes, // NodeID for each node
            @NonNull long[] stake,
            @Min(1) @Max(Integer.MAX_VALUE) int coinInterval, // how often coin flips happen. 10 is a good value.
            int seeNum, // numerator of threshold for seeing, to be a witness in first voting round
            int seeDen, // denominator of threshold. Ratio can be in range 2/3 for shortcut, to 3/3 for original
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
        private final long eventID; // for debugging, caller could optionally give each event a unique ID
        private HashgraphInfo hashgraph;
        private final long creatorNodeID; // nodeID of this event's creator
        private final Instant timeCreated;
        private EventInfo[] parentsSigned;
        private EventInfo selfParent; // self-parent or null if not a descendent of judges in the previous round
        private final int coin;
        private int creator; // index into the nodes array of this event's creator
        private final long birthRound;
        private boolean[] ancestorJudge;
        private long gen; // also called dGen. Is -1 iff update() has never been called yet on this event
        private EventInfo[] lastSee;
        private EventInfo[] stronglySeeP;
        private EventInfo firstSelfWitnessS;
        private long votingRound;
        private EventInfo firstWitnessS;
        private EventInfo[] stronglySeeS1;
        private EventInfo[] voteE;
        private int[] voteIndex; // index into h.candEventInfo of the candidate corresponding to voteE
        private boolean[] voteB;
        private Instant[] receivedTime; // when each judge is reached.
        private boolean isConsensus;
        private long consensusOrder;
        private Instant consensusTimestamp;
        private boolean isPrevJudge; // true if this is a judge in previous round (updated when round reaches consensus)
        private long maxJudgeRound;
        private int eventCandIndex; // index into h.cand* for candidate events (can be anything for non-candidates)
        // the following are used for graph searches in the hashgraph
        private long searchMark; // mark visited events so depth-first search backtracks when revisiting it
        private int searchCount; // number of judges that are descendents of this event
        private int searchParent; // index of the parent of this event currently being searched
        private EventInfo searchChild; // the child of this event through which it was reached
        private boolean searchJudgeSelfAncestor; // true iff a self-ancestor of the judge currently being searched
        private int searchOrder; // order in which this was found during search (or could be randomized)

        /**
         * Constructor for the {@link EventInfo EventInfo} object for an event. The parents array should contain the
         * parents in the same order as they are listed in the signed event that is gossiped. If there is a self-parent
         * in the array, it must be in the first position. It is ok if the parents array is missing some or all of the
         * ancient parents. It is ok if the array contains some null elements. If an event has no non-ancient parents,
         * then it is ok to pass in an array of all nulls, or an empty array. The eventID field is not used for
         * consensus but can be useful for testing / debugging / monitoring.
         *
         * @param hashgraph   which hashgraph this event belongs to (if multiple hashgraphs are simulated in memory)
         * @param eventID     for debugging, the caller could optionally give each event a unique ID
         * @param creator     the nodeID of the creator of this event
         * @param timeCreated when this event was created, as claimed by its creator node
         * @param birthRound  birth round of this event (which was the pending round at the moment it was created)
         * @param coin        the coin flip results for this event (in the range 0 to numNodes, inclusive)
         * @param parents     array of parents, in the same order as in the signed event that is gossiped.
         */
        public EventInfo(
                @NonNull HashgraphInfo hashgraph,
                long eventID,
                long creator,
                @NonNull Instant timeCreated,
                long birthRound,
                int coin,
                @NonNull EventInfo[] parents) {
            this.hashgraph = hashgraph;
            this.eventID = eventID;
            this.creatorNodeID = creator;
            this.timeCreated = timeCreated;
            this.birthRound = birthRound;
            this.parentsSigned = parents;
            this.coin = coin;
            this.isConsensus = false;
            this.gen = -1; // -1 iff update() has never been called yet on this event
        }

        /** True iff this event has reached consensus. (If false, it may still reach consensus later). */
        public boolean isConsensus() {
            return isConsensus;
        }

        /** The consensus order of this event, starting at 1 for genesis (or 0 if isConsensus is false). */
        public long getConsensusOrder() {
            return consensusOrder;
        }

        /** The consensus timestamp for this event (or null if isConsensus is false). */
        public Instant getConsensusTimestamp() {
            return consensusTimestamp;
        }

        // the following getters are just for debugging, monitoring, testing, etc. Normal code should not rely on them.

        public HashgraphInfo getHashgraph() {
            return hashgraph;
        }

        public long getEventID() {
            return eventID;
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

        public EventInfo getSelfParent() {
            return selfParent;
        }

        public int coin() {
            return coin;
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

        public long getGen() {
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

        public Instant[] getReceivedTime() {
            return receivedTime;
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

        public int getSearchOrder() {
            return searchOrder;
        }

        public int getCoin() {
            return coin;
        }

        public int[] getVoteIndex() {
            return voteIndex;
        }

        public boolean isPrevJudge() {
            return isPrevJudge;
        }

        public int getEventCandIndex() {
            return eventCandIndex;
        }

        public boolean isSearchJudgeSelfAncestor() {
            return searchJudgeSelfAncestor;
        }

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
            receivedTime = null;
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
         * Set isConsensus to true for each event x in the hashgraph where it is false, and where x is an ancestor
         * of all the given judges (or of at least one judge, if judgeCon1 is true). Add each x to consensusEvents
         * (if consensusEvents is not null). Set x.searchOrder to the order in which it was found. For each judge j,
         * set x.receivedTime[j] to the creation time of the event where x first reached a self-ancestor of judge j.
         */
        private static void graphSearch(@NonNull HashgraphInfo hashgraphInfo, @NonNull EventInfo[] judges,
                                 boolean judgeCon1, ArrayList<EventInfo> consensusEvents) {
            // mark used while searching from the first judge (later judges' marks are greater)
            int firstMark = hashgraphInfo.currMark + 1;
            // events reach consensus when they are an ancestor of this many judges
            int targetCount = judgeCon1 ? 1 : judges.length;
            hashgraphInfo.benchmarks[BENCHMARK_SEARCH] -= System.nanoTime();
            for (int judgeIndex = 0;
                 judgeIndex < judges.length;
                 judgeIndex++) { // depth-first search starting from each judge
                EventInfo nextX;
                EventInfo x = judges[judgeIndex];
                Instant lowestTime = x.timeCreated; // created time for lowest self-ancestor on current search path
                x.searchJudgeSelfAncestor = true; // true iff x is a self-ancestor of judge judgeIndex
                if (x.isConsensus) {
                    continue;
                }
                hashgraphInfo.currMark++;
                x.searchChild = null; // backtracking up from this judge means the search is done
                while (x != null) { // depth-first search starting from this judge
                    // x is ancestor of this many judges so far (1 if the mark is lower than the first judge's)
                    x.searchCount = (x.searchMark < firstMark) ? 1 : x.searchCount + 1;
                    x.receivedTime[judgeIndex] = lowestTime;
                    x.searchParent = -1; // descend through the first parent first (index 0)
                    if (x.searchCount == targetCount) {
                        x.isConsensus = true;
                        if (consensusEvents != null) {
                            x.searchOrder = consensusEvents.size();
                            consensusEvents.add(x);
                        }
                    }
                    nextX = null;
                    // while nextX is bad (null / ancient / marked / isConsensus), search until good is found or done
                    while (x != null
                            && (nextX == null
                            || nextX.birthRound < hashgraphInfo.minNonAncientRound
                            || nextX.searchMark == hashgraphInfo.currMark
                            || nextX.isConsensus)) {
                        while (x != null && x.searchParent >= x.parentsSigned.length - 1) {
                            x.searchMark = hashgraphInfo.currMark; // backtrack up from x to its child, so mark x as fully explored
                            x = x.searchChild; // backtrack up until an event is found with an unexplored parent
                            if (x != null && x.searchJudgeSelfAncestor) {
                                lowestTime = x.timeCreated;
                            }
                        }
                        if (x == null) {
                            nextX = null;
                        } else {
                            x.searchParent++;
                            nextX = x.parentsSigned[x.searchParent];
                        }
                    }
                    if (nextX != null) {
                        nextX.searchChild = x;
                        nextX.searchJudgeSelfAncestor = x.searchJudgeSelfAncestor && x.searchParent == 0;
                    }
                    x = nextX; // move to the new event that was good (or null if done searching from this judge)
                    if (x != null && x.searchJudgeSelfAncestor) {
                        lowestTime = x.timeCreated;
                    }
                }
            }
            hashgraphInfo.benchmarks[BENCHMARK_SEARCH] += System.nanoTime();
        }

        /**
         * This should be called for each event just after it is added to the hashgraph. If it returns a non-null
         * result, then consensus has now been reached for that round. At that point, switch to the round info for the
         * next round. Using it, call update on all existing events with a birth round equal to or greater than the
         * minimum birth round of the judges that were just found. Stop updating them if one of those calls reaches
         * consensus.
         * <p>
         * When starting with an empty hashgraph after a reconnect or restart, there will be a period before all
         * the previous judges have been added to the hashgraph. Do not call this update function
         * during that period. Once all the previous round's judges have been added, it will be possible
         * to instantiate the {@link RoundInfoPrev RoundInfoPrev} record, because all the references for
         * {@link RoundInfoPrev#prevJudges RoundInfoPrev.prevJudges} will be known.
         * At that point, call update() on all the events with the appropriate birth round. Stop updating them if
         * one of those calls reaches consensus.
         * <p>
         * For any given pending round, call update() on events in topological order. So if it is to be called
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
         * <p>
         * To ensure the {@link EventInfo#isPrevJudge} and {@link EventInfo#isConsensus} fields are set correctly,
         * this method should be called repeatedly on a given pending round for a given hashgraph until it reaches
         * consensus, and then be called on the next pending round. It shouldn't be called again on the round that
         * already reached consensus, nor on a previous round.
         * <p>
         * For each batch of events that reaches consensus, they are first sorted by median timestamp
         * (if judgeCon1==false), or by generation (if judgeCon1==true). Either way, ties are broken by eventID, and
         * ties in that are broken by search order (the order they were found in the graph search). Consensus will
         * still work, be consistent across nodes, and be in topological order, even if the eventIDs are chosen randomly
         * and might have duplicates. But the random choice must be the same on all nodes. One way to do this
         * is to set the event IDs of all events in consensusEvents at the end of graphSearch(), using a
         * CSPRNG seeded with the XOR of all judge hashes for that round. Or, for debugging, set them randomly when
         * they are originally created.
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
            long minJudgeBirthRound;
            boolean witness;
            boolean prevJudgesCopied; // true iff judges for this round copied from the previous, rather than elected
            EventInfo[] judgesArray;
            EventInfo[] consensusEventsArray;

            h.benchmarks[HashgraphInfo.BENCHMARK_UPDATE] -= System.nanoTime();
            if (hashgraph == null) {
                throw new IllegalArgumentException("Event was already cleared");
            }
            if (ENFORCE_ROUND_ADVANCE && roundInfo.pendingRound != roundInfoPrev.pendingRound) {
                throw new IllegalArgumentException("roundInfo.pendingRound != roundInfoPrev.pendingRound ("
                        + roundInfo.pendingRound + " != " + roundInfoPrev.pendingRound + ")");
            }
            if (ENFORCE_ROUND_ADVANCE
                    && roundInfo.pendingRound != h.pendingRound
                    && (roundInfo.pendingRound != 1 || h.pendingRound != 0)) {
                throw new IllegalArgumentException(
                        "roundInfo.pendingRound should be " + h.pendingRound + ", not " + roundInfo.pendingRound);
            }
            HashgraphInfo.latestRoundInfo = roundInfo;
            HashgraphInfo.latestRoundInfoPrev = roundInfoPrev;
            // if this is a new round (or the first called on this hashgraph), calculate the HashgraphInfo fields
            if (h.newRound) {
                // If this is the first time update has ever been called on this hashgraph.
                if (h.pendingRound == 0) {
                    graphSearch(h,roundInfoPrev.prevJudges, rp.prevJudgeCon1, null);
                }
                h.pendingRound = r.pendingRound;
                h.numNodes = r.nodes.length;
                h.newRound = false;
                // if the numbers of nodes changed this round (or it's the first time called), prep cand data structures
                if (h.nodeIDs == null || h.nodeIDs.length != r.nodes.length) {
                    h.candCount = h.numNodes; // start with all the null votes
                    h.candIndex = new ArrayList<>(h.numNodes);
                    h.candEventInfo = new EventInfo[2 * h.numNodes];
                    h.candStakeCollected = new long[2 * h.numNodes];
                    for (int i = 0; i < h.numNodes; i++) {
                        ArrayList<Integer> list = new ArrayList<>(2);
                        h.candIndex.add(list);
                    }
                }
                // it's a new round, so reset the list of candidates to just have the null vote for each node
                for (int m = 0; m < h.numNodes; m++) {
                    h.candIndex.get(m).clear(); // forget old list of candidates for node with index m
                    h.candIndex.get(m).add(m); // add back the entry for the null candidate
                    h.candEventInfo[m] = null; // index m represents a vote that node m have a judge of null
                    Arrays.fill(h.candStakeCollected, 0L); // this could be skipped, but it's cheap and safer to do it
                }
                // if r.nodes changed this round (or it's the first time called), then store it, create nodeIdToIndex
                h.nodesChanged = false;
                if (!Arrays.equals(h.nodeIDs, r.nodes)) {
                    h.nodeIDs = r.nodes; // no defensive copy: we require the caller to never change arrays in r/rp/h/x
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
                if (h.parents == null || h.parentsCapacity > 2 * h.numNodes) {
                    // initialize h.parents the first time, and shrink to recover after massive branching in last round
                    h.parentsCapacity = 2 * h.numNodes;
                    h.parents = new ArrayList<>(h.parentsCapacity);
                }
                if (h.judges == null || h.judgesCapacity > h.numNodes) { // shrink if address book shrank
                    h.judgesCapacity = h.numNodes;
                    h.judges = new ArrayList<>(h.judgesCapacity);
                }
                if (h.consensusEvents == null || h.consensusEventsCapacity > 10 * h.numNodes) { // shrink after a surge
                    h.consensusEventsCapacity = 10 * h.numNodes;
                    h.consensusEvents = new ArrayList<>(h.consensusEventsCapacity);
                }
                if (h.sortInd == null || h.sortInd.length != h.numNodes) { // match address book size
                    h.sortInd = new Integer[h.numNodes];
                }
                // function totalStake /--------------------------------------------------------------------------
                h.totalStake = 0;
                for (long s : r.stake) {
                    h.totalStake += s;
                }
                // function minNonAncientRound /------------------------------------------------------------------
                h.minNonAncientRound = HashgraphInfo.minNonAncientRound(r, rp);
                { // function voteD  /----------------------------------------------------------------------------
                    long totalStake = 0;
                    for (EventInfo judge : rp.prevJudges) {
                        totalStake += r.stake[judge.creator];
                    }
                    h.voteD = (rp.prevJudgesCopied
                                    || (rp.prevJudgeCon1 && !r.judgeCon1)
                                    || (totalStake <= h.supermajorityThreshold))
                            ? 2
                            : 1;
                }
                // function supermajority /-----------------------------------------------------------------------
                h.supermajorityThreshold = 2 * h.totalStake / 3;
                // set prevJudge to true for the judges in the previous round
                for (EventInfo judge : rp.prevJudges) {
                    judge.isPrevJudge = true;
                }
            } // end if first call for this round (newRound==true)
            // function maxJudgeRound /---------------------------------------------------------------------------
            maxJudgeRound = isPrevJudge ? (r.pendingRound - 1) : 0;
            for (EventInfo parent : h.parents) {
                maxJudgeRound = Math.max(maxJudgeRound, parent.maxJudgeRound);
            }
            // if this is the first time this event has been updated, or if this round has a changed address book,
            // then recalculate the index for the creator.
            // The index is -1 if the creator is not in this round's address book.
            if (gen == -1 || h.nodesChanged) {
                Integer index = h.nodeIdToIndex.get(creatorNodeID);
                creator = (index == null) ? -1 : index;
            }
            final int numNodes = h.numNodes; // make the (possibly updated) value a local constant from here down
            // instantiate fields if they are null, or the array is the wrong size.
            if (ancestorJudge == null || ancestorJudge.length != numNodes) {
                ancestorJudge = new boolean[numNodes]; // only the first rp.prevJudges.length elements will be used
            }
            if (lastSee == null || lastSee.length != numNodes) {
                lastSee = new EventInfo[numNodes];
            }
            if (stronglySeeP == null || stronglySeeP.length != numNodes) {
                stronglySeeP = new EventInfo[numNodes];
            }
            if (stronglySeeS1 == null || stronglySeeS1.length != numNodes) {
                stronglySeeS1 = new EventInfo[numNodes];
            }
            if (voteE == null || voteE.length != numNodes) {
                voteE = new EventInfo[numNodes];
            }
            if (voteIndex == null || voteIndex.length != numNodes) {
                voteIndex = new int[numNodes];
            }
            if (voteB == null || voteB.length != numNodes) {
                voteB = new boolean[numNodes];
            }
            if (receivedTime == null || receivedTime.length != numNodes) {
                receivedTime = new Instant[numNodes]; // only the first numJudges elements will end up non-null
            }
            // function parents  /--------------------------------------------------------------------------------
            // put in the h.parents list only parents that are non-ancient descendents of judges in the prev round
            h.parents.clear();
            for (EventInfo parent : parentsSigned) {
                if (parent != null && parent.maxJudgeRound >= r.pendingRound - 1) {
                    h.parents.add(parent);
                }
            }
            h.parentsCapacity = Math.max(h.parentsCapacity, parentsSigned.length);
            selfParent = (h.parents.isEmpty() || h.parents.getFirst().creator != creator) ? null : h.parents.getFirst();
            // function ancestorJudge  /--------------------------------------------------------------------------
            // (for each i that is the index of the judge in prevJudge(r))
            for (int i = 0; i < rp.prevJudges.length; i++) {
                ancestorJudge[i] = (this == rp.prevJudges[i]);
                h.benchmarks[HashgraphInfo.BENCHMARK_LOOP1] -= System.nanoTime();
                for (EventInfo parent : parentsSigned) {
                    if (parent.birthRound >= rp.prevMinJudgeBirthRound  && parent.ancestorJudge[i]) {
                        ancestorJudge[i] = true;
                        break;
                    }
                }
                h.benchmarks[HashgraphInfo.BENCHMARK_LOOP1] += System.nanoTime();
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
            for (int m = 0; m < numNodes; m++) {
                if (m == creator) {
                    lastSee[m] = this;
                } else {
                    // find k = max(map(s1,votingRound))
                    long k = 1; // start at 1 to ensure max({}) = 1
                    h.benchmarks[HashgraphInfo.BENCHMARK_LOOP2] -= System.nanoTime();
                    for (EventInfo parent : h.parents) {
                        EventInfo y = parent.lastSee[m];
                        if (y != null && y.votingRound > k) {
                            k = y.votingRound;
                        }
                    }
                    h.benchmarks[HashgraphInfo.BENCHMARK_LOOP2] += System.nanoTime();
                    EventInfo w = null; // find w = firstSelfWitness(r,first(s2))
                    EventInfo p = null; // find p = event in s3 with the max gen
                    boolean s2empty = true;
                    long maxGen = -1;
                    h.benchmarks[HashgraphInfo.BENCHMARK_LOOP3] -= System.nanoTime();
                    for (EventInfo parent : h.parents) {
                        EventInfo y = parent.lastSee[m];
                        // y (if nonnull) is in s1
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
                    h.benchmarks[HashgraphInfo.BENCHMARK_LOOP3] += System.nanoTime();
                    lastSee[m] = s2empty ? null : p;
                }
            }
            // function stronglySeeP /----------------------------------------------------------------------------
            for (int m = 0; m < numNodes; m++) {
                EventInfo y, z, p = selfParent;
                // function seeThru /-----------------------------------------------------------------------------
                // do y = seeThru(r,x,m,m)
                if (creator == m) {
                    y = (p == null) ? null : p.firstSelfWitnessS;
                } else {
                    y = lastSee[m];
                    z = (y == null) ? null : y.lastSee[m];
                    y = (z == null) ? null : z.firstSelfWitnessS;
                }
                // finished y = seeThru(r,x,m,m)
                if (y == null || y.votingRound != parentRound) {
                    stronglySeeP[m] = null;
                } else {
                    long s = 0;
                    h.benchmarks[HashgraphInfo.BENCHMARK_LOOP4] -= System.nanoTime();
                    for (int mp = 0; mp < numNodes; mp++) {
                        EventInfo yp;
                        // function seeThru /---------------------------------------------------------------------
                        // do yp = seeThru(r,x,m,mp)
                        if (m == mp && creator == m) {
                            yp = (p == null) ? null : p.firstSelfWitnessS;
                        } else {
                            yp = lastSee[mp];
                            z = (yp == null) ? null : yp.lastSee[m];
                            yp = (z == null) ? null : z.firstSelfWitnessS;
                        }
                        // finished yp = seeThru(r,x,m,mp)
                        s += (yp != y) ? 0 : r.stake[mp];
                    }
                    h.benchmarks[HashgraphInfo.BENCHMARK_LOOP4] += System.nanoTime();
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
                    votingRound = b ? p + 1 : p;
                } else {
                    long stakeSum = 0;
                    for (int m = 0; m < r.nodes.length; m++) {
                        if (((creator == m) && (selfParent != null) && (selfParent.votingRound == p))
                                || ((creator != m) && (lastSee[m] != null) && (lastSee[m].votingRound == p))) {
                            stakeSum += r.stake[m];
                        }
                    }
                    if ((r.pendingRound == p) && (h.voteD == 1) && (h.totalStake * r.seeNum < r.seeDen * stakeSum)) {
                        votingRound = p + 1;
                    } else {
                        stakeSum = 0;
                        for (int m = 0; m < r.nodes.length; m++) {
                            if (stronglySeeP[m] != null) {
                                stakeSum += r.stake[m];
                            }
                        }
                        votingRound = (stakeSum >= h.supermajorityThreshold) ? p + 1 : p;
                    }
                }
            }
            { // function firstSelfWitnessS /---------------------------------------------------------------------
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
                for (EventInfo y : h.parents) {
                    if (y.votingRound == votingRound) {
                        firstWitnessS = y.firstWitnessS;
                        break;
                    }
                }
            }
            // function stronglySeeS1 /---------------------------------------------------------------------------
            if (firstWitnessS == null) {
                Arrays.fill(stronglySeeS1, null);
            } else {
                System.arraycopy(firstWitnessS.stronglySeeP, 0, stronglySeeS1, 0, numNodes);
            }
            // function witness /---------------------------------------------------------------------------------
            witness = (selfParent == null) || (votingRound > selfParent.votingRound);
            // Create data structures to make it fast to count votes for candidates.
            // If this event is a judge candidate for this round, then give it a new index and remember it.
            if (witness && (votingRound == r.pendingRound) && (creator >= 0)) {
                h.candCount++; // a new candidate has been found
                eventCandIndex = h.candCount - 1; // the event remembers its own index
                h.candIndex.get(creator).add(eventCandIndex); // maintain a list of indices for each creator
                if (h.candCount > h.candStakeCollected.length) { // if too big for arrays, then double their sizes
                    h.candStakeCollected = Arrays.copyOf(h.candStakeCollected, h.candCount * 2);
                    h.candEventInfo = Arrays.copyOf(h.candEventInfo, h.candCount * 2);
                }
                h.candEventInfo[eventCandIndex] = this;
            }
            // function vote /------------------------------------------------------------------------------------
            h.benchmarks[h.voteD == 2 ? HashgraphInfo.BENCHMARK_LOOP6 : HashgraphInfo.BENCHMARK_LOOP7] -=
                    System.nanoTime();
            for (int m = 0; m < numNodes; m++) { // find which candidate created by each m to vote for (null for none)
                long i = h.pendingRound + h.voteD; // first voting round
                voteE[m] = null; // default if not overridden before the "continue"
                voteB[m] = false; // default if not overridden before the "continue"
                voteIndex[m] = m; // index of m means null (voting for no event created by m to be a judge)
                if (!witness || votingRound < i) {
                    continue;
                }
                if (votingRound == i) { // if this is the first round of voting
                    // function firstVote /-----------------------------------------------------------------------
                    EventInfo firstVote;
                    if (h.voteD == 2) { // vote for a witness strongly seen by a witness that you strongly see.
                        firstVote = null;
                        for (int mp = 0; mp < numNodes; mp++) {
                            EventInfo t = stronglySeeS1[mp];
                            if (t != null) {
                                EventInfo s = t.stronglySeeS1[m];
                                if (s != null) {
                                    firstVote = s;
                                    break;
                                }
                            }
                        }
                    } else { // voteD = 1. Vote for a witness you can see. (Or the branch seen first, if branching)
                        EventInfo z = lastSee[m];
                        if (z == null) {
                            firstVote = null;
                        } else {
                            EventInfo v = z.firstSelfWitnessS;
                            if (v.votingRound == votingRound - 1) {
                                firstVote = v;
                            } else {
                                EventInfo y = v.selfParent;
                                if (y != null && y.votingRound == votingRound - 1) {
                                    firstVote = y.firstSelfWitnessS;
                                } else {
                                    firstVote = null;
                                }
                            }
                        }
                    }
                    voteE[m] = firstVote;
                    voteIndex[m] = (voteE[m] == null) ? m : voteE[m].eventCandIndex;
                } else { // not the first round of voting. (end of firstVote, continuing vote)
                    // function stakeAgrees /---------------------------------------------------------------------
                    // function topVote /-------------------------------------------------------------------------
                    // Instead of using these 2 functions from the paper, use h.cand* fields for more efficiency
                    h.benchmarks[h.voteD == 2 ? HashgraphInfo.BENCHMARK_LOOP6 : HashgraphInfo.BENCHMARK_LOOP7] +=
                            System.nanoTime(); // don't include topVote in loops 6 or 7
                    h.benchmarks[HashgraphInfo.BENCHMARK_LOOP8] -= System.nanoTime(); // first half of top vote
                    // collect all votes
                    Arrays.fill(h.candStakeCollected, 0);
                    for (int mp = 0; mp < numNodes; mp++) {
                        EventInfo t = stronglySeeS1[mp];
                        if (t != null) {
                            h.candStakeCollected[t.voteIndex[m]] += r.stake[mp];
                        }
                    }
                    h.benchmarks[HashgraphInfo.BENCHMARK_LOOP8] += System.nanoTime();
                    h.benchmarks[HashgraphInfo.BENCHMARK_LOOP9] -= System.nanoTime(); // second half of top vote
                    // find the top vote
                    int bestIndex = 0;
                    long bestStake = -1;
                    for (int index : h.candIndex.get(m)) {
                        long stake = h.candStakeCollected[index];
                        if (stake > bestStake) {
                            bestStake = stake;
                            bestIndex = index;
                        }
                    }
                    EventInfo v = h.candEventInfo[bestIndex];
                    boolean s = (bestStake > h.supermajorityThreshold);
                    h.benchmarks[HashgraphInfo.BENCHMARK_LOOP9] += System.nanoTime(); // end of loop 9 for topVote
                    h.benchmarks[h.voteD == 2 ? HashgraphInfo.BENCHMARK_LOOP6 : HashgraphInfo.BENCHMARK_LOOP7] -=
                            System.nanoTime(); // continue with loop 6 or loop 7 for vote
                    // end of topVote(), returning (v,s). Now continue with vote()
                    boolean q = (0 == ((votingRound - h.pendingRound) % r.coinInterval));
                    if (!q) { // if not a coin round, vote whatever vote had the majority collected
                        voteE[m] = v;
                        voteB[m] = s;
                        voteIndex[m] = (voteE[m] == null) ? m : voteE[m].eventCandIndex;
                        continue;
                    }
                    h.lastUpdateUsedCoin = true; // this is a coin round
                    if (s) { // if a coin round and collect a supermajority, vote that way, but don't decide
                        voteE[m] = v;
                        voteIndex[m] = (voteE[m] == null) ? m : voteE[m].eventCandIndex;
                        continue;
                    }
                    int mp =
                            Math.floorMod(coin, numNodes + 1); // "coin" field is a big random number. Actual coin is mp
                    if ((mp == numNodes) || (h.pendingRound != birthRound)) { // coin==numNodes means vote for null
                        continue; // if the coin chose null then vote null. (Or if birth round isn't pending round)
                    }
                    EventInfo w = stronglySeeS1[mp];
                    if (w == null) { // if the coin chose a voter that wasn't collected, then vote null
                        continue;
                    }
                    voteE[m] = w.voteE[m]; // vote the same as the vote collected from the voter that the coin chose
                    voteIndex[m] = w.voteIndex[m];
                    h.benchmarks[HashgraphInfo.BENCHMARK_LOOP7] += System.nanoTime();
                }
            } // end vote
            h.benchmarks[h.voteD == 2 ? HashgraphInfo.BENCHMARK_LOOP6 : HashgraphInfo.BENCHMARK_LOOP7] +=
                    System.nanoTime();
            // function judges /-----------------------------------------------------------------------------
            // set h.roundDecided to true iff this event decided the pending round
            h.roundDecided = witness;
            for (int m = 0; m < numNodes; m++) {
                h.roundDecided = h.roundDecided && voteB[m];
                if (!h.roundDecided) {
                    break;
                }
            }
            if (!h.roundDecided) { // if it didn't decide, then judges is {} and update() returns null now
                h.benchmarks[HashgraphInfo.BENCHMARK_UPDATE] += System.nanoTime();
                h.benchmarks[HashgraphInfo.BENCHMARK_UPDATE_COUNT]++;
                return null;
            }
            long s = 0; // total stake of all the elected judges
            for (int m = 0; m < numNodes; m++) {
                if (voteE[m] != null) {
                    s += r.stake[m];
                }
            }
            h.judges.clear();
            prevJudgesCopied = (s <= h.supermajorityThreshold);
            if (prevJudgesCopied) { // if not a supermajority, copy previous judges. This is VERY rare.
                Collections.addAll(h.judges, rp.prevJudges); // Some might not be in the current address book.
            } else {
                for (int m = 0; m < numNodes; m++) {
                    if (voteE[m] != null) {
                        h.judges.add(voteE[m]);
                    }
                }
            }
            h.judgesCapacity = Math.max(h.judgesCapacity, h.judges.size());
            judgesArray = h.judges.toArray(new EventInfo[0]);
            // function receivedEvent /--------------------------------------------------------------------------
            // function isReceived /------------------------------------------------------------------------------
            // function reachedCon /------------------------------------------------------------------------------
            // function isConsensus /-----------------------------------------------------------------------------
            // graphSearch finds each new event that reaches consensus (so isReceived and reachedCon are true),
            // sets isConsensus for it, finds all its receivedEvent events, and sets its receivedTim[] to be the
            // times from those received events.
            h.consensusEvents.clear();
            graphSearch(h, judgesArray, r.judgeCon1, h.consensusEvents);
            h.consensusEventsCapacity = Math.max(h.consensusEventsCapacity, h.consensusEvents.size());
            consensusEventsArray = h.consensusEvents.toArray(new EventInfo[0]);
            // function timeCon /---------------------------------------------------------------------------------
            // timeCon is gen + t (if judgeCon1 is true) or the median of sorted receivedTime (if false)
            // function before /----------------------------------------------------------------------------------
            // function consensusOrder /--------------------------------------------------------------------------
            // function consensusTimestamp /----------------------------------------------------------------------
            if (r.judgeCon1 && consensusEventsArray.length > 0) { // if each is ancestor of at least one judge
                Arrays.sort(consensusEventsArray, Comparator
                        .comparingLong((EventInfo e) -> e.gen) // sort by timeCon(r,d,x) which is just e.gen plus const
                        .thenComparingLong(e -> e.eventID) // tiebreaker is eventID then searchOrder
                        .thenComparingInt(e -> e.searchOrder));
                Instant roundTime;
                Arrays.setAll(h.sortInd, i -> i); // set array to [0, 1, ..., consensusEventsArray.length - 1]
                Arrays.sort(h.sortInd, (Integer i1, Integer i2) -> { // sort by received time, ascending
                    return (i2 >= judgesArray.length) ? -1
                            : (i1 >= judgesArray.length) ? 1
                            : judgesArray[i1].timeCreated.compareTo(judgesArray[i2].timeCreated);
                });
                long stake = 0; // sum of weights of judges with earlier received time
                int medianPos;
                for (medianPos = 0; 2 * stake < h.totalStake; medianPos++) {
                    stake += r.stake[judgesArray[h.sortInd[medianPos]].creator];
                }
                // the round timestamp is the weighted median created time of all the judges.
                roundTime = judgesArray[medianPos].timeCreated;
                for (int i = 0; i < consensusEventsArray.length; i++) {
                    consensusEventsArray[i].consensusOrder = i + rp.prevNumCons;
                    consensusEventsArray[i].consensusTimestamp = roundTime.plusNanos(i);
                }
            } else if (consensusEventsArray.length > 0) { // each new consensus event is an ancestor of all judges
                // put weighted median timestamp for each event into event.receivedTime[0]
                for (EventInfo event : consensusEventsArray) {
                    Arrays.setAll(h.sortInd, i -> i); // set array to [0, 1, ..., consensusEventsArray.length - 1]
                    Arrays.sort(h.sortInd, (Integer i1, Integer i2) -> { // sort by received time, ascending
                        return (i2 >= judgesArray.length) ? -1
                                : (i1 >= judgesArray.length) ? 1
                                  : event.receivedTime[i1].compareTo(event.receivedTime[i2]);
                    });
                    long stake = 0; // sum of weights of judges with earlier received time
                    int medianPos;
                    for (medianPos = 0; 2 * stake < h.totalStake; medianPos++) {
                        stake += r.stake[judgesArray[h.sortInd[medianPos]].creator];
                    }
                    event.consensusTimestamp = event.receivedTime[medianPos];
                }
                Arrays.sort(consensusEventsArray, Comparator
                        .comparing((EventInfo e) -> e.consensusTimestamp) // sort by weighted median time received
                        .thenComparingLong(e -> e.gen) // tiebreaker is gen then eventID then searchOrder
                        .thenComparingLong(e -> e.eventID)
                        .thenComparingInt(e -> e.searchOrder));
                for (int i = 0; i < consensusEventsArray.length; i++) {
                    consensusEventsArray[i].consensusOrder = i + rp.prevNumCons;
                }
            } // end of timeCon, before, consensusOrder, consensusTimestamp
            // the round reached consensus, so set the old judges to false and the new to true
            for (EventInfo judge : rp.prevJudges) {
                judge.isPrevJudge = false;
            }
            for (EventInfo judge : h.judges) {
                judge.isPrevJudge = true;
            }
            minJudgeBirthRound = Long.MAX_VALUE;
            for (EventInfo judge : h.judges) {
                minJudgeBirthRound = Math.min(minJudgeBirthRound, judge.birthRound);
            }
            h.benchmarks[HashgraphInfo.BENCHMARK_UPDATE] += System.nanoTime();
            h.pendingRound++; // require the next call to update to be for the next round
            h.newRound = true;
            return new UpdateResults(
                    consensusEventsArray, // consensusEvents
                    new RoundInfoPrev(
                            h.pendingRound, // pendingRound
                            r.judgeCon1, // prevJudgeCon1
                            judgesArray, // prevJudges
                            prevJudgesCopied, // prevJudgesCopied
                            h.minNonAncientRound, // prevMinNonAncientRound
                            rp.prevNumCons + consensusEventsArray.length, // prevNumCons
                            minJudgeBirthRound)); // prevMinJudgeBirthRound
        }
    }
}
