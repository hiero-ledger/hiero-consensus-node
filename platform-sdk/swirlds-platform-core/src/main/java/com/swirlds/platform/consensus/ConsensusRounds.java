// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.consensus;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.swirlds.logging.legacy.LogMarker;
import com.swirlds.platform.internal.EventImpl;
import com.swirlds.platform.roster.RosterUtils;
import com.swirlds.platform.state.MinimumJudgeInfo;
import com.swirlds.platform.system.events.EventConstants;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.LongStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Stores all hashgraph round information in a single place.
 *
 * <p>It keeps track of what rounds are stored and creates elections when needed.
 */
public class ConsensusRounds {
    private static final Logger logger = LogManager.getLogger(ConsensusRounds.class);
    /** consensus configuration */
    private final ConsensusConfig config;
    /** stores the minimum judge ancient identifier for all decided and non-expired rounds */
    private final SequentialRingBuffer<MinimumJudgeInfo> minimumJudgeStorage;
    /** a derivative of the only roster currently in use, until roster changes are implemented */
    private final Map<Long, RosterEntry> rosterEntryMap;
    /** The maximum round created of all the known witnesses */
    private long maxRoundCreated = ConsensusConstants.ROUND_UNDEFINED;
    /** The round we are currently voting on */
    private final RoundElections roundElections = new RoundElections();
    /** the minimum generation of all the judges that are not ancient */
    private long minGenNonAncient = EventConstants.FIRST_GENERATION;

    /** Constructs an empty object */
    public ConsensusRounds(@NonNull final ConsensusConfig config, @NonNull final Roster roster) {
        this.config = Objects.requireNonNull(config);
        this.minimumJudgeStorage =
                new SequentialRingBuffer<>(ConsensusConstants.ROUND_FIRST, config.roundsExpired() * 2);
        this.rosterEntryMap = RosterUtils.toMap(Objects.requireNonNull(roster));
        reset();
    }

    /** Reset this instance to its initial state, equivalent to creating a new instance */
    public void reset() {
        minimumJudgeStorage.reset(ConsensusConstants.ROUND_FIRST);
        maxRoundCreated = ConsensusConstants.ROUND_UNDEFINED;
        roundElections.reset();
        updateMinGenNonAncient();
    }

    /**
     * @return the round number below which the fame of all witnesses has been decided
     */
    public long getFameDecidedBelow() {
        return roundElections.getRound();
    }

    /**
     * A new witness has been received, add it to the appropriate round and create an election if needed
     *
     * @param witness the new witness
     */
    public void newWitness(@NonNull final EventImpl witness) {
        // track the latest known round created
        maxRoundCreated = Math.max(witness.getRoundCreated(), maxRoundCreated);
        if (!isElectionRound(witness.getRoundCreated())) {
            // this is not a witness we are currently voting on, so no need to create elections for
            // it yet
            return;
        }

        // theorem says this witness can't be famous if round R+2 exists
        // if this is true, we immediately mark this witness as not famous without any elections
        // also, if the witness is not in the AB, we decide that it's not famous
        if (maxRoundCreated >= witness.getRoundCreated() + 2
                || !rosterEntryMap.containsKey(witness.getCreatorId().id())) {
            witness.setFamous(false);
            witness.setFameDecided(true);
            return;
        }

        // the theorem doesn't apply, so we can't decide yet, so elections are needed
        roundElections.addWitness(witness);
    }

    /**
     * Notifies this instance that recalculating is starting again, so that any metadata can be reset.
     */
    public void recalculating() {
        // when starting recalculation, the highest round created will be the last round that is
        // decided
        maxRoundCreated = getLastRoundDecided();
    }

    /**
     * Checks if the event is older than the round generation of the latest decided round. If no round has been decided
     * yet, returns false.
     *
     * @param event the event to check
     * @return true if its older
     */
    public boolean isOlderThanDecidedRoundGeneration(@NonNull final EventImpl event) {
        return isAnyRoundDecided() // if no round has been decided, it can't be older
                && minimumJudgeStorage.get(getLastRoundDecided()).minimumJudgeAncientThreshold()
                        > event.getGeneration();
    }

    /**
     * @return true if we have decided fame for at least one round, this is false only at genesis
     */
    private boolean isAnyRoundDecided() {
        return roundElections.getRound() > ConsensusConstants.ROUND_FIRST;
    }

    /**
     * Notifies the instance that the current elections have been decided. This will start the next election.
     */
    public void currentElectionDecided() {
        minimumJudgeStorage.add(roundElections.getRound(), roundElections.createMinimumJudgeInfo());
        roundElections.startNextElection();
        // Delete the oldest rounds with round number which is expired
        minimumJudgeStorage.removeOlderThan(getFameDecidedBelow() - config.roundsExpired());
        updateMinGenNonAncient();
    }

    /**
     * Check if this event is a judge in the latest decided round
     *
     * @param event the event to check
     * @return true if it is a judge in the last round that was decided
     */
    public boolean isLastDecidedJudge(@NonNull final EventImpl event) {
        return event.isJudge() && event.getRoundCreated() == getLastRoundDecided();
    }

    private long getLastRoundDecided() {
        return roundElections.getRound() - 1;
    }

    /**
     * @return the current round we are voting on
     */
    public long getElectionRoundNumber() {
        return roundElections.getRound();
    }

