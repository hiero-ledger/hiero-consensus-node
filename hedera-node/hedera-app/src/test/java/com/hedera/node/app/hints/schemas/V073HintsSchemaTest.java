// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints.schemas;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.hedera.hapi.node.state.hints.CRSStage;
import com.hedera.hapi.node.state.hints.CRSState;
import com.hedera.hapi.node.state.hints.HintsConstruction;
import com.hedera.hapi.node.state.hints.HintsScheme;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.hapi.node.state.roster.RosterState;
import com.hedera.hapi.node.state.roster.RoundRosterPair;
import com.hedera.node.app.hints.HintsLibrary;
import com.hedera.node.app.hints.impl.HintsContext;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.spi.WritableKVState;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.spi.WritableStates;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class V073HintsSchemaTest {
    @Mock
    private HintsLibrary library;

    @Mock
    private HintsContext signingContext;

    @Mock
    private MigrationContext ctx;

    @Mock
    private WritableStates writableStates;

    @Mock
    private WritableSingletonState<HintsConstruction> activeConstructionState;

    @Mock
    private WritableSingletonState<HintsConstruction> nextConstructionState;

    @Mock
    private WritableSingletonState<CRSState> crsState;

    @Mock
    private WritableSingletonState<RosterState> rosterState;

    @Mock
    private WritableKVState<ProtoBytes, Roster> rosters;

    @Test
    void restartInitializesMissingSingletonsWhenHintsEnabled() {
        final var subject = new V073HintsSchema(library, signingContext);
        final var newCrs = Bytes.wrap(new byte[] {1, 2, 3});
        final var activeRosterHash = Bytes.wrap("active-roster-hash".getBytes());
        given(ctx.isGenesis()).willReturn(false);
        given(ctx.appConfig())
                .willReturn(HederaTestConfigBuilder.create()
                        .withValue("tss.hintsEnabled", "true")
                        .getOrCreateConfig());
        given(ctx.newStates()).willReturn(writableStates);
        given(writableStates.<HintsConstruction>getSingleton(V059HintsSchema.ACTIVE_HINTS_CONSTRUCTION_STATE_ID))
                .willReturn(activeConstructionState);
        given(activeConstructionState.get()).willReturn(null);
        given(writableStates.<HintsConstruction>getSingleton(V059HintsSchema.NEXT_HINTS_CONSTRUCTION_STATE_ID))
                .willReturn(nextConstructionState);
        given(nextConstructionState.get()).willReturn(null);
        given(writableStates.<CRSState>getSingleton(V060HintsSchema.CRS_STATE_STATE_ID))
                .willReturn(crsState);
        given(crsState.get()).willReturn(null);
        given(writableStates.<RosterState>getSingleton(org.hiero.consensus.roster.RosterStateId.ROSTER_STATE_STATE_ID))
                .willReturn(rosterState);
        given(writableStates.<ProtoBytes, Roster>get(org.hiero.consensus.roster.RosterStateId.ROSTERS_STATE_ID))
                .willReturn(rosters);
        given(rosterState.get())
                .willReturn(RosterState.newBuilder()
                        .roundRosterPairs(List.of(new RoundRosterPair(123L, activeRosterHash)))
                        .build());
        given(rosters.get(new ProtoBytes(activeRosterHash)))
                .willReturn(Roster.newBuilder()
                        .rosterEntries(List.of(
                                RosterEntry.DEFAULT,
                                RosterEntry.DEFAULT,
                                RosterEntry.DEFAULT,
                                RosterEntry.DEFAULT,
                                RosterEntry.DEFAULT))
                        .build());
        given(library.newCrs((short) 8)).willReturn(newCrs);

        subject.restart(ctx);

        verify(activeConstructionState).put(HintsConstruction.DEFAULT);
        verify(nextConstructionState).put(HintsConstruction.DEFAULT);
        verify(crsState)
                .put(CRSState.newBuilder()
                        .stage(CRSStage.GATHERING_CONTRIBUTIONS)
                        .nextContributingNodeId(0L)
                        .crs(newCrs)
                        .build());
        verify(signingContext, never()).setConstruction(HintsConstruction.DEFAULT);
    }

    @Test
    void restartInitializesSigningContextFromExistingActiveConstruction() {
        final var subject = new V073HintsSchema(library, signingContext);
        final var activeConstruction = HintsConstruction.newBuilder()
                .constructionId(1L)
                .hintsScheme(HintsScheme.DEFAULT)
                .build();
        given(ctx.isGenesis()).willReturn(false);
        given(ctx.appConfig())
                .willReturn(HederaTestConfigBuilder.create()
                        .withValue("tss.hintsEnabled", "true")
                        .getOrCreateConfig());
        given(ctx.newStates()).willReturn(writableStates);
        given(writableStates.<HintsConstruction>getSingleton(V059HintsSchema.ACTIVE_HINTS_CONSTRUCTION_STATE_ID))
                .willReturn(activeConstructionState);
        given(activeConstructionState.get()).willReturn(activeConstruction);
        given(writableStates.<HintsConstruction>getSingleton(V059HintsSchema.NEXT_HINTS_CONSTRUCTION_STATE_ID))
                .willReturn(nextConstructionState);
        given(nextConstructionState.get()).willReturn(HintsConstruction.DEFAULT);
        given(writableStates.<CRSState>getSingleton(V060HintsSchema.CRS_STATE_STATE_ID))
                .willReturn(crsState);
        given(crsState.get()).willReturn(CRSState.DEFAULT);

        subject.restart(ctx);

        verify(signingContext).setConstruction(activeConstruction);
    }

    @Test
    void restartSkipsInitializationForGenesisOrDisabledHints() {
        final var subject = new V073HintsSchema(library, signingContext);
        given(ctx.isGenesis()).willReturn(true);

        subject.restart(ctx);

        verifyNoInteractions(
                writableStates,
                activeConstructionState,
                nextConstructionState,
                crsState,
                rosterState,
                rosters,
                library,
                signingContext);
    }
}
