// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.consensus;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.hapi.platform.state.MinimumJudgeInfo;
import com.swirlds.logging.legacy.LogMarker;
import com.swirlds.platform.internal.EventImpl;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.LongStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.event.AncientMode;
import org.hiero.consensus.model.event.EventConstants;
import org.hiero.consensus.model.event.NonDeterministicGeneration;
import org.hiero.consensus.model.hashgraph.ConsensusConstants;
import org.hiero.consensus.roster.RosterUtils;

/**
 * Stores all hashgraph round information in a single place.
 *
 * <p>It keeps track of what rounds are stored and creates elections when needed.
 */
public class ConsensusRounds {
    private static final Logger logger = LogManager.getLogger(ConsensusRounds.class);
    /** consensus configuration */
    private final ConsensusConfig config;
    /** the ancient mode currently in use */
    private final AncientMode ancientMode;
    /** stores the minimum judge ancient identifier for all decided and non-expired rounds */
    private final SequentialRingBuffer<MinimumJudgeInfo> minimumJudgeStorage;
    /** a derivative of the only roster currently in use, until roster changes are implemented */
    private final Map<Long, RosterEntry> rosterEntryMap;
    /** The maximum round created of all the known witnesses */
    private long maxRoundCreated = ConsensusConstants.ROUND_UNDEFINED;
    /** The round we are currently voting on */
    private final RoundElections roundElections = new RoundElections();
    /** the current threshold below which all events are ancient */
    private long ancientThreshold = EventConstants.ANCIENT_THRESHOLD_UNDEFINED;
    /**
     * The minimum non-deterministic generation of all the judges in the latest decided round. events with a lower
     * non-deterministic generation than this do not affect any consensus calculations and do not have their metadata
     * recalculated.
     */
    private long consensusRelevantNGen = NonDeterministicGeneration.GENERATION_UNDEFINED;

    /** Constructs an empty object */
    public ConsensusRounds(
            @NonNull final ConsensusConfig config,
            @NonNull final AncientMode ancientMode,
            @NonNull final Roster roster) {
        this.config = Objects.requireNonNull(config);
        this.ancientMode = Objects.requireNonNull(ancientMode);
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
        updateAncientThreshold();
        consensusRelevantNGen = NonDeterministicGeneration.GENERATION_UNDEFINED;
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
        return consensusRelevantNGen > event.getNGen();
    }

    /**
     * Setter for the {@link #consensusRelevantNGen} field. This is used when loading consensus from a snapshot.
     *
     * @param consensusRelevantNGen the value to set
     */
    public void setConsensusRelevantNGen(final long consensusRelevantNGen) {
        this.consensusRelevantNGen = consensusRelevantNGen;
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
        minimumJudgeStorage.add(roundElections.getRound(), roundElections.createMinimumJudgeInfo(ancientMode));
        consensusRelevantNGen = roundElections.getMinNGen();
        roundElections.startNextElection();
        // Delete the oldest rounds with round number which is expired
        minimumJudgeStorage.removeOlderThan(getFameDecidedBelow() - config.roundsExpired());
        updateAncientThreshold();
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
        updateAncientThreshold();
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
     * Similar to {@link #getAncientThreshold()} but for expired rounds.
     *
     * @return the threshold for expired rounds
     */
    public long getExpiredThreshold() {
        final MinimumJudgeInfo info = minimumJudgeStorage.get(minimumJudgeStorage.minIndex());
        return info == null ? EventConstants.ANCIENT_THRESHOLD_UNDEFINED : info.minimumJudgeAncientThreshold();
    }

    /**
     * Update the current ancient threshold based on the latest round decided.
     */
    private void updateAncientThreshold() {
        if (!isAnyRoundDecided()) {
            // if no round has been decided, no events are ancient yet
            ancientThreshold = EventConstants.ANCIENT_THRESHOLD_UNDEFINED;
            return;
        }
        final long nonAncientRound =
                RoundCalculationUtils.getOldestNonAncientRound(config.roundsNonAncient(), getLastRoundDecided());
        final MinimumJudgeInfo info = minimumJudgeStorage.get(nonAncientRound);
        ancientThreshold = info.minimumJudgeAncientThreshold();
    }

    /**
     * Returns the threshold of all the judges that are not in ancient rounds. This is either a generation value or a
     * birth round value, depending on the ancient mode configured. If no judges are ancient, returns
     * {@link EventConstants#FIRST_GENERATION} or {@link ConsensusConstants#ROUND_FIRST} depending on the ancient mode.
     *
     * @return the threshold
     */
    public long getAncientThreshold() {
        return ancientThreshold;
    }
}