    /**
     * @return the round we are currently voting on
     */
    public @NonNull RoundElections getElectionRound() {
        return roundElections;
    }

    /**
     * Is the supplied round number the current election round
     *
     * @param round the round number being queried
     * @return true if this is the election round number
     */
    private boolean isElectionRound(final long round) {
        return getElectionRoundNumber() == round;
    }

    /**
     * Used when loading rounds from a starting point (a signed state). It will create rounds with their minimum ancient
     * indicator numbers, but we won't know about the witnesses in these rounds. We also don't care about any other
     * information except for minimum ancient indicator since these rounds have already been decided beforehand.
     *
     * @param minimumJudgeInfos a list of round numbers and round ancient indicator pairs, in ascending round numbers
     */
    public void loadFromMinimumJudge(@NonNull final List<MinimumJudgeInfo> minimumJudgeInfos) {
        minimumJudgeStorage.reset(minimumJudgeInfos.getFirst().round());
        for (final MinimumJudgeInfo minimumJudgeInfo : minimumJudgeInfos) {
            minimumJudgeStorage.add(minimumJudgeInfo.round(), minimumJudgeInfo);
        }
        roundElections.setRound(minimumJudgeStorage.getLatest().round() + 1);
        updateMinGenNonAncient();
    }

    /**
     * @return A list of {@link MinimumJudgeInfo} for all decided and non-ancient rounds
     */
    public @NonNull List<MinimumJudgeInfo> getMinimumJudgeInfoList() {
        final long oldestNonAncientRound =
                RoundCalculationUtils.getOldestNonAncientRound(config.roundsNonAncient(), getLastRoundDecided());
        return LongStream.range(oldestNonAncientRound, getFameDecidedBelow())
                .mapToObj(this::getMinimumJudgeIndicator)
                .filter(Objects::nonNull)
                .toList();
    }

    private MinimumJudgeInfo getMinimumJudgeIndicator(final long round) {
        final MinimumJudgeInfo minimumJudgeInfo = minimumJudgeStorage.get(round);
        if (minimumJudgeInfo == null) {
            logger.error(
                    LogMarker.EXCEPTION.getMarker(),
                    "Missing round {}. Fame decided below {}, oldest non-ancient round {}",
                    round,
                    getFameDecidedBelow(),
                    RoundCalculationUtils.getOldestNonAncientRound(config.roundsNonAncient(), getLastRoundDecided()));
            return null;
        }
        return minimumJudgeInfo;
    }

    /**
     * @return the max round created, or {@link ConsensusConstants#ROUND_UNDEFINED} if none.
     */
    public long getMaxRound() {
        return maxRoundCreated;
    }

    /**
     * @return The minimum judge generation number from the oldest non-expired round, if we have expired any rounds.
     * Else this returns {@link EventConstants#FIRST_GENERATION}.
     */
    public long getMinRoundGeneration() {
        final MinimumJudgeInfo info = minimumJudgeStorage.get(minimumJudgeStorage.minIndex());
        return info == null ? EventConstants.FIRST_GENERATION : info.minimumJudgeAncientThreshold();
    }

    /**
     * Update the oldest non-ancient round generation
     *
     * <p>Executed only on consensus thread.
     */
    private void updateMinGenNonAncient() {
        if (getFameDecidedBelow() == ConsensusConstants.ROUND_FIRST) {
            // if no round has been decided, no events are ancient yet
            minGenNonAncient = EventConstants.FIRST_GENERATION;
            return;
        }
        final long nonAncientRound =
                RoundCalculationUtils.getOldestNonAncientRound(config.roundsNonAncient(), getLastRoundDecided());
        final MinimumJudgeInfo info = minimumJudgeStorage.get(nonAncientRound);
        minGenNonAncient = info.minimumJudgeAncientThreshold();
    }

    /**
     * Return the minimum generation of all the famous witnesses that are not in ancient rounds.
     *
     * <p>Define gen(R) to be the minimum generation of all the events that were famous witnesses in
     * round R.
     *
     * <p>If round R is the most recent round for which we have decided the fame of all the
     * witnesses, then any event with a generation less than gen(R - {@code Settings.state.roundsExpired}) is called an
     * “expired” event. And any non-expired event with a generation less than gen(R -
     * {@code Settings.state.roundsNonAncient} + 1) is an “ancient” event. If the event failed to achieve consensus
     * before becoming ancient, then it is “stale”. So every non-expired event with a generation before gen(R -
     * {@code Settings.state.roundsNonAncient} + 1) is either stale or consensus, not both.
     *
     * <p>Expired events can be removed from memory unless they are needed for an old signed state
     * that is still being used for something (such as still in the process of being written to disk).
     *
     * @return The minimum generation of all the judges that are not ancient. If no judges are ancient, returns
     * {@link EventConstants#FIRST_GENERATION}.
     */
    public long getMinGenerationNonAncient() {
        return minGenNonAncient;
    }

    /**
     * Checks if the supplied event is ancient or not. An event is ancient if its generation is smaller than the round
     * generation of the oldest non-ancient round.
     *
     * @param event the event to check
     * @return true if the event is ancient, false otherwise
     */
    public boolean isAncient(@NonNull final EventImpl event) {
        return event.getGeneration() < getMinGenerationNonAncient();
    }
}
