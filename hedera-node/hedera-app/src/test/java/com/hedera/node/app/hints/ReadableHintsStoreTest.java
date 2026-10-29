// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints;

import static com.hedera.hapi.util.HapiUtils.asTimestamp;
import static com.hedera.node.app.hints.schemas.V059HintsSchema.ACTIVE_HINTS_CONSTRUCTION_STATE_ID;
import static com.hedera.node.app.hints.schemas.V059HintsSchema.ACTIVE_HINTS_CONSTRUCTION_STATE_LABEL;
import static com.hedera.node.app.hints.schemas.V059HintsSchema.NEXT_HINTS_CONSTRUCTION_STATE_ID;
import static com.hedera.node.app.hints.schemas.V059HintsSchema.NEXT_HINTS_CONSTRUCTION_STATE_LABEL;
import static com.hedera.node.app.hints.schemas.V060HintsSchema.CRS_STATE_STATE_ID;
import static com.hedera.node.app.hints.schemas.V060HintsSchema.CRS_STATE_STATE_LABEL;
import static com.hedera.node.app.service.entityid.impl.schemas.V0490EntityIdSchema.ENTITY_ID_STATE_ID;
import static com.hedera.node.app.service.entityid.impl.schemas.V0490EntityIdSchema.ENTITY_ID_STATE_LABEL;
import static com.hedera.node.app.service.entityid.impl.schemas.V0590EntityIdSchema.ENTITY_COUNTS_STATE_ID;
import static com.hedera.node.app.service.entityid.impl.schemas.V0590EntityIdSchema.ENTITY_COUNTS_STATE_LABEL;
import static com.hedera.node.app.service.entityid.impl.schemas.V0730EntityIdSchema.HIGHEST_NODE_ID_STATE_ID;
import static com.hedera.node.app.service.entityid.impl.schemas.V0730EntityIdSchema.HIGHEST_NODE_ID_STATE_LABEL;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doCallRealMethod;

import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.hapi.node.state.entity.EntityCounts;
import com.hedera.hapi.node.state.hints.CRSStage;
import com.hedera.hapi.node.state.hints.CRSState;
import com.hedera.hapi.node.state.hints.HintsConstruction;
import com.hedera.hapi.node.state.hints.HintsScheme;
import com.hedera.hapi.platform.state.NodeId;
import com.hedera.node.app.hints.impl.ReadableHintsStoreImpl;
import com.hedera.node.app.service.entityid.ReadableEntityIdStore;
import com.hedera.node.app.service.entityid.impl.WritableEntityIdStoreImpl;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.spi.ReadableStates;
import com.swirlds.state.test.fixtures.FunctionReadableSingletonState;
import com.swirlds.state.test.fixtures.FunctionWritableSingletonState;
import com.swirlds.state.test.fixtures.MapWritableStates;
import java.time.Instant;
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

    private ReadableEntityIdStore readableEntityIdStore;

    @BeforeEach
    void setUp() {
        readableEntityIdStore = new WritableEntityIdStoreImpl(new MapWritableStates(Map.of(
                ENTITY_ID_STATE_ID,
                new FunctionWritableSingletonState<>(
                        ENTITY_ID_STATE_ID,
                        ENTITY_ID_STATE_LABEL,
                        () -> EntityNumber.newBuilder().build(),
                        c -> {}),
                ENTITY_COUNTS_STATE_ID,
                new FunctionWritableSingletonState<>(
                        ENTITY_COUNTS_STATE_ID,
                        ENTITY_COUNTS_STATE_LABEL,
                        () -> EntityCounts.newBuilder().numNodes(2).build(),
                        c -> {}),
                HIGHEST_NODE_ID_STATE_ID,
                new FunctionWritableSingletonState<>(
                        HIGHEST_NODE_ID_STATE_ID, HIGHEST_NODE_ID_STATE_LABEL, () -> NodeId.DEFAULT, c -> {}))));
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

        final var ready = HintsConstruction.newBuilder()
                .targetRosterHash(rosterHash)
                .hintsScheme(HintsScheme.DEFAULT)
                .crsId(1)
                .numParties(4)
                .build();
        given(subject.getNextConstruction()).willReturn(ready);
        given(subject.getCrsStateFor(ready))
                .willReturn(CRSState.newBuilder()
                        .ceremonyId(1)
                        .numParties(4)
                        .stage(CRSStage.COMPLETED)
                        .crs(Bytes.wrap("crs"))
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
        given(readableStates.getSingleton(CRS_STATE_STATE_ID))
                .willReturn(new FunctionReadableSingletonState<>(
                        CRS_STATE_STATE_ID, CRS_STATE_STATE_LABEL, () -> crsState));
        given(readableStates.getSingleton(NEXT_HINTS_CONSTRUCTION_STATE_ID))
                .willReturn(new FunctionReadableSingletonState<>(
                        NEXT_HINTS_CONSTRUCTION_STATE_ID,
                        NEXT_HINTS_CONSTRUCTION_STATE_LABEL,
                        () -> HintsConstruction.DEFAULT));
        given(readableStates.getSingleton(ACTIVE_HINTS_CONSTRUCTION_STATE_ID))
                .willReturn(new FunctionReadableSingletonState<>(
                        ACTIVE_HINTS_CONSTRUCTION_STATE_ID,
                        ACTIVE_HINTS_CONSTRUCTION_STATE_LABEL,
                        () -> HintsConstruction.DEFAULT));
        subject = new ReadableHintsStoreImpl(readableStates, readableEntityIdStore);

        assertEquals(crsState, subject.getCrsState());
    }
}
