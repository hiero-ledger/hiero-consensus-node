// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.roster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.state.roster.RosterState;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.spi.ReadableSingletonState;
import com.swirlds.state.spi.ReadableStates;
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

    private ReadableRosterStoreImpl subject;

    @BeforeEach
    void setUp() {
        given(readableStates.<RosterState>getSingleton(WritableRosterStore.ROSTER_STATES_KEY))
                .willReturn(rosterState);
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
