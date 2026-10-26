// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.app.services.roster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.Mockito.mock;

import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterState;
import com.hedera.hapi.node.state.roster.RoundRosterPair;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.test.fixtures.FunctionWritableSingletonState;
import com.swirlds.state.test.fixtures.MapWritableKVState;
import com.swirlds.state.test.fixtures.MapWritableStates;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import org.hiero.consensus.model.hashgraph.Round;
import org.hiero.consensus.roster.RosterStateId;
import org.hiero.consensus.roster.RosterUtils;
import org.hiero.consensus.roster.WritableRosterStore;
import org.hiero.consensus.roster.test.fixtures.RosterFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RosterServiceTest {

    private final Random random = new Random(0L);
    private final Roster genesisRoster = RosterFactory.randomRoster(random, 4);
    private final Bytes genesisRosterHash = RosterUtils.hash(genesisRoster).getBytes();

    private AtomicReference<RosterState> rosterStateBackingStore;
    private MapWritableKVState<ProtoBytes, Roster> rostersState;
    private MapWritableStates writableStates;

    @BeforeEach
    void setUp() {
        rosterStateBackingStore = new AtomicReference<>();
        rostersState = new MapWritableKVState<>(RosterStateId.ROSTERS_STATE_ID, RosterStateId.ROSTERS_STATE_LABEL);
        final FunctionWritableSingletonState<RosterState> singletonState = new FunctionWritableSingletonState<>(
                RosterStateId.ROSTER_STATE_STATE_ID,
                RosterStateId.ROSTER_STATE_STATE_LABEL,
                rosterStateBackingStore::get,
                rosterStateBackingStore::set);
        writableStates = MapWritableStates.builder()
                .state(singletonState)
                .state(rostersState)
                .build();
    }

    @Test
    void writesGenesisRosterInFirstRound() {
        final RosterService rosterService = new RosterService(genesisRoster);

        rosterService.onRoundStart(writableStates, mock(Round.class));
        writableStates.commit();

        assertThat(rosterStateBackingStore.get().roundRosterPairs())
                .containsExactly(new RoundRosterPair(0L, genesisRosterHash));
        assertThat(rostersState.getBackingStore())
                .containsOnly(entry(new ProtoBytes(genesisRosterHash), genesisRoster));
    }

    @Test
    void keepsRosterAfterGenesis() {
        new RosterService(genesisRoster).onRoundStart(writableStates, mock(Round.class));
        writableStates.commit();

        new RosterService(genesisRoster).onRoundStart(writableStates, mock(Round.class));
        writableStates.commit();

        assertThat(rosterStateBackingStore.get().roundRosterPairs())
                .containsExactly(new RoundRosterPair(0L, genesisRosterHash));
        assertThat(rostersState.getBackingStore())
                .containsOnly(entry(new ProtoBytes(genesisRosterHash), genesisRoster));
    }

    @Test
    void ignoresGenesisRosterIfStateContainsRoster() {
        final Roster existingRoster = RosterFactory.randomRoster(random, 4);
        final Bytes existingRosterHash = RosterUtils.hash(existingRoster).getBytes();
        new WritableRosterStore(writableStates).putActiveRoster(existingRoster, 7L);
        writableStates.commit();

        new RosterService(genesisRoster).onRoundStart(writableStates, mock(Round.class));
        writableStates.commit();

        assertThat(rosterStateBackingStore.get().roundRosterPairs())
                .containsExactly(new RoundRosterPair(7L, existingRosterHash));
        assertThat(rostersState.getBackingStore())
                .containsOnly(entry(new ProtoBytes(existingRosterHash), existingRoster));
    }
}
