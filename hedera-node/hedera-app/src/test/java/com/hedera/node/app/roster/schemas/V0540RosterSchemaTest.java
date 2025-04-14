// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.roster.schemas;

import static com.hedera.node.app.fixtures.AppTestBase.DEFAULT_CONFIG;
import static com.hedera.node.app.roster.schemas.V0540RosterSchema.ROSTER_KEY;
import static com.hedera.node.app.roster.schemas.V0540RosterSchema.ROSTER_STATES_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.node.internal.network.Network;
import com.hedera.node.internal.network.NodeMetadata;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.platform.state.service.WritableRosterStore;
import com.swirlds.state.State;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.lifecycle.StartupNetworks;
import com.swirlds.state.lifecycle.StateDefinition;
import com.swirlds.state.spi.WritableStates;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link V0540RosterSchema}.
 */
@ExtendWith(MockitoExtension.class)
class V0540RosterSchemaTest {
    private static final long ROUND_NO = 666L;
    private static final Network NETWORK = Network.newBuilder()
            .nodeMetadata(NodeMetadata.newBuilder()
                    .rosterEntry(RosterEntry.newBuilder().nodeId(1L).build())
                    .build())
            .build();
    private static final Roster ROSTER = new Roster(NETWORK.nodeMetadata().stream()
            .map(NodeMetadata::rosterEntryOrThrow)
            .toList());

    @Mock
    private MigrationContext ctx;

    @Mock
    private WritableStates writableStates;

    @Mock
    private WritableRosterStore rosterStore;

    @Mock
    private StartupNetworks startupNetworks;

    @Mock
    private Function<WritableStates, WritableRosterStore> rosterStoreFactory;

    @Mock
    private BiConsumer<Roster, Roster> onAdopt;

    @Mock
    private Predicate<Roster> canAdopt;

    @Mock
    private State state;

    @Mock
    private PlatformStateFacade platformStateFacade;

    private State getState() {
        return state;
    }

    private V0540RosterSchema subject;

    @BeforeEach
    void setUp() {
        subject = new V0540RosterSchema(onAdopt, canAdopt, rosterStoreFactory, this::getState, platformStateFacade);
    }

    @Test
    void registersExpectedSchema() {
        final var statesToCreate = subject.statesToCreate();
        assertThat(statesToCreate).hasSize(2);
        final var iter =
                statesToCreate.stream().map(StateDefinition::stateKey).sorted().iterator();
        assertEquals(ROSTER_KEY, iter.next(), "Unexpected Roster key!");
        assertEquals(ROSTER_STATES_KEY, iter.next(), "Unexpected RosterState key!");
    }

    @Test
    void usesGenesisRosterIfLifecycleEnabledAndApropros() {
        given(ctx.newStates()).willReturn(writableStates);
        given(ctx.isGenesis()).willReturn(true);
        given(ctx.startupNetworks()).willReturn(startupNetworks);
        given(ctx.platformConfig()).willReturn(DEFAULT_CONFIG);
        given(startupNetworks.genesisNetworkOrThrow(DEFAULT_CONFIG)).willReturn(NETWORK);
        given(rosterStoreFactory.apply(writableStates)).willReturn(rosterStore);

        subject.restart(ctx);

        verify(rosterStore).putActiveRoster(ROSTER, 0L);
    }

    @Test
    void noOpIfNotUpgradeAndActiveRosterPresent() {
        given(ctx.newStates()).willReturn(writableStates);
        given(ctx.startupNetworks()).willReturn(startupNetworks);
        given(rosterStoreFactory.apply(writableStates)).willReturn(rosterStore);
        given(rosterStore.getActiveRoster()).willReturn(ROSTER);

        subject.restart(ctx);

        verify(rosterStore).getActiveRoster();
        verifyNoMoreInteractions(rosterStore);
    }

    @Test
    void doesNotAdoptNullCandidateRoster() {
        given(ctx.newStates()).willReturn(writableStates);
        given(ctx.startupNetworks()).willReturn(startupNetworks);
        given(rosterStoreFactory.apply(writableStates)).willReturn(rosterStore);
        given(rosterStore.getActiveRoster()).willReturn(ROSTER);
        given(ctx.isUpgrade(any(), any())).willReturn(true);

        subject.restart(ctx);

        verify(rosterStore).getActiveRoster();
        verify(rosterStore).getCandidateRoster();
        verifyNoMoreInteractions(rosterStore);
    }

