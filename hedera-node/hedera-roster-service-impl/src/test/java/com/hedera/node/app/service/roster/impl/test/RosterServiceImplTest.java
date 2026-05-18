// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.roster.impl.test;

import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.node.app.service.roster.RosterService;
import com.hedera.node.app.service.roster.RosterTransplantSchema;
import com.hedera.node.app.service.roster.impl.RosterServiceImpl;
import com.hedera.node.app.service.roster.impl.RosterServiceImpl.RosterAdoption;
import com.hedera.node.app.service.roster.impl.schemas.V0540RosterSchema;
import com.hedera.node.app.spi.migrate.StartupNetworks;
import com.swirlds.state.lifecycle.PostUpgradeContext;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.SchemaRegistry;
import com.swirlds.state.spi.WritableStates;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;
import org.assertj.core.api.Assertions;
import org.hiero.consensus.roster.WritableRosterStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RosterServiceImplTest {
    private static final long ROUND_NUMBER = 666L;
    private static final Roster ROSTER =
            new Roster(List.of(RosterEntry.newBuilder().nodeId(1L).weight(1L).build()));

    @Mock
    private Predicate<Roster> canAdopt;

    @Mock
    private BiPredicate<Roster, PostUpgradeContext> postUpgradeCanAdopt;

    @Mock
    private RosterAdoption postUpgradeOnAdopt;

    @Mock
    private BiConsumer<Roster, Roster> onAdopt;

    @Mock
    private StartupNetworks startupNetworks;

    @Mock
    private Function<WritableStates, WritableRosterStore> rosterStoreFactory;

    @Mock
    private WritableStates writableStates;

    @Mock
    private WritableRosterStore rosterStore;

    @Mock
    private PostUpgradeContext postUpgradeContext;

    private RosterServiceImpl rosterService;

    @BeforeEach
    void setUp() {
        rosterService = new RosterServiceImpl(canAdopt, onAdopt, () -> startupNetworks);
    }

    @Test
    void registerSchemasNullArgsThrow() {
        Assertions.assertThatThrownBy(() -> rosterService.registerSchemas(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void registerExpectedSchemas() {
        final var schemaRegistry = Mockito.mock(SchemaRegistry.class);

        rosterService.registerSchemas(schemaRegistry);
        final var captor = ArgumentCaptor.forClass(Schema.class);
        Mockito.verify(schemaRegistry).register(captor.capture());
        final var schemas = captor.getAllValues();
        Assertions.assertThat(schemas).hasSize(1);
        Assertions.assertThat(schemas.getFirst()).isInstanceOf(V0540RosterSchema.class);
        Assertions.assertThat(schemas.getFirst()).isInstanceOf(RosterTransplantSchema.class);
    }

    @Test
    void testServiceNameReturnsCorrectName() {
        Assertions.assertThat(rosterService.getServiceName()).isEqualTo(RosterService.NAME);
    }

    @Test
    void postUpgradeSetupDoesNothingWithoutCandidateRoster() {
        rosterService = new RosterServiceImpl(
                postUpgradeCanAdopt, postUpgradeOnAdopt, onAdopt, () -> startupNetworks, rosterStoreFactory);
        given(rosterStoreFactory.apply(writableStates)).willReturn(rosterStore);

        Assertions.assertThat(rosterService.doPostUpgradeSetup(writableStates, postUpgradeContext))
                .isFalse();

        Mockito.verify(rosterStore).getCandidateRoster();
        Mockito.verifyNoInteractions(postUpgradeCanAdopt, postUpgradeOnAdopt);
    }

    @Test
    void postUpgradeSetupRejectsCandidateRosterIfTestFails() {
        rosterService = new RosterServiceImpl(
                postUpgradeCanAdopt, postUpgradeOnAdopt, onAdopt, () -> startupNetworks, rosterStoreFactory);
        given(rosterStoreFactory.apply(writableStates)).willReturn(rosterStore);
        given(rosterStore.getCandidateRoster()).willReturn(ROSTER);

        Assertions.assertThat(rosterService.doPostUpgradeSetup(writableStates, postUpgradeContext))
                .isFalse();

        Mockito.verify(postUpgradeCanAdopt).test(ROSTER, postUpgradeContext);
        Mockito.verifyNoInteractions(postUpgradeOnAdopt);
        Mockito.verify(rosterStore, Mockito.never()).adoptCandidateRoster(Mockito.anyLong());
    }

    @Test
    void postUpgradeSetupAdoptsCandidateRosterIfTestPasses() {
        rosterService = new RosterServiceImpl(
                postUpgradeCanAdopt, postUpgradeOnAdopt, onAdopt, () -> startupNetworks, rosterStoreFactory);
        given(rosterStoreFactory.apply(writableStates)).willReturn(rosterStore);
        given(rosterStore.getCandidateRoster()).willReturn(ROSTER);
        given(rosterStore.getActiveRoster()).willReturn(ROSTER);
        given(postUpgradeCanAdopt.test(ROSTER, postUpgradeContext)).willReturn(true);
        given(postUpgradeContext.roundNumber()).willReturn(ROUND_NUMBER);

        Assertions.assertThat(rosterService.doPostUpgradeSetup(writableStates, postUpgradeContext))
                .isTrue();

        Mockito.verify(postUpgradeOnAdopt).accept(ROSTER, ROSTER, postUpgradeContext);
        Mockito.verify(rosterStore).adoptCandidateRoster(ROUND_NUMBER);
    }
}
