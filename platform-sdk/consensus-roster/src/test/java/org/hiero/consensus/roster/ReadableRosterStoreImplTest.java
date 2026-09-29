// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.roster;

import static org.hiero.consensus.roster.RosterStateId.ROSTERS_STATE_ID;
import static org.hiero.consensus.roster.RosterStateId.ROSTERS_STATE_LABEL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterState;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.spi.ReadableKVState;
import com.swirlds.state.spi.ReadableSingletonState;
import com.swirlds.state.spi.ReadableStates;
import com.swirlds.state.test.fixtures.MapReadableKVState;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReadableRosterStoreImplTest {
    @Mock
    private ReadableStates readableStates;

    @Mock
    private ReadableSingletonState<RosterState> rosterState;

    private final Map<ProtoBytes, Roster> rosterMap = new HashMap<>();

    private ReadableRosterStoreImpl subject;

    @BeforeEach
    void setUp() {
        given(readableStates.<RosterState>getSingleton(RosterStateId.ROSTER_STATE_STATE_ID))
                .willReturn(rosterState);
        final ReadableKVState<ProtoBytes, Roster> rosterKVState =
                new MapReadableKVState<>(ROSTERS_STATE_ID, ROSTERS_STATE_LABEL, rosterMap);
        given(readableStates.<ProtoBytes, Roster>get(RosterStateId.ROSTERS_STATE_ID))
                .willReturn(rosterKVState);
        subject = new ReadableRosterStoreImpl(readableStates);
    }

    @Test
    void nullCandidateRosterCasesPass() {
        assertNull(subject.getCandidateRosterHash());
        given(rosterState.get()).willReturn(RosterState.DEFAULT);
        assertNull(subject.getCandidateRosterHash());
    }

    @Test
    void nonNullCandidateRosterIsReturned() {
        final var fakeHash = Bytes.wrap("PRETEND");
        given(rosterState.get())
                .willReturn(
                        RosterState.newBuilder().candidateRosterHash(fakeHash).build());
        assertEquals(fakeHash, subject.getCandidateRosterHash());
    }
}
