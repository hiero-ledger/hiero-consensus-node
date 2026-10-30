// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints.schemas;

import static com.hedera.node.app.hints.schemas.V059HintsSchema.ACTIVE_HINTS_CONSTRUCTION_STATE_ID;
import static com.hedera.node.app.hints.schemas.V059HintsSchema.HINTS_KEY_SETS_STATE_ID;
import static com.hedera.node.app.hints.schemas.V059HintsSchema.NEXT_HINTS_CONSTRUCTION_STATE_ID;
import static com.hedera.node.app.hints.schemas.V060HintsSchema.CRS_STATE_STATE_ID;
import static com.hedera.node.app.hints.schemas.V079HintsSchema.NEXT_CRS_STATE_ID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.hedera.hapi.node.state.hints.CRSStage;
import com.hedera.hapi.node.state.hints.CRSState;
import com.hedera.hapi.node.state.hints.HintsConstruction;
import com.hedera.hapi.node.state.hints.HintsKeySet;
import com.hedera.hapi.node.state.hints.HintsPartyId;
import com.hedera.hapi.node.state.hints.HintsScheme;
import com.hedera.hapi.node.state.hints.NodePartyId;
import com.hedera.hapi.node.state.hints.PreprocessedKeys;
import com.hedera.node.app.hints.HintsLibrary;
import com.hedera.node.app.hints.impl.HintsContext;
import com.hedera.node.app.hints.impl.HintsLibraryImpl;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.node.internal.network.Network;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.test.fixtures.FunctionWritableSingletonState;
import com.swirlds.state.test.fixtures.MapWritableKVState;
import com.swirlds.state.test.fixtures.MapWritableStates;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class V079HintsSchemaTest {
    private static final Bytes CRS = Bytes.wrap("serialized-crs");

    @Mock
    private HintsLibrary library;

    @Mock
    private HintsContext signingContext;

    @Mock
    private MigrationContext ctx;

    private final WritableSingletonState<CRSState> activeCrs = singleton(CRS_STATE_STATE_ID, CRSState.DEFAULT);
    private final WritableSingletonState<CRSState> nextCrs = singleton(NEXT_CRS_STATE_ID, null);
    private final WritableSingletonState<HintsConstruction> active =
            singleton(ACTIVE_HINTS_CONSTRUCTION_STATE_ID, HintsConstruction.DEFAULT);
    private final WritableSingletonState<HintsConstruction> next =
            singleton(NEXT_HINTS_CONSTRUCTION_STATE_ID, HintsConstruction.DEFAULT);
    private final MapWritableKVState<HintsPartyId, HintsKeySet> keys =
            new MapWritableKVState<>(HINTS_KEY_SETS_STATE_ID, "HintsService.HINTS_KEY_SETS");
    private V079HintsSchema subject;

    @BeforeEach
    void setUp() {
        subject = new V079HintsSchema(library, signingContext);
        lenient().when(ctx.appConfig()).thenReturn(HederaTestConfigBuilder.createConfig());
        lenient()
                .when(ctx.newStates())
                .thenReturn(new MapWritableStates(Map.of(
                        CRS_STATE_STATE_ID, activeCrs,
                        NEXT_CRS_STATE_ID, nextCrs,
                        ACTIVE_HINTS_CONSTRUCTION_STATE_ID, active,
                        NEXT_HINTS_CONSTRUCTION_STATE_ID, next,
                        HINTS_KEY_SETS_STATE_ID, keys)));
    }

    @Test
    void genesisLeavesSingletonsUnmodifiedUntilConsensusSetup() {
        given(ctx.isGenesis()).willReturn(true);
        subject.restart(ctx);
        assertNull(nextCrs.get());
        assertFalse(nextCrs.isModified());
        assertFalse(activeCrs.isModified());
        assertFalse(active.isModified());
        assertFalse(next.isModified());
        verifyNoInteractions(library, signingContext);
    }

    @Test
    void emptyNonGenesisStateStaysEmptyAcrossRestarts() {
        subject.restart(ctx);
        subject.restart(ctx);
        assertEquals(HintsConstruction.DEFAULT, active.get());
        assertEquals(HintsConstruction.DEFAULT, next.get());
        assertEquals(CRSState.DEFAULT, activeCrs.get());
        assertEquals(CRSState.DEFAULT, nextCrs.get());
        verifyNoInteractions(library, signingContext);
    }

    @Test
    void realPreNovaStateIsRejectedWithoutReinterpretingItsCrypto() throws Exception {
        final Network fixture;
        try (var input = getClass().getResourceAsStream("/tss/pre-nova-public-state.json")) {
            fixture = Network.JSON.parse(BufferedData.wrap(input.readAllBytes()));
        }
        final var metadata = fixture.tssMetadataOrThrow();
        activeCrs.put(metadata.crsStateOrThrow());
        active.put(metadata.activeHintsConstructionOrThrow());
        subject = new V079HintsSchema(new HintsLibraryImpl(), signingContext);
        final var failure = assertThrows(IllegalStateException.class, () -> subject.restart(ctx));
        assertTrue(failure.getMessage().contains("coordinated hinTS/history reset"));
        assertEquals(metadata.crsStateOrThrow(), activeCrs.get());
        assertEquals(metadata.activeHintsConstructionOrThrow(), active.get());
        verifyNoInteractions(signingContext);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsPopulatedLegacyStateEvenWithTssDisabled(final boolean completed) {
        givenLegacyScheme(4);
        if (!completed) {
            activeCrs.put(activeCrs
                    .get()
                    .copyBuilder()
                    .stage(CRSStage.GATHERING_CONTRIBUTIONS)
                    .build());
            active.put(HintsConstruction.newBuilder().constructionId(7).build());
        }
        lenient()
                .when(ctx.appConfig())
                .thenReturn(HederaTestConfigBuilder.create()
                        .withValue("tss.hintsEnabled", false)
                        .getOrCreateConfig());
        final var originalCrs = activeCrs.get();
        final var originalConstruction = active.get();
        final var failure = assertThrows(IllegalStateException.class, () -> subject.restart(ctx));
        assertTrue(failure.getMessage().contains("coordinated hinTS/history reset"));
        assertEquals(originalCrs, activeCrs.get());
        assertEquals(originalConstruction, active.get());
        assertNotNull(keys.get(new HintsPartyId(0, 4, 0)));
        verifyNoInteractions(library, signingContext);
    }

    @Test
    void restartPreservesUnboundModernConstructionWithoutAllocatingAnotherId() {
        activeCrs.put(CRSState.newBuilder().lastUsedConstructionId(7).build());
        final var construction = HintsConstruction.newBuilder()
                .constructionId(7)
                .sourceRosterHash(Bytes.wrap("source"))
                .targetRosterHash(Bytes.wrap("target"))
                .build();
        active.put(construction);
        subject.restart(ctx);
        subject.restart(ctx);
        assertEquals(construction, active.get());
        assertEquals(7, activeCrs.get().lastUsedConstructionId());
        verifyNoInteractions(library, signingContext);
    }

    @Test
    void restartRestoresSigningOnlyWithTheBoundActiveCrs() {
        givenBoundActiveScheme(4);
        given(library.crsPartySize(CRS)).willReturn(4);
        subject.restart(ctx);
        lenient()
                .when(ctx.appConfig())
                .thenReturn(HederaTestConfigBuilder.create()
                        .withValue("tss.hintsEnabled", "true")
                        .getOrCreateConfig());
        subject.restart(ctx);
        verify(signingContext, times(2)).setConstruction(active.get(), activeCrs.get());
    }

    @Test
    void sameVersionRestartRejectsOversizedActiveCrs() {
        givenBoundActiveScheme(128);
        givenRestartConfiguration();

        assertThrows(IllegalStateException.class, () -> subject.restart(ctx));

        assertEquals(128, activeCrs.get().numParties());
        verifyNoInteractions(library, signingContext);
    }

    @Test
    void sameVersionRestartRejectsOversizedNextCrsBeforeRestoringSigning() {
        givenBoundActiveScheme(64);
        next.put(HintsConstruction.newBuilder()
                .constructionId(8)
                .crsId(2)
                .numParties(128)
                .build());
        nextCrs.put(CRSState.newBuilder()
                .ceremonyId(2)
                .constructionId(8)
                .numParties(128)
                .stage(CRSStage.GATHERING_CONTRIBUTIONS)
                .crs(Bytes.wrap("oversized-next-crs"))
                .build());
        lenient().when(library.crsPartySize(CRS)).thenReturn(64);
        givenRestartConfiguration();

        assertThrows(IllegalStateException.class, () -> subject.restart(ctx));

        assertEquals(64, activeCrs.get().numParties());
        assertEquals(128, nextCrs.get().numParties());
        verifyNoInteractions(signingContext);
    }

    @Test
    void sameVersionRestartRestoresMaximumSupportedCapacityAndLastAssignableParty() {
        givenBoundActiveScheme(64);
        given(library.crsPartySize(CRS)).willReturn(64);
        givenRestartConfiguration();

        subject.restart(ctx);

        verify(signingContext).setConstruction(active.get(), activeCrs.get());
        assertEquals(64, activeCrs.get().numParties());
        assertEquals(
                62, active.get().hintsSchemeOrThrow().nodePartyIds().getFirst().partyId());
    }

    private void givenBoundActiveScheme(final int capacity) {
        activeCrs.put(CRSState.newBuilder()
                .ceremonyId(1)
                .numParties(capacity)
                .crs(CRS)
                .stage(CRSStage.COMPLETED)
                .build());
        active.put(HintsConstruction.newBuilder()
                .constructionId(7)
                .crsId(1)
                .numParties(capacity)
                .hintsScheme(new HintsScheme(PreprocessedKeys.DEFAULT, List.of(new NodePartyId(1, capacity - 2, 1))))
                .build());
        nextCrs.put(CRSState.DEFAULT);
    }

    private void givenRestartConfiguration() {
        lenient()
                .when(ctx.appConfig())
                .thenReturn(HederaTestConfigBuilder.create()
                        .withValue("tss.hintsEnabled", "true")
                        .getOrCreateConfig());
    }

    private void givenLegacyScheme(final int capacity) {
        activeCrs.put(CRSState.newBuilder().crs(CRS).stage(CRSStage.COMPLETED).build());
        active.put(HintsConstruction.newBuilder()
                .constructionId(7)
                .hintsScheme(new HintsScheme(PreprocessedKeys.DEFAULT, List.of(new NodePartyId(1, 0, 1))))
                .build());
        keys.put(
                new HintsPartyId(0, capacity, 0),
                HintsKeySet.newBuilder().nodeId(1).key(Bytes.wrap("key")).build());
    }

    private static <T> WritableSingletonState<T> singleton(final int id, final T value) {
        final var ref = new AtomicReference<>(value);
        return new FunctionWritableSingletonState<>(id, "HintsService." + id, ref::get, ref::set);
    }
}
