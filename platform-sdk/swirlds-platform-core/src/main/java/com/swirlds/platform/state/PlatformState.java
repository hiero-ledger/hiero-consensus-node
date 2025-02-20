// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state;

import com.swirlds.common.crypto.Hash;
import com.swirlds.common.io.streams.SerializableDataInputStream;
import com.swirlds.common.io.streams.SerializableDataOutputStream;
import com.swirlds.common.merkle.MerkleLeaf;
import com.swirlds.common.merkle.impl.PartialMerkleLeaf;
import com.swirlds.platform.consensus.ConsensusSnapshot;
import com.swirlds.platform.event.AncientMode;
import com.swirlds.platform.system.SoftwareVersion;
import com.swirlds.platform.system.address.AddressBook;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * State managed and used by the platform.
 * @deprecated Implementation of {@link PlatformStateModifier} before moving platform state into State API. This class
 * should be moved to the platform test fixtures after migration to 0.54.0.
 */
@Deprecated(since = "0.54.0", forRemoval = true)
public class PlatformState extends PartialMerkleLeaf implements MerkleLeaf, PlatformStateModifier {

    public static final long CLASS_ID = 0x52cef730a11cb6dfL;

    private static final class ClassVersion {
        public static final int ORIGINAL = 1;
        /**
         * Added state to allow for birth round migration.
         */
        public static final int BIRTH_ROUND_MIGRATION_PATHWAY = 2;
        /**
         * Added the new running event hash algorithm.
         */
        public static final int RUNNING_EVENT_HASH = 3;
        /**
         * Removed the running event hash algorithm.
         */
        public static final int REMOVED_EVENT_HASH = 4;
        /**
         * Removed epoch hash fields.
         */
        public static final int REMOVED_EPOCH_HASH = 5;
        /**
         * Removed the uptime data.
         */
        public static final int REMOVED_UPTIME_DATA = 6;
    }

    /**
     * The address book for this round.
     */
    private AddressBook addressBook;

    /**
     * The previous address book. A temporary workaround until dynamic address books are supported.
     */
    private AddressBook previousAddressBook;

    /**
     * The round of this state. This state represents the handling of all transactions that have reached consensus in
     * all previous rounds. All transactions from this round will eventually be applied to this state. The first state
     * (genesis state) has a round of 0 because the first round is defined as round 1, and the genesis state is before
     * any transactions are handled.
     */
    private long round = GENESIS_ROUND;

    /**
     * The running event hash computed by the consensus event stream. This should be deleted once the consensus event
     * stream is retired.
     */
    private Hash legacyRunningEventHash;

    /**
     * the consensus timestamp for this signed state
     */
    private Instant consensusTimestamp;

    /**
     * The version of the application software that was responsible for creating this state.
     */
    private SoftwareVersion creationSoftwareVersion;

    /**
     * The number of non-ancient rounds.
     */
    private int roundsNonAncient;

    /**
     * A snapshot of the consensus state at the end of the round, used for restart/reconnect
     */
    private ConsensusSnapshot snapshot;

    /**
     * the time when the freeze starts
     */
    private Instant freezeTime;

    /**
     * the last time when a freeze was performed
     */
    private Instant lastFrozenTime;

    /**
     * Null if birth round migration has not yet happened, otherwise the software version that was first used when the
     * birth round migration was performed.
     */
    private SoftwareVersion firstVersionInBirthRoundMode;

    /**
     * The last round before the birth round mode was enabled, or -1 if birth round mode has not yet been enabled.
     */
    private long lastRoundBeforeBirthRoundMode = -1;

    /**
     * The lowest judge generation before the birth round mode was enabled, or -1 if birth round mode has not yet been
     * enabled.
     */
    private long lowestJudgeGenerationBeforeBirthRoundMode = -1;

    public PlatformState() {}

