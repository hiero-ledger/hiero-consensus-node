// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.internal;

import com.swirlds.platform.consensus.CandidateWitness;
import com.swirlds.platform.consensus.DeGen;
import com.swirlds.platform.consensus.LocalConsensusGeneration;
import com.swirlds.platform.event.EventCounter;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.hiero.base.Clearable;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.model.event.EventDescriptorWrapper;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusConstants;
import org.hiero.consensus.model.node.NodeId;

/**
 * An internal platform event.
 * This class that stores temporary data that is used while calculating consensus inside the platform.
 * This data is not relevant after consensus has been calculated.
 */
public class EventImpl implements Clearable {
    /** The base event information, including some gossip specific information */
    private final PlatformEvent baseEvent;
    /** the round number in which this event reached a consensus order */
    private long roundReceived = ConsensusConstants.ROUND_UNDEFINED;
    /** the self parent of this */
    private EventImpl selfParent;
    /** the other parent of this */
    private EventImpl otherParent;
    /** has this event been cleared (because it was old and should be discarded)? */
    private boolean cleared = false;
    /** is this a witness? (is round > selfParent's round, or there is no self parent?) */
    private boolean isWitness;
    /** has this witness decided as famous? */
    private boolean isFamous;
    /** is this both a witness and the fame election is over? */
    private boolean isFameDecided;
    /** is this event a judge? */
    private boolean isJudge;
    /** is this part of the consensus order yet? */
    private boolean isConsensus;
    /**
     * a field used to store consensus time while it is still not finalized. depending on the phase of consensus
     * calculation, this field may or may not store the final consensus time.
     */
    private Instant preliminaryConsensusTimestamp;
    /** lastSee[m] is the last ancestor created by m (memoizes function from Swirlds-TR-2020-01) */
    private EventImpl[] lastSee;
    /**
     * stronglySeeP[m] is strongly-seen witness in parent round by m (memoizes function from Swirlds-TR-2020-01)
     */
    private EventImpl[] stronglySeeP;
    /**
     * The first witness that's a self-ancestor in the self round (memoizes function from Swirlds-TR-2020-01)
     */
    private EventImpl firstSelfWitnessS;
    /**
     * the first witness that's an ancestor in the self round (memoizes function from Swirlds-TR-2020-01)
     */
    private EventImpl firstWitnessS;
    /**
     * temporarily used during any graph algorithm that needs to mark vertices (events) already visited
     */
    private int mark;
    /**
     * the time at which each unique famous witness in the received round first received this event
     */
    private List<Instant> recTimes;
    /**
     * the created round of this event (max of parents', plus either 0 or 1. 1 if no parents. 0 if neg infinity)
     */
    private long roundCreated = ConsensusConstants.ROUND_UNDEFINED;
    /**
     * an array that holds votes for witness elections. the index for each vote matches the index of the witness in the
     * current election
     */
    private boolean[] votes;

    /** Local consensus generation, for more info, see {@link com.swirlds.platform.consensus.LocalConsensusGeneration} */
    private int cGen = LocalConsensusGeneration.GENERATION_UNDEFINED;

    /** The deterministic generation, see {@link DeGen} */
    private int deGen = 0;

    public EventImpl(
            @NonNull final PlatformEvent platformEvent,
            @Nullable final EventImpl selfParent,
            @Nullable final EventImpl otherParent) {
        Objects.requireNonNull(platformEvent, "baseEvent");
        this.selfParent = selfParent;
        this.otherParent = otherParent;
        // ConsensusImpl.currMark starts at 1 and counts up, so all events initially count as
        // unmarked
        this.mark = ConsensusConstants.EVENT_UNMARKED;
        this.baseEvent = platformEvent;
    }

    //
    // Getters and setters
    //

    /**
     * @return the base event
     */
    public @NonNull PlatformEvent getBaseEvent() {
        return baseEvent;
    }

    /**
     * @return the round number in which this event reached a consensus order, or
     * {@link ConsensusConstants#ROUND_UNDEFINED} if this event has not reached consensus
     */
    public long getRoundReceived() {
        return roundReceived;
    }

    /**
     * Set the round number in which this event reached a consensus order
     *
     * @param roundReceived the round number in which this event reached a consensus order
     */
    public void setRoundReceived(final long roundReceived) {
        this.roundReceived = roundReceived;
    }

