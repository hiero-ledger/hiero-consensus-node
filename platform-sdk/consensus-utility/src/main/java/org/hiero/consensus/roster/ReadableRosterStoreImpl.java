// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.roster;

import static java.util.Objects.requireNonNull;

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
import java.util.Optional;

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
     * Create a new {@link ReadableRosterStore} instance.
     *
     * @param readableStates The state to use.
     */
    public ReadableRosterStoreImpl(@NonNull final ReadableStates readableStates) {
        requireNonNull(readableStates);
        this.rosterState = readableStates.getSingleton(WritableRosterStore.ROSTER_STATES_KEY);
        this.rosterMap = readableStates.get(WritableRosterStore.ROSTER_KEY);
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
        final var activeRosterHash = getCurrentRosterHash();
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
    public Bytes getCurrentRosterHash() {
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

    @Nullable
    @Override
    public Bytes getPreviousRosterHash() {
        final var rosterHistory = getRosterHistory();
        return rosterHistory.size() > 1 ? rosterHistory.get(1).activeRosterHash() : null;
    }

    /** {@inheritDoc} */
    @Override
    public @NonNull List<RoundRosterPair> getRosterHistory() {
        return requireNonNull(rosterState.get()).roundRosterPairs().stream()
                .filter(pair -> rosterMap.contains(new ProtoBytes(pair.activeRosterHash())))
                .toList();
    }

    @Override
    public @Nullable Bytes getCandidateRosterHash() {
        return Optional.ofNullable(rosterState.get())
                .map(RosterState::candidateRosterHash)
                .filter(bytes -> bytes.length() > 0)
                .orElse(null);
    }
}
