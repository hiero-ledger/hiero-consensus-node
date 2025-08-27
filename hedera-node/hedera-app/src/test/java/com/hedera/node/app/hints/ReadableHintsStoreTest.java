// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints;

import static com.hedera.hapi.util.HapiUtils.asTimestamp;
import static com.hedera.node.app.hints.schemas.V059HintsSchema.ACTIVE_HINT_CONSTRUCTION_KEY;
import static com.hedera.node.app.hints.schemas.V059HintsSchema.HINTS_KEY_SETS_KEY;
import static com.hedera.node.app.hints.schemas.V059HintsSchema.NEXT_HINT_CONSTRUCTION_KEY;
import static com.hedera.node.app.hints.schemas.V059HintsSchema.PREPROCESSING_VOTES_KEY;
import static com.hedera.node.app.hints.schemas.V060HintsSchema.CRS_PUBLICATIONS_KEY;
import static com.hedera.node.app.hints.schemas.V060HintsSchema.CRS_STATE_KEY;
import static com.hedera.node.app.ids.schemas.V0490EntityIdSchema.ENTITY_ID_STATE_KEY;
import static com.hedera.node.app.ids.schemas.V0590EntityIdSchema.ENTITY_COUNTS_KEY;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doCallRealMethod;

import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.hapi.node.state.entity.EntityCounts;
import com.hedera.hapi.node.state.hints.CRSStage;
import com.hedera.hapi.node.state.hints.CRSState;
import com.hedera.hapi.node.state.hints.HintsConstruction;
import com.hedera.hapi.node.state.hints.HintsKeySet;
import com.hedera.hapi.node.state.hints.HintsPartyId;
import com.hedera.hapi.node.state.hints.HintsScheme;
import com.hedera.hapi.node.state.hints.PreprocessingVote;
import com.hedera.hapi.node.state.hints.PreprocessingVoteId;
import com.hedera.hapi.platform.state.NodeId;
import com.hedera.hapi.services.auxiliary.hints.CrsPublicationTransactionBody;
import com.hedera.node.app.hints.impl.ReadableHintsStoreImpl;
import com.hedera.node.app.ids.EntityIdService;
import com.hedera.node.app.ids.WritableEntityIdStore;
import com.hedera.node.app.spi.ids.ReadableEntityCounters;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.spi.ReadableStates;
import com.swirlds.state.test.fixtures.FunctionReadableSingletonState;
import com.swirlds.state.test.fixtures.FunctionWritableSingletonState;
import com.swirlds.state.test.fixtures.MapReadableKVState;
import com.swirlds.state.test.fixtures.MapWritableStates;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReadableHintsStoreTest {
    @Mock
    private ReadableHintsStore subject;

    @Mock
    private ReadableStates readableStates;

    private ReadableEntityCounters entityCounters;

    @BeforeEach
    void setUp() {
        entityCounters = new WritableEntityIdStore(new MapWritableStates(Map.of(
                ENTITY_ID_STATE_KEY,
                new FunctionWritableSingletonState<>(
                        EntityIdService.NAME,
                        ENTITY_ID_STATE_KEY,
                        () -> EntityNumber.newBuilder().build(),
                        c -> {}),
                ENTITY_COUNTS_KEY,
                new FunctionWritableSingletonState<>(
                        EntityIdService.NAME,
                        ENTITY_COUNTS_KEY,
                        () -> EntityCounts.newBuilder().numNodes(2).build(),
                        c -> {}))));
    }

    @Test
    void onlyReadyToAdoptIfNextConstructionIsCompleteAndMatching() {
        final var rosterHash = Bytes.wrap("RH");
        doCallRealMethod().when(subject).isReadyToAdopt(rosterHash);

        given(subject.getNextConstruction()).willReturn(HintsConstruction.DEFAULT);
        assertFalse(subject.isReadyToAdopt(rosterHash));

        given(subject.getNextConstruction())
                .willReturn(HintsConstruction.newBuilder()
                        .targetRosterHash(rosterHash)
                        .build());
        assertFalse(subject.isReadyToAdopt(rosterHash));

        given(subject.getNextConstruction())
                .willReturn(HintsConstruction.newBuilder()
                        .targetRosterHash(rosterHash)
                        .hintsScheme(HintsScheme.DEFAULT)
                        .build());
        assertTrue(subject.isReadyToAdopt(rosterHash));
    }

    @Test
    void returnsCrsState() {
        final var crsState = CRSState.newBuilder()
                .crs(Bytes.wrap("test"))
                .nextContributingNodeId(0L)
                .stage(CRSStage.GATHERING_CONTRIBUTIONS)
                .contributionEndTime(asTimestamp(Instant.ofEpochSecond(1_234_567L)))
                .build();
        given(readableStates.getSingleton(CRS_STATE_KEY))
                .willReturn(new FunctionReadableSingletonState<>(HintsService.NAME, CRS_STATE_KEY, () -> crsState));
        given(readableStates.getSingleton(NEXT_HINT_CONSTRUCTION_KEY))
                .willReturn(new FunctionReadableSingletonState<>(
                        HintsService.NAME, NEXT_HINT_CONSTRUCTION_KEY, () -> HintsConstruction.DEFAULT));
        given(readableStates.getSingleton(ACTIVE_HINT_CONSTRUCTION_KEY))
                .willReturn(new FunctionReadableSingletonState<>(
                        HintsService.NAME, ACTIVE_HINT_CONSTRUCTION_KEY, () -> HintsConstruction.DEFAULT));
        subject = new ReadableHintsStoreImpl(readableStates, entityCounters);

        assertEquals(crsState, subject.getCrsState());
    }

    @Test
    void returnsAllPublications() {
        final var publication = CrsPublicationTransactionBody.newBuilder()
                .newCrs(Bytes.wrap("pub1"))
                .proof(Bytes.wrap("proof"))
                .build();
        final var state = MapReadableKVState.<NodeId, CrsPublicationTransactionBody>builder(
                        HintsService.NAME, CRS_PUBLICATIONS_KEY)
                .value(NodeId.DEFAULT, publication)
                .value(NodeId.DEFAULT, publication)
                .build();
        given(readableStates.<NodeId, CrsPublicationTransactionBody>get(CRS_PUBLICATIONS_KEY))
                .willReturn(state);
        given(readableStates.<HintsPartyId, HintsKeySet>get(HINTS_KEY_SETS_KEY))
                .willReturn(MapReadableKVState.<HintsPartyId, HintsKeySet>builder(HintsService.NAME, HINTS_KEY_SETS_KEY)
                        .build());
        given(readableStates.<PreprocessingVoteId, PreprocessingVote>get(PREPROCESSING_VOTES_KEY))
                .willReturn(MapReadableKVState.<PreprocessingVoteId, PreprocessingVote>builder(
                                HintsService.NAME, PREPROCESSING_VOTES_KEY)
                        .build());

        subject = new ReadableHintsStoreImpl(readableStates, entityCounters);

        assertEquals(List.of(publication), subject.getCrsPublications());
    }
}