    /**
     * @return the self parent of this
     */
    public @Nullable EventImpl getSelfParent() {
        return selfParent;
    }

    /**
     * @param selfParent the self parent of this
     */
    public void setSelfParent(@Nullable final EventImpl selfParent) {
        this.selfParent = selfParent;
    }

    /**
     * @return the other parent of this
     */
    public @Nullable EventImpl getOtherParent() {
        return otherParent;
    }

    /**
     * @param otherParent the other parent of this
     */
    public void setOtherParent(@Nullable final EventImpl otherParent) {
        this.otherParent = otherParent;
    }

    public boolean isWitness() {
        return isWitness;
    }

    public void setWitness(final boolean witness) {
        isWitness = witness;
    }

    public boolean isFamous() {
        return isFamous;
    }

    public void setFamous(final boolean famous) {
        isFamous = famous;
    }

    /**
     * @return is this both a witness and the fame election is over?
     */
    public boolean isFameDecided() {
        return isFameDecided;
    }

    /**
     * @param fameDecided is this both a witness and the fame election is over?
     */
    public void setFameDecided(final boolean fameDecided) {
        isFameDecided = fameDecided;
    }

    /**
     * @return true if this event is a judge
     */
    public boolean isJudge() {
        return isJudge;
    }

    /** Mark this event as a judge */
    public void setJudgeTrue() {
        isJudge = true;
    }

    /**
     * @return is this part of the consensus order yet?
     */
    public boolean isConsensus() {
        return isConsensus;
    }

    /**
     * @param consensus is this part of the consensus order yet?
     */
    public void setConsensus(final boolean consensus) {
        isConsensus = consensus;
    }

    /**
     * @return a field used to store consensus time while it is still not finalized. depending on the
     *     phase of consensus calculation, this field may or may not store the final consensus time.
     */
    public @Nullable Instant getPreliminaryConsensusTimestamp() {
        return preliminaryConsensusTimestamp;
    }

    /**
     * Set the preliminary consensus timestamp
     * @param preliminaryConsensusTimestamp the preliminary consensus timestamp
     */
    public void setPreliminaryConsensusTimestamp(@Nullable final Instant preliminaryConsensusTimestamp) {
        this.preliminaryConsensusTimestamp = preliminaryConsensusTimestamp;
    }

    /**
     * @param m the member ID
     * @return last ancestor created by m (memoizes lastSee function from Swirlds-TR-2020-01)
     */
    public @Nullable EventImpl getLastSee(final int m) {
        return lastSee[m];
    }

    /**
     * remember event, the last ancestor created by m (memoizes lastSee function from
     * Swirlds-TR-2020-01)
     *
     * @param m the member ID
     * @param event the last seen {@link EventImpl} object created by m
     */
    public void setLastSee(final int m, @Nullable final EventImpl event) {
        lastSee[m] = event;
    }

    /**
     * Initialize the lastSee array to hold n elements (for n &ge; 0) (memoizes lastSee function
     * from Swirlds-TR-2020-01)
     *
     * @param n number of members in the initial address book
     */
    public void initLastSee(final int n) {
        lastSee = n == 0 ? null : new EventImpl[n];
    }

    /**
     * @return the number of elements lastSee holds (memoizes lastSee function from
     *     Swirlds-TR-2020-01)
     */
    public int sizeLastSee() {
        return lastSee == null ? 0 : lastSee.length;
    }

    /**
     * @param m the member ID
     * @return strongly-seen witness in parent round by m (memoizes stronglySeeP function from
     *     Swirlds-TR-2020-01)
     */
    public @Nullable EventImpl getStronglySeeP(final int m) {
        return stronglySeeP[m];
    }

    /**
     * @return strongly-seen witness in parent round (memoizes stronglySeeP function from
     *     Swirlds-TR-2020-01)
     */
    public EventImpl[] getStronglySeeP() {
        return stronglySeeP;
    }

    /**
     * remember event, the strongly-seen witness in parent round by m (memoizes stronglySeeP
     * function from Swirlds-TR-2020-01)
     *
     * @param m the member ID
     * @param event the strongly-seen witness in parent round created by m
     */
    public void setStronglySeeP(final int m, @Nullable final EventImpl event) {
        stronglySeeP[m] = event;
    }