    /**
     * Copy constructor.
     *
     * @param that the object to copy
     */
    private PlatformState(final PlatformState that) {
        super(that);
        this.addressBook = that.addressBook == null ? null : that.addressBook.copy();
        this.previousAddressBook = that.previousAddressBook == null ? null : that.previousAddressBook.copy();
        this.round = that.round;
        this.legacyRunningEventHash = that.legacyRunningEventHash;
        this.consensusTimestamp = that.consensusTimestamp;
        this.creationSoftwareVersion = that.creationSoftwareVersion;
        this.roundsNonAncient = that.roundsNonAncient;
        this.snapshot = that.snapshot;
        this.freezeTime = that.freezeTime;
        this.lastFrozenTime = that.lastFrozenTime;
        this.firstVersionInBirthRoundMode = that.firstVersionInBirthRoundMode;
        this.lastRoundBeforeBirthRoundMode = that.lastRoundBeforeBirthRoundMode;
        this.lowestJudgeGenerationBeforeBirthRoundMode = that.lowestJudgeGenerationBeforeBirthRoundMode;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getClassId() {
        return CLASS_ID;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void serialize(final SerializableDataOutputStream out) throws IOException {
        out.writeSerializable(addressBook, false);
        out.writeSerializable(previousAddressBook, true);
        out.writeLong(round);
        out.writeSerializable(legacyRunningEventHash, false);
        out.writeInstant(consensusTimestamp);
        out.writeSerializable(creationSoftwareVersion, true);
        out.writeInt(roundsNonAncient);
        out.writeSerializable(snapshot, false);
        out.writeInstant(freezeTime);
        out.writeInstant(lastFrozenTime);
        out.writeSerializable(firstVersionInBirthRoundMode, true);
        out.writeLong(lastRoundBeforeBirthRoundMode);
        out.writeLong(lowestJudgeGenerationBeforeBirthRoundMode);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void deserialize(final SerializableDataInputStream in, final int version) throws IOException {
        addressBook = in.readSerializable(false, AddressBook::new);
        previousAddressBook = in.readSerializable(true, AddressBook::new);
        round = in.readLong();
        legacyRunningEventHash = in.readSerializable(false, Hash::new);
        consensusTimestamp = in.readInstant();
        creationSoftwareVersion = in.readSerializable();
        if (version < ClassVersion.REMOVED_EPOCH_HASH) {
            in.readSerializable(false, Hash::new);
        }
        roundsNonAncient = in.readInt();
        snapshot = in.readSerializable(false, ConsensusSnapshot::new);
        freezeTime = in.readInstant();
        lastFrozenTime = in.readInstant();
        if (version < ClassVersion.REMOVED_UPTIME_DATA) {
            skipUptimeData(in);
        }
        if (version >= ClassVersion.BIRTH_ROUND_MIGRATION_PATHWAY) {
            firstVersionInBirthRoundMode = in.readSerializable();
            lastRoundBeforeBirthRoundMode = in.readLong();
            lowestJudgeGenerationBeforeBirthRoundMode = in.readLong();
        }
        if (version == ClassVersion.RUNNING_EVENT_HASH) {
            in.readSerializable(false, Hash::new);
        }
    }

    private void skipUptimeData(final @NonNull SerializableDataInputStream in) throws IOException {
        // uptime data version
        in.readInt();
        int numOfEntries = in.readInt();
        for (int i = 0; i < numOfEntries; i++) {
            // key version
            in.readInt();
            // nodeId
            in.readLong();
            // value version
            in.readInt();
            // lastEventRound
            in.readLong();
            // lastEventTime
            in.readInstant();
            // lastJudgeRound
            in.readLong();
            // lastJudgeTime
            in.readInstant();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getVersion() {
        return ClassVersion.REMOVED_UPTIME_DATA;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public PlatformState copy() {
        return new PlatformState(this);
    }

    /**
     * Get the software version of the application that created this state.
     *
     * @return the creation version
     */
    @NonNull
    @Override
    public SoftwareVersion getCreationSoftwareVersion() {
        return creationSoftwareVersion;
    }

    /**
     * Set the software version of the application that created this state.
     *
     * @param creationVersion the creation version
     */
    @Override
    public void setCreationSoftwareVersion(@NonNull final SoftwareVersion creationVersion) {
        this.creationSoftwareVersion = Objects.requireNonNull(creationVersion);
    }

    /**
     * Get the address book.
     */
    @Override
    @Nullable
    public AddressBook getAddressBook() {
        return addressBook;
    }

    /**
     * Set the address book.
     *
     * @param addressBook an address book
     */
    @Override
    public void setAddressBook(@Nullable final AddressBook addressBook) {
        this.addressBook = addressBook;
    }

    /**
     * Get the previous address book.
     */
    @Override
    @Nullable
    public AddressBook getPreviousAddressBook() {
        return previousAddressBook;
    }

    /**
     * Set the previous address book.
     *
     * @param addressBook an address book
     */
    @Override
    public void setPreviousAddressBook(@Nullable final AddressBook addressBook) {
        this.previousAddressBook = addressBook;
    }

    /**
     * Get the round when this state was generated.
     *
     * @return a round number
     */
    @Override
    public long getRound() {
        return round;
    }

    /**
     * Set the round when this state was generated.
     *
     * @param round a round number
     */
    @Override
    public void setRound(final long round) {
        this.round = round;
    }

    /**
     * Get the legacy running event hash. Used by the consensus event stream.
     *
     * @return a running hash of events
     */
    @Override
    @Nullable
    public Hash getLegacyRunningEventHash() {
        return legacyRunningEventHash;
    }

    /**
     * Set the legacy running event hash. Used by the consensus event stream.
     *
     * @param legacyRunningEventHash a running hash of events
     */
    @Override
    public void setLegacyRunningEventHash(@Nullable final Hash legacyRunningEventHash) {
        this.legacyRunningEventHash = legacyRunningEventHash;
    }

    /**
     * Get the consensus timestamp for this state, defined as the timestamp of the first transaction that was applied in
     * the round that created the state.
     *
     * @return a consensus timestamp
     */
    @Override
    @Nullable
    public Instant getConsensusTimestamp() {
        return consensusTimestamp;
    }

    /**
     * Set the consensus timestamp for this state, defined as the timestamp of the first transaction that was applied in
     * the round that created the state.
     *
     * @param consensusTimestamp a consensus timestamp
     */
    @Override
    public void setConsensusTimestamp(@NonNull final Instant consensusTimestamp) {
        this.consensusTimestamp = Objects.requireNonNull(consensusTimestamp);
    }

    /**
     * For the oldest non-ancient round, get the lowest ancient indicator out of all of those round's judges. This is
     * the ancient threshold at the moment after this state's round reached consensus. All events with an ancient
     * indicator that is greater than or equal to this value are non-ancient. All events with an ancient indicator less
     * than this value are ancient.
     *
     * <p>
     * When running in {@link AncientMode#GENERATION_THRESHOLD}, this value is the minimum generation non-ancient. When
     * running in {@link AncientMode#BIRTH_ROUND_THRESHOLD}, this value is the minimum birth round non-ancient.
     * </p>
     *
     * @return the ancient threshold after this round has reached consensus
     */
    @Override
    public long getAncientThreshold() {
        if (snapshot == null) {
            throw new IllegalStateException(
                    "No minimum judge info found in state for round " + round + ", snapshot is null");
        }

        final List<MinimumJudgeInfo> minimumJudgeInfo = snapshot.getMinimumJudgeInfoList();
        if (minimumJudgeInfo.isEmpty()) {
            throw new IllegalStateException(
                    "No minimum judge info found in state for round " + round + ", list is empty");
        }

        return minimumJudgeInfo.getFirst().minimumJudgeAncientThreshold();
    }

    /**
     * Sets the number of non-ancient rounds.
     *
     * @param roundsNonAncient the number of non-ancient rounds
     */
    @Override
    public void setRoundsNonAncient(final int roundsNonAncient) {
        this.roundsNonAncient = roundsNonAncient;
    }

    /**
     * Gets the number of non-ancient rounds.
     *
     * @return the number of non-ancient rounds
     */
    @Override
    public int getRoundsNonAncient() {
        return roundsNonAncient;
    }

    /**
     * @return the consensus snapshot for this round
     */
    @Override
    @Nullable
    public ConsensusSnapshot getSnapshot() {
        return snapshot;
    }

    /**
     * @param snapshot the consensus snapshot for this round
     */
    @Override
    public void setSnapshot(@NonNull final ConsensusSnapshot snapshot) {
        this.snapshot = Objects.requireNonNull(snapshot);
    }

    /**
     * Gets the time when the next freeze is scheduled to start. If null then there is no freeze scheduled.
     *
     * @return the time when the freeze starts
     */
    @Override
    @Nullable
    public Instant getFreezeTime() {
        return freezeTime;
    }

    /**
     * Sets the instant after which the platform will enter FREEZING status. When consensus timestamp of a signed state
     * is after this instant, the platform will stop creating events and accepting transactions. This is used to safely
     * shut down the platform for maintenance.
     *
     * @param freezeTime an Instant in UTC
     */
    @Override
    public void setFreezeTime(@Nullable final Instant freezeTime) {
        this.freezeTime = freezeTime;
    }

    /**
     * Gets the last freezeTime based on which the nodes were frozen. If null then there has never been a freeze.
     *
     * @return the last freezeTime based on which the nodes were frozen
     */
    @Override
    @Nullable
    public Instant getLastFrozenTime() {
        return lastFrozenTime;
    }

    /**
     * Sets the last freezeTime based on which the nodes were frozen.
     *
     * @param lastFrozenTime the last freezeTime based on which the nodes were frozen
     */
    @Override
    public void setLastFrozenTime(@Nullable final Instant lastFrozenTime) {
        this.lastFrozenTime = lastFrozenTime;
    }

    /**
     * Get the first software version where the birth round migration happened, or null if birth round migration has not
     * yet happened.
     *
     * @return the first software version where the birth round migration happened
     */
    @Override
    @Nullable
    public SoftwareVersion getFirstVersionInBirthRoundMode() {
        return firstVersionInBirthRoundMode;
    }

    /**
     * Set the first software version where the birth round migration happened.
     *
     * @param firstVersionInBirthRoundMode the first software version where the birth round migration happened
     */
    @Override
    public void setFirstVersionInBirthRoundMode(final SoftwareVersion firstVersionInBirthRoundMode) {
        this.firstVersionInBirthRoundMode = firstVersionInBirthRoundMode;
    }

    /**
     * Get the last round before the birth round mode was enabled, or -1 if birth round mode has not yet been enabled.
     *
     * @return the last round before the birth round mode was enabled
     */
    @Override
    public long getLastRoundBeforeBirthRoundMode() {
        return lastRoundBeforeBirthRoundMode;
    }

    /**
     * Set the last round before the birth round mode was enabled.
     *
     * @param lastRoundBeforeBirthRoundMode the last round before the birth round mode was enabled
     */
    @Override
    public void setLastRoundBeforeBirthRoundMode(final long lastRoundBeforeBirthRoundMode) {
        this.lastRoundBeforeBirthRoundMode = lastRoundBeforeBirthRoundMode;
    }

    /**
     * Get the lowest judge generation before the birth round mode was enabled, or -1 if birth round mode has not yet
     * been enabled.
     *
     * @return the lowest judge generation before the birth round mode was enabled
     */
    @Override
    public long getLowestJudgeGenerationBeforeBirthRoundMode() {
        return lowestJudgeGenerationBeforeBirthRoundMode;
    }

    /**
     * Set the lowest judge generation before the birth round mode was enabled.
     *
     * @param lowestJudgeGenerationBeforeBirthRoundMode the lowest judge generation before the birth round mode was
     *                                                  enabled
     */
    @Override
    public void setLowestJudgeGenerationBeforeBirthRoundMode(final long lowestJudgeGenerationBeforeBirthRoundMode) {
        this.lowestJudgeGenerationBeforeBirthRoundMode = lowestJudgeGenerationBeforeBirthRoundMode;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void bulkUpdate(@NonNull Consumer<PlatformStateModifier> updater) {
        updater.accept(this);
    }
}
