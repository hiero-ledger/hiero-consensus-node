/*
 * Copyright (C) 2024-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.swirlds.platform.state;

import static com.swirlds.common.test.fixtures.RandomUtils.nextInt;
import static com.swirlds.platform.test.fixtures.state.FakeStateLifecycles.FAKE_MERKLE_STATE_LIFECYCLES;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.common.Reservable;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.platform.NodeId;
import com.swirlds.common.test.fixtures.Randotron;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.merkledb.MerkleDb;
import com.swirlds.platform.SwirldsPlatform;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.platform.state.signed.SignedState;
import com.swirlds.platform.system.BasicSoftwareVersion;
import com.swirlds.platform.system.Round;
import com.swirlds.platform.system.status.StatusActionSubmitter;
import com.swirlds.platform.test.fixtures.addressbook.RandomRosterBuilder;
import com.swirlds.platform.test.fixtures.state.RandomSignedStateGenerator;
import com.swirlds.platform.test.fixtures.state.TestMerkleStateRoot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SwirldsStateManagerTests {

    private SwirldStateManager swirldStateManager;
    private MerkleNodeState initialState;

    @BeforeEach
    void setup() {
        MerkleDb.resetDefaultInstancePath();
        final SwirldsPlatform platform = mock(SwirldsPlatform.class);
        final Roster roster = RandomRosterBuilder.create(Randotron.create()).build();
        when(platform.getRoster()).thenReturn(roster);
        PlatformStateFacade platformStateFacade = new PlatformStateFacade(v -> new BasicSoftwareVersion(v.major()));
        initialState = newState(platformStateFacade);
        final PlatformContext platformContext =
                TestPlatformContextBuilder.create().build();

        swirldStateManager = new SwirldStateManager(
                platformContext,
                roster,
                NodeId.of(0L),
                mock(StatusActionSubmitter.class),
                new BasicSoftwareVersion(1),
                FAKE_MERKLE_STATE_LIFECYCLES,
                platformStateFacade);
        swirldStateManager.setInitialState(initialState);
    }

    @AfterEach
    void tearDown() {
        RandomSignedStateGenerator.releaseAllBuiltSignedStates();
    }

    @Test
    @DisplayName("Initial State - state reference counts")
    void initialStateReferenceCount() {
        assertEquals(
                1,
                initialState.getReservationCount(),
                "The initial state is copied and should be referenced once as the previous immutable state.");
        Reservable consensusStateAsReservable = swirldStateManager.getConsensusState();
        assertEquals(
                1, consensusStateAsReservable.getReservationCount(), "The consensus state should have one reference.");
    }

    @Test
    @DisplayName("Seal consensus round")
    void sealConsensusRound() {
        final var round = mock(Round.class);
        swirldStateManager.sealConsensusRound(round);
        verify(round).getRoundNum();
    }

    @Test
    @DisplayName("Load From Signed State - state reference counts")
    void loadFromSignedStateRefCount() {
        final SignedState ss1 = newSignedState();
        final Reservable state1 = ss1.getState();
        MerkleDb.resetDefaultInstancePath();
        swirldStateManager.loadFromSignedState(ss1);

        assertEquals(
                2,
                state1.getReservationCount(),
                "Loading from signed state should increment the reference count, because it is now referenced by the "
                        + "signed state and the previous immutable state in SwirldStateManager.");
        final Reservable consensusState1 = swirldStateManager.getConsensusState();
        assertEquals(
                1,
                consensusState1.getReservationCount(),
                "The current consensus state should have a single reference count.");

        MerkleDb.resetDefaultInstancePath();
        final SignedState ss2 = newSignedState();
        MerkleDb.resetDefaultInstancePath();
        swirldStateManager.loadFromSignedState(ss2);
        final Reservable consensusState2 = swirldStateManager.getConsensusState();

        Reservable state2 = ss2.getState();
        assertEquals(
                2,
                state2.getReservationCount(),
                "Loading from signed state should increment the reference count, because it is now referenced by the "
                        + "signed state and the previous immutable state in SwirldStateManager.");
        assertEquals(
                1,
                consensusState2.getReservationCount(),
                "The current consensus state should have a single reference count.");
        assertEquals(
                1,
                state1.getReservationCount(),
                "The previous immutable state was replaced, so the old state's reference count should have been "
                        + "decremented.");
    }

    private static MerkleNodeState newState(PlatformStateFacade platformStateFacade) {
        final MerkleNodeState state = new TestMerkleStateRoot();
        FAKE_MERKLE_STATE_LIFECYCLES.initPlatformState(state);

        platformStateFacade.setCreationSoftwareVersionTo(state, new BasicSoftwareVersion(nextInt(1, 100)));

        assertEquals(0, state.getReservationCount(), "A brand new state should have no references.");
        return state;
    }

    private static SignedState newSignedState() {
        final SignedState ss = new RandomSignedStateGenerator().build();
        final Reservable state = ss.getState();
        assertEquals(
                1, state.getReservationCount(), "Creating a signed state should increment the state reference count.");
        return ss;
    }
}