    /**
     * Initialize the stronglySeeP array to hold n elements (for n &ge; 0) (memoizes stronglySeeP
     * function from Swirlds-TR-2020-01)
     *
     * @param n number of members in AddressBook
     */
    public void initStronglySeeP(final int n) {
        stronglySeeP = n == 0 ? null : new EventImpl[n];
    }

    /**
     * @return the number of elements stronglySeeP holds (memoizes stronglySeeP function from
     *     Swirlds-TR-2020-01)
     */
    public int sizeStronglySeeP() {
        return stronglySeeP == null ? 0 : stronglySeeP.length;
    }

    /**
     * @return The first witness that's a self-ancestor in the self round (memoizes function from
     *     Swirlds-TR-2020-01)
     */
    public @Nullable EventImpl getFirstSelfWitnessS() {
        return firstSelfWitnessS;
    }

    /**
     * @param firstSelfWitnessS The first witness that's a self-ancestor in the self round (memoizes
     *     function from Swirlds-TR-2020-01)
     */
    public void setFirstSelfWitnessS(@Nullable final EventImpl firstSelfWitnessS) {
        this.firstSelfWitnessS = firstSelfWitnessS;
    }

    /**
     * @return the first witness that's an ancestor in the self round (memoizes function from
     *     Swirlds-TR-2020-01)
     */
    public @Nullable EventImpl getFirstWitnessS() {
        return firstWitnessS;
    }

    /**
     * @param firstWitnessS the first witness that's an ancestor in the self round (memoizes
     *     function from Swirlds-TR-2020-01)
     */
    public void setFirstWitnessS(@Nullable final EventImpl firstWitnessS) {
        this.firstWitnessS = firstWitnessS;
    }

    /**
     * @return temporarily used during any graph algorithm that needs to mark vertices (events)
     *     already visited
     */
    public int getMark() {
        return mark;
    }

    /**
     * @param mark temporarily used during any graph algorithm that needs to mark vertices (events)
     *     already visited
     */
    public void setMark(final int mark) {
        this.mark = mark;
    }

    /**
     * @return the time at which each unique famous witness in the received round first received
     *     this event
     */
    public @Nullable List<Instant> getRecTimes() {
        return recTimes;
    }

    /**
     * @param recTimes the time at which each unique famous witness in the received round first
     *     received this event
     */
    public void setRecTimes(@Nullable final List<Instant> recTimes) {
        this.recTimes = recTimes;
    }

    public long getRoundCreated() {
        return roundCreated;
    }

    public void setRoundCreated(final long roundCreated) {
        this.roundCreated = roundCreated;
    }

    /**
     * Initialize the voting array
     *
     * @param numWitnesses the number of witnesses we are voting on
     */
    public void initVoting(final int numWitnesses) {
        if (votes == null || votes.length < numWitnesses) {
            votes = new boolean[numWitnesses];
            return;
        }
        Arrays.fill(votes, false);
    }

    /**
     * Get this witness' vote on the witness provided
     *
     * @param witness the witness being voted on
     * @return true if it's a YES vote, false if it's a NO vote
     */
    public boolean getVote(@NonNull final CandidateWitness witness) {
        return votes != null && votes.length > witness.getElectionIndex() && votes[witness.getElectionIndex()];
    }

    /**
     * Get the vote for a specific election index.
     *
     * @param electionIndex the index of the election to retrieve the vote for
     * @return true if it's a YES vote, false if it's a NO vote
     */
    public boolean getVote(final int electionIndex) {
        return votes != null && votes.length > electionIndex && votes[electionIndex];
    }

    /**
     * Get the size of the votes array.
     *
     * @return the number of elements in the votes array. If the votes array is null, returns 0.
     */
    public int getVotesSize() {
        return votes == null ? 0 : votes.length;
    }

    /**
     * Set this witness' vote on the witness provided
     *
     * @param witness the witness being voted on
     * @param vote true if it's a YES vote, false if it's a NO vote
     */
    public void setVote(@NonNull final CandidateWitness witness, final boolean vote) {
        this.votes[witness.getElectionIndex()] = vote;
    }

    //
    // Clear methods
    //

