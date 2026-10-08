// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints.schemas;

import static com.hedera.node.app.hints.schemas.V059HintsSchema.ACTIVE_HINTS_CONSTRUCTION_STATE_ID;
import static com.hedera.node.app.hints.schemas.V059HintsSchema.HINTS_KEY_SETS_STATE_ID;
import static com.hedera.node.app.hints.schemas.V059HintsSchema.NEXT_HINTS_CONSTRUCTION_STATE_ID;
import static com.hedera.node.app.hints.schemas.V060HintsSchema.CRS_STATE_STATE_ID;
import static com.hedera.node.app.hints.schemas.V079HintsSchema.NEXT_CRS_STATE_ID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.BDDMockito.given;
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
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
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
        given(ctx.newStates())
                .willReturn(new MapWritableStates(Map.of(
                        CRS_STATE_STATE_ID, activeCrs,
                        NEXT_CRS_STATE_ID, nextCrs,
                        ACTIVE_HINTS_CONSTRUCTION_STATE_ID, active,
                        NEXT_HINTS_CONSTRUCTION_STATE_ID, next,
                        HINTS_KEY_SETS_STATE_ID, keys)));
    }

    @Test
    void genesisCreatesEmptyNextSlotWithoutInferringCryptoMetadata() {
        given(ctx.isGenesis()).willReturn(true);
        subject.migrate(ctx);
        assertEquals(CRSState.DEFAULT, nextCrs.get());
        verifyNoInteractions(library, signingContext);
    }

    @Test
    void preservesLegacyCompletedCryptoAndNamespacesItsKeys() {
        givenLegacyScheme(4);
        given(library.crsPartySize(CRS)).willReturn(4);
        next.put(HintsConstruction.newBuilder()
                .constructionId(8)
                .sourceRosterHash(Bytes.wrap("source"))
                .targetRosterHash(Bytes.wrap("candidate"))
                .hintsScheme(HintsScheme.DEFAULT)
                .build());
        final var legacyScheme = active.get().hintsScheme();
        subject.migrate(ctx);
        assertEquals(CRS, activeCrs.get().crs());
        assertEquals(4, activeCrs.get().numParties());
        assertEquals(1, activeCrs.get().ceremonyId());
        assertEquals(1, activeCrs.get().lastUsedCeremonyId());
        assertEquals(legacyScheme, active.get().hintsScheme());
        assertEquals(7, active.get().constructionId());
        assertEquals(1, active.get().crsId());
        assertEquals(4, active.get().numParties());
        assertNotNull(keys.get(new HintsPartyId(0, 4, 1)));
        assertNull(keys.get(new HintsPartyId(0, 4, 0)));
        assertEquals(9, next.get().constructionId());
        assertFalse(next.get().hasHintsScheme());
        assertFalse(next.get().hasGracePeriodEndTime());
        assertEquals(Bytes.wrap("candidate"), next.get().targetRosterHash());
        assertEquals(CRSState.DEFAULT, nextCrs.get());
    }

    @Test
    void refusesToReinterpretLegacySchemeAtADifferentCapacity() {
        givenLegacyScheme(4);
        given(library.crsPartySize(CRS)).willReturn(8);
        assertThrows(IllegalStateException.class, () -> subject.migrate(ctx));
        assertEquals(0, activeCrs.get().ceremonyId());
        assertEquals(0, active.get().crsId());
        assertNotNull(keys.get(new HintsPartyId(0, 4, 0)));
    }

    @Test
    void refusesAmbiguousLegacyCapacity() {
        givenLegacyScheme(4);
        given(library.crsPartySize(CRS)).willReturn(4);
        keys.put(
                new HintsPartyId(0, 8, 0),
                HintsKeySet.newBuilder().nodeId(1).key(Bytes.wrap("other")).build());
        assertThrows(IllegalStateException.class, () -> subject.migrate(ctx));
    }

    @Test
    void refusesMissingLegacyCapacityEvidence() {
        givenLegacyScheme(4);
        given(library.crsPartySize(CRS)).willReturn(4);
        keys.remove(new HintsPartyId(0, 4, 0));
        assertThrows(IllegalStateException.class, () -> subject.migrate(ctx));
    }

    @Test
    void incompleteLegacyCeremonyStartsCleanWithAFreshHintsConstructionId() {
        activeCrs.put(CRSState.newBuilder()
                .crs(CRS)
                .stage(CRSStage.WAITING_FOR_ADOPTING_FINAL_CRS)
                .build());
        active.put(HintsConstruction.newBuilder()
                .constructionId(7)
                .sourceRosterHash(Bytes.wrap("source"))
                .targetRosterHash(Bytes.wrap("target"))
                .preprocessingStartTime(com.hedera.hapi.node.base.Timestamp.DEFAULT)
                .build());
        keys.put(new HintsPartyId(0, 4, 0), HintsKeySet.DEFAULT);
        subject.migrate(ctx);
        assertEquals(CRSState.newBuilder().lastUsedConstructionId(8).build(), activeCrs.get());
        assertEquals(8, active.get().constructionId());
        assertEquals(Bytes.wrap("target"), active.get().targetRosterHash());
        assertFalse(active.get().hasPreprocessingStartTime());
        assertFalse(active.get().hasGracePeriodEndTime());
        assertEquals(0, active.get().crsId());
        assertNull(keys.get(new HintsPartyId(0, 4, 0)));
        verifyNoInteractions(library, signingContext);
    }

    @Test
    void restartRestoresSigningOnlyWithTheBoundActiveCrs() {
        givenLegacyScheme(4);
        given(library.crsPartySize(CRS)).willReturn(4);
        subject.migrate(ctx);
        given(ctx.appConfig())
                .willReturn(HederaTestConfigBuilder.create()
                        .withValue("tss.hintsEnabled", "true")
                        .getOrCreateConfig());
        subject.restart(ctx);
        verify(signingContext).setConstruction(active.get(), activeCrs.get());
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
