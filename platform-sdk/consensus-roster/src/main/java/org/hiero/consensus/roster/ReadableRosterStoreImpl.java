// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.roster;

import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toMap;

import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterState;
import com.hedera.hapi.node.state.roster.RoundRosterPair;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.spi.ReadableKVState;
import com.swirlds.state.spi.ReadableSingletonState;
import com.swirlds.state.spi.ReadableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.hiero.base.crypto.Cryptography;
import org.hiero.base.crypto.DigestType;
import org.hiero.consensus.model.roster.ConsensusLayerRosterInputs;

/**
 * Provides read-only methods for interacting with the underlying data storage mechanisms for
 * working with Rosters.
 */
public class ReadableRosterStoreImpl implements ReadableRosterStore {

    /**
     * The roster state singleton. This is the state that holds the candidate roster hash and the list of pairs of round
     * and active roster hashes.
     */
    private final ReadableSingletonState<RosterState> rosterState;

    /**
     * The key-value map of roster hashes and rosters.
     */
    private final ReadableKVState<ProtoBytes, Roster> rosterMap;

    /**
     * The digest type to hash rosters with while no roster hash is stored.
     */
    private final DigestType defaultDigestType;

    /**
     * Create a new {@link ReadableRosterStore} instance that hashes rosters with the platform's default digest type
     * while no roster hash is stored.
     *
     * @param readableStates The state to use.
     */
    public ReadableRosterStoreImpl(@NonNull final ReadableStates readableStates) {
        this(readableStates, Cryptography.DEFAULT_DIGEST_TYPE);
    }

    /**
     * Create a new {@link ReadableRosterStore} instance.
     *
     * @param readableStates The state to use.
     * @param defaultDigestType the digest type to hash rosters with while no roster hash is stored
     */
    public ReadableRosterStoreImpl(
            @NonNull final ReadableStates readableStates, @NonNull final DigestType defaultDigestType) {
        requireNonNull(readableStates);
        this.rosterState = readableStates.getSingleton(RosterStateId.ROSTER_STATE_STATE_ID);
        this.rosterMap = readableStates.get(RosterStateId.ROSTERS_STATE_ID);
        this.defaultDigestType = requireNonNull(defaultDigestType);
    }

    /**
     * Hashes a roster with the digest type of the stored roster hashes, so the result can be compared with them and
     * used to look rosters up.
     *
     * @param roster the roster to hash
     * @return the roster's hash
     */
    @NonNull
    public Bytes rosterHashOf(@NonNull final Roster roster) {
        return RosterUtils.hash(roster, rosterDigestType()).getBytes();
    }

    /**
     * Returns the digest type of the stored roster hashes, or the default one while none is stored.
     *
     * @return the digest type roster hashes are computed with
     */
    @NonNull
    protected DigestType rosterDigestType() {
        final var currentRosterState = rosterState.get();
        if (currentRosterState != null) {
            final var storedHash = currentRosterState.roundRosterPairs().isEmpty()
                    ? currentRosterState.candidateRosterHash()
                    : currentRosterState.roundRosterPairs().getFirst().activeRosterHash();
            final var storedDigestType = DigestType.digestLengthToDigestType((int) storedHash.length());
            if (storedDigestType != null) {
                return storedDigestType;
            }
        }
        return defaultDigestType;
    }

    /**
     * {@inheritDoc}
     */
    @Nullable
    @Override
    public Roster getCandidateRoster() {
        final RosterState rosterStateSingleton = rosterState.get();
        if (rosterStateSingleton == null) {
            return null;
        }
        final Bytes candidateRosterHash = rosterStateSingleton.candidateRosterHash();
        return rosterMap.get(ProtoBytes.newBuilder().value(candidateRosterHash).build());
    }

    /**
     * {@inheritDoc}
     */
    @Nullable
    @Override
    public Roster getActiveRoster() {
        final var activeRosterHash = getActiveRosterHash();
        if (activeRosterHash == null) {
            return null;
        }
        return rosterMap.get(ProtoBytes.newBuilder().value(activeRosterHash).build());
    }

    /**
     * {@inheritDoc}
     */
    @Nullable
    @Override
    public Roster get(@NonNull final Bytes rosterHash) {
        return rosterMap.get(ProtoBytes.newBuilder().value(rosterHash).build());
    }

    /**
     * {@inheritDoc}
     */
    @Nullable
    @Override
    public Bytes getActiveRosterHash() {
        final RosterState rosterStateSingleton = rosterState.get();
        if (rosterStateSingleton == null) {
            return null;
        }
        final List<RoundRosterPair> rostersAndRounds = rosterStateSingleton.roundRosterPairs();
        if (rostersAndRounds.isEmpty()) {
            return null;
        }
        // by design, the first round roster pair is the active roster
        // this may need to be revisited when we reach DAB
        final RoundRosterPair latestRoundRosterPair = rostersAndRounds.getFirst();
        return latestRoundRosterPair.activeRosterHash();
    }

    /**
     * {@inheritDoc}
     */
    @Nullable
    @Override
    public Bytes getPreviousRosterHash() {
        final var rosterHistory = requireNonNull(rosterState.get()).roundRosterPairs();
        return rosterHistory.size() > 1 ? rosterHistory.get(1).activeRosterHash() : null;
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public ConsensusLayerRosterInputs getConsensusLayerRosterInputs() {
        final RosterState rosterState = requireNonNull(this.rosterState.get());
        final List<RoundRosterPair> history = rosterState.roundRosterPairs();
        final Map<Bytes, Roster> rosterMap = history.stream()
                .collect(
                        toMap(RoundRosterPair::activeRosterHash, pair -> requireNonNull(get(pair.activeRosterHash()))));

        return new ConsensusLayerRosterInputs(history, rosterMap);
    }

    /**
     * {@inheritDoc}
     */
    @Nullable
    @Override
    public Bytes getCandidateRosterHash() {
        return Optional.ofNullable(rosterState.get())
                .map(RosterState::candidateRosterHash)
                .filter(bytes -> bytes.length() > 0)
                .orElse(null);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isTransplantInProgress() {
        return rosterState.get() != null && requireNonNull(rosterState.get()).transplantInProgress();
    }
}