    /**
     * Erase all references to other events within this event. This can be used so other events can
     * be garbage collected, even if this one still has things pointing to it. The numEventsInMemory
     * count is decremented here, and incremented when the event is instantiated, so it is important
     * to ensure that this is eventually called on every event.
     */
    @Override
    public void clear() {
        if (cleared) {
            return;
        }
        cleared = true;
        EventCounter.decrementLinkedEventCount();
        selfParent = null;
        otherParent = null;
        clearMetadata();
    }

    /** Clear all metadata used to calculate consensus, this metadata changes with every round */
    public void clearMetadata() {
        clearJudgeFlags();
        clearNonJudgeMetadata();
        DeGen.clearDeGen(this);
    }

    private void clearJudgeFlags() {
        setWitness(false);
        setFamous(false);
        setFameDecided(false);
        isJudge = false;
    }

    private void clearNonJudgeMetadata() {
        initLastSee(0);
        initStronglySeeP(0);
        setFirstSelfWitnessS(null);
        setFirstWitnessS(null);
        setRecTimes(null);
    }

    //
    // Convenience methods for data inside platform event
    //

    /**
     * Check if the event has a self parent.
     *
     * @return true if the event has a self parent
     */
    public boolean hasSelfParent() {
        return baseEvent.getSelfParent() != null;
    }

    /**
     * Check if the event has other parents.
     *
     * @return true if the event has other parents
     */
    public boolean hasOtherParent() {
        return !baseEvent.getOtherParents().isEmpty();
    }

    /**
     * @return returns {@link PlatformEvent#getTimeCreated()}}
     */
    public Instant getTimeCreated() {
        return baseEvent.getTimeCreated();
    }

    /**
     * @return returns {@link PlatformEvent#getHash()}}
     */
    public Hash getBaseHash() {
        return baseEvent.getHash();
    }

    /**
     * Get the consensus timestamp of this event
     *
     * @return the consensus timestamp of this event
     */
    public Instant getConsensusTimestamp() {
        return baseEvent.getConsensusTimestamp();
    }

    /**
     * Get the generation of this event
     *
     * @return the generation of this event
     */
    @Deprecated(forRemoval = true)
    public long getGeneration() {
        return baseEvent.getGeneration();
    }

    /**
     * Get the non-deterministic generation of this event
     *
     * @return the non-deterministic generation of this event
     */
    public long getNGen() {
        return baseEvent.getNGen();
    }

    /**
     * Get the birth round of this event
     *
     * @return the birth round of this event
     */
    public long getBirthRound() {
        return baseEvent.getBirthRound();
    }

    /**
     * Same as {@link PlatformEvent#getCreatorId()}
     */
    @NonNull
    public NodeId getCreatorId() {
        return baseEvent.getCreatorId();
    }

    /**
     * Returns the local consensus generation (cGen) of this event.
     *
     * @return the local consensus generation
     * @see com.swirlds.platform.consensus.LocalConsensusGeneration
     */
    public int getCGen() {
        return cGen;
    }

    /**
     * Sets the local consensus generation (cGen) of this event.
     *
     * @param cGen the local consensus generation to set
     * @see com.swirlds.platform.consensus.LocalConsensusGeneration
     */
    public void setCGen(final int cGen) {
        this.cGen = cGen;
    }

    /**
     * Returns the deterministic generation (deGen) of this event.
     *
     * @return the deterministic generation
     * @see com.swirlds.platform.consensus.DeGen
     */
    public int getDeGen() {
        return deGen;
    }

    /**
     * Sets the deterministic generation (deGen) of this event.
     *
     * @param deGen the deterministic generation to set
     * @see com.swirlds.platform.consensus.DeGen
     */
    public void setDeGen(final int deGen) {
        this.deGen = deGen;
    }

    //
    // Overrides
    //

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }

        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        final EventImpl event = (EventImpl) o;

        return Objects.equals(baseEvent, event.baseEvent) && roundReceived == event.roundReceived;
    }

    @Override
    public int hashCode() {
        return Objects.hash(baseEvent, roundReceived);
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();
        baseEvent.getDescriptor().shortString(sb);
        final List<EventDescriptorWrapper> allParents = baseEvent.getAllParents();
        for (final EventDescriptorWrapper parent : allParents) {
            parent.shortString(sb);
        }
        return sb.toString();
    }

    /**
     * Create a short string representation of this event without any parent information.
     * @return a short string
     */
    public String shortString() {
        return baseEvent.getDescriptor().shortString();
    }
}