    @Test
    void doesNotAdoptCandidateRosterIfNotSpecified() {
        given(ctx.newStates()).willReturn(writableStates);
        given(ctx.startupNetworks()).willReturn(startupNetworks);
        given(rosterStoreFactory.apply(writableStates)).willReturn(rosterStore);
        given(rosterStore.getActiveRoster()).willReturn(ROSTER);
        given(ctx.isUpgrade(any(), any())).willReturn(true);
        given(rosterStore.getCandidateRoster()).willReturn(ROSTER);
        given(canAdopt.test(ROSTER)).willReturn(false);

        subject.restart(ctx);

        verify(rosterStore).getActiveRoster();
        verify(rosterStore).getCandidateRoster();
        verifyNoMoreInteractions(rosterStore);
    }

    @Test
    void adoptsCandidateRosterIfTestPasses() {
        given(ctx.newStates()).willReturn(writableStates);
        given(ctx.startupNetworks()).willReturn(startupNetworks);
        given(rosterStoreFactory.apply(writableStates)).willReturn(rosterStore);
        given(rosterStore.getActiveRoster()).willReturn(ROSTER);
        given(ctx.isUpgrade(any(), any())).willReturn(true);
        given(rosterStore.getCandidateRoster()).willReturn(ROSTER);
        given(canAdopt.test(ROSTER)).willReturn(true);
        given(ctx.roundNumber()).willReturn(ROUND_NO);

        subject.restart(ctx);

        verify(rosterStore, times(2)).getActiveRoster();
        verify(rosterStore).getCandidateRoster();
        verify(rosterStore).adoptCandidateRoster(ROUND_NO + 1L);
    }

    @Test
    void restartSetsActiveRosterFromOverrideIfPresent() {
        given(ctx.appConfig()).willReturn(DEFAULT_CONFIG);
        given(ctx.startupNetworks()).willReturn(startupNetworks);
        given(ctx.roundNumber()).willReturn(ROUND_NO);
        given(ctx.newStates()).willReturn(writableStates);
        given(rosterStoreFactory.apply(writableStates)).willReturn(rosterStore);
        given(ctx.platformConfig()).willReturn(DEFAULT_CONFIG);
        given(startupNetworks.overrideNetworkFor(ROUND_NO, DEFAULT_CONFIG)).willReturn(Optional.of(NETWORK));
        given(rosterStore.getActiveRoster()).willReturn(ROSTER);

        subject.restart(ctx);

        verify(rosterStore).putActiveRoster(ROSTER, ROUND_NO + 1L);
        verify(startupNetworks).setOverrideRound(ROUND_NO);
        verify(onAdopt).accept(ROSTER, ROSTER);
    }

    @Test
    void restartSetsActiveRosterFromOverrideWithPreservedWeightsIfPresent() {
        given(ctx.appConfig()).willReturn(DEFAULT_CONFIG);
        given(ctx.startupNetworks()).willReturn(startupNetworks);
        given(ctx.roundNumber()).willReturn(ROUND_NO);
        given(ctx.newStates()).willReturn(writableStates);
        given(ctx.platformConfig()).willReturn(DEFAULT_CONFIG);
        given(rosterStoreFactory.apply(writableStates)).willReturn(rosterStore);
        given(rosterStore.getActiveRoster())
                .willReturn(new Roster(
                        List.of(RosterEntry.newBuilder().nodeId(1L).weight(42L).build())));
        given(startupNetworks.overrideNetworkFor(ROUND_NO, DEFAULT_CONFIG)).willReturn(Optional.of(NETWORK));
        final var adaptedRoster = new Roster(
                List.of(RosterEntry.newBuilder().nodeId(1L).weight(42L).build()));
        given(rosterStore.getActiveRoster()).willReturn(adaptedRoster);

        subject.restart(ctx);

        verify(rosterStore).putActiveRoster(adaptedRoster, ROUND_NO + 1L);
        verify(startupNetworks).setOverrideRound(ROUND_NO);
    }
}
