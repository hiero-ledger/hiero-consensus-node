// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle.steps;

import static com.hedera.hapi.node.freeze.FreezeType.FREEZE_ABORT;
import static com.hedera.hapi.node.freeze.FreezeType.FREEZE_UPGRADE;
import static com.hedera.hapi.node.freeze.FreezeType.PREPARE_UPGRADE;
import static com.hedera.hapi.node.freeze.FreezeType.TELEMETRY_UPGRADE;
import static com.hedera.hapi.node.freeze.FreezeType.UNKNOWN_FREEZE_TYPE;
import static com.hedera.node.app.fixtures.AppTestBase.DEFAULT_CONFIG;
import static com.hedera.node.app.ids.schemas.V0490EntityIdSchema.ENTITY_ID_STATE_ID;
import static com.hedera.node.app.ids.schemas.V0590EntityIdSchema.ENTITY_COUNTS_STATE_ID;
import static com.hedera.node.app.ids.schemas.V0590EntityIdSchema.ENTITY_COUNTS_STATE_LABEL;
import static com.hedera.node.app.service.addressbook.impl.schemas.V053AddressBookSchema.NODES_STATE_ID;
import static com.hedera.node.app.service.networkadmin.impl.schemas.V0490FreezeSchema.FREEZE_TIME_STATE_ID;
import static com.hedera.node.app.service.networkadmin.impl.schemas.V0490FreezeSchema.FREEZE_TIME_STATE_LABEL;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.STAKING_INFOS_STATE_ID;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.hiero.consensus.roster.RosterStateId.ROSTERS_STATE_ID;
import static org.hiero.consensus.roster.RosterStateId.ROSTER_STATE_STATE_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mock.Strictness.LENIENT;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.ServiceEndpoint;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.freeze.FreezeTransactionBody;
import com.hedera.hapi.node.state.addressbook.Node;
import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.hapi.node.state.entity.EntityCounts;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.hapi.node.state.roster.RosterState;
import com.hedera.hapi.node.state.roster.RoundRosterPair;
import com.hedera.hapi.node.state.token.StakingNodeInfo;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.platform.state.PlatformState;
import com.hedera.node.app.fixtures.state.FakeState;
import com.hedera.node.app.ids.EntityIdService;
import com.hedera.node.app.service.addressbook.AddressBookService;
import com.hedera.node.app.service.networkadmin.FreezeService;
import com.hedera.node.app.service.roster.RosterService;
import com.hedera.node.app.service.token.TokenService;
import com.hedera.node.app.spi.fixtures.TransactionFactory;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import com.swirlds.platform.state.service.PlatformStateService;
import com.swirlds.platform.state.service.schemas.V0540PlatformStateSchema;
import com.swirlds.state.spi.WritableStates;
import com.swirlds.state.test.fixtures.FunctionWritableSingletonState;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import org.assertj.core.api.Assertions;
import org.hiero.consensus.roster.WritableRosterStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class PlatformStateUpdatesTest implements TransactionFactory {

    public static final RosterState ROSTER_STATE = RosterState.newBuilder()
            .roundRosterPairs(List.of(RoundRosterPair.DEFAULT))
            .build();
    private FakeState state;

    private PlatformStateUpdates subject;
    private AtomicReference<Timestamp> freezeTimeBackingStore;
    private AtomicReference<PlatformState> platformStateBackingStore;
    private AtomicReference<RosterState> rosterStateBackingStore;
    private AtomicReference<EntityCounts> entityCountsBackingStore;
    private ConcurrentHashMap<ProtoBytes, Roster> rosters = new ConcurrentHashMap<>();
    private ConcurrentHashMap<EntityNumber, Node> nodes = new ConcurrentHashMap<>();
    private ConcurrentHashMap<EntityNumber, StakingNodeInfo> stakingInfo = new ConcurrentHashMap<>();

    @Mock(strictness = LENIENT)
    protected WritableStates writableStates;

    @Mock
    private BiConsumer<Roster, Path> rosterExportHelper;

    @BeforeEach
    void setUp() {
        freezeTimeBackingStore = new AtomicReference<>(null);
        platformStateBackingStore = new AtomicReference<>(V0540PlatformStateSchema.UNINITIALIZED_PLATFORM_STATE);
        rosterStateBackingStore = new AtomicReference<>(ROSTER_STATE);
        entityCountsBackingStore = new AtomicReference<>(
                EntityCounts.newBuilder().numNodes(3).numStakingInfos(3).build());

        when(writableStates.getSingleton(ENTITY_COUNTS_STATE_ID))
                .then(invocation -> new FunctionWritableSingletonState<>(
                        ENTITY_COUNTS_STATE_ID,
                        ENTITY_COUNTS_STATE_LABEL,
                        entityCountsBackingStore::get,
                        entityCountsBackingStore::set));

        when(writableStates.getSingleton(FREEZE_TIME_STATE_ID))
                .then(invocation -> new FunctionWritableSingletonState<>(
                        FREEZE_TIME_STATE_ID,
                        FREEZE_TIME_STATE_LABEL,
                        freezeTimeBackingStore::get,
                        freezeTimeBackingStore::set));

        state = new FakeState()
                .addService(FreezeService.NAME, Map.of(FREEZE_TIME_STATE_ID, freezeTimeBackingStore))
                .addService(
                        RosterService.NAME,
                        Map.of(
                                ROSTER_STATE_STATE_ID, rosterStateBackingStore,
                                ROSTERS_STATE_ID, rosters))
                .addService(AddressBookService.NAME, Map.of(NODES_STATE_ID, nodes))
                .addService(
                        PlatformStateService.NAME,
                        Map.of(V0540PlatformStateSchema.PLATFORM_STATE_STATE_ID, platformStateBackingStore))
                .addService(TokenService.NAME, Map.of(STAKING_INFOS_STATE_ID, stakingInfo))
                .addService(
                        EntityIdService.NAME,
                        Map.of(
                                ENTITY_ID_STATE_ID,
                                new AtomicReference<>(EntityNumber.newBuilder().build()),
                                ENTITY_COUNTS_STATE_ID,
                                entityCountsBackingStore));

        subject = new PlatformStateUpdates(rosterExportHelper);
    }

    @SuppressWarnings("ConstantConditions")
    @Test
    void testMethodsWithInvalidArguments() {
        // given
        final var txBody = simpleCryptoTransfer().body();

        // then
        assertThatThrownBy(() -> subject.handleTxBody(null, txBody, DEFAULT_CONFIG))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> subject.handleTxBody(state, txBody, DEFAULT_CONFIG))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> subject.handleTxBody(state, null, DEFAULT_CONFIG))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void testCryptoTransferShouldBeNoOp() {
        // given
        final var txBody = TransactionBody.newBuilder()
                .cryptoTransfer(CryptoTransferTransactionBody.DEFAULT)
                .build();

        // then
        Assertions.assertThatCode(() -> subject.handleTxBody(state, txBody, DEFAULT_CONFIG))
                .doesNotThrowAnyException();
    }

    @Test
    void freezeAbortNullsOutFreezeTime() {
        final var freezeTime = Timestamp.newBuilder().seconds(123L).nanos(456).build();
        freezeTimeBackingStore.set(freezeTime);
        final var txBody = TransactionBody.newBuilder()
                .freeze(FreezeTransactionBody.newBuilder().freezeType(FREEZE_ABORT));

        subject.handleTxBody(state, txBody.build(), DEFAULT_CONFIG);

        assertThat(platformStateBackingStore.get().freezeTime()).isNull();
    }

    @Test
    void unknownFreezeIsNoop() {
        platformStateBackingStore.set(
                PlatformState.newBuilder().freezeTime(Timestamp.DEFAULT).build());
        final var txBody = TransactionBody.newBuilder()
                .freeze(FreezeTransactionBody.newBuilder().freezeType(UNKNOWN_FREEZE_TYPE));

        subject.handleTxBody(state, txBody.build(), DEFAULT_CONFIG);

        assertThat(platformStateBackingStore.get().freezeTime()).isEqualTo(Timestamp.DEFAULT);
    }

    @Test
    void telemetryUpgradeIsNoop() {
        platformStateBackingStore.set(
                PlatformState.newBuilder().freezeTime(Timestamp.DEFAULT).build());
        final var txBody = TransactionBody.newBuilder()
                .freeze(FreezeTransactionBody.newBuilder().freezeType(TELEMETRY_UPGRADE));

        subject.handleTxBody(state, txBody.build(), DEFAULT_CONFIG);

        assertThat(platformStateBackingStore.get().freezeTime()).isEqualTo(Timestamp.DEFAULT);
    }

    @Test
    void testFreezeUpgradeWhenKeying() {
        // given
        final var freezeTime = Timestamp.newBuilder().seconds(123L).nanos(456).build();
        freezeTimeBackingStore.set(freezeTime);
        final var txBody = TransactionBody.newBuilder()
                .freeze(FreezeTransactionBody.newBuilder().freezeType(FREEZE_UPGRADE));

        // when
        subject.handleTxBody(state, txBody.build(), configWith(false, true));

        // then
        final var platformState = platformStateBackingStore.get();
        assertEquals(freezeTime.seconds(), platformState.freezeTimeOrThrow().seconds());
        assertEquals(freezeTime.nanos(), platformState.freezeTimeOrThrow().nanos());
    }

    @Test
    void testFreezeUpgradeWhenKeyingButNotUsingRosterLifecycle() {
        // given
        final var freezeTime = Timestamp.newBuilder().seconds(123L).nanos(456).build();
        freezeTimeBackingStore.set(freezeTime);
        final var txBody = TransactionBody.newBuilder()
                .freeze(FreezeTransactionBody.newBuilder().freezeType(FREEZE_UPGRADE));

        // when
        subject.handleTxBody(state, txBody.build(), configWith(false, true));

        // then
        final var platformState = platformStateBackingStore.get();
        assertEquals(freezeTime.seconds(), platformState.freezeTimeOrThrow().seconds());
        assertEquals(freezeTime.nanos(), platformState.freezeTimeOrThrow().nanos());
    }

    @Test
    void putsCandidateRosterWhenRequested() {
        // given
        final var freezeTime = Timestamp.newBuilder().seconds(123L).nanos(456).build();
        freezeTimeBackingStore.set(freezeTime);
        final var txBody = TransactionBody.newBuilder()
                .freeze(FreezeTransactionBody.newBuilder().freezeType(PREPARE_UPGRADE));
        nodes.put(
                new EntityNumber(0L),
                Node.newBuilder()
                        .weight(1)
                        .gossipCaCertificate(Bytes.fromHex("0123"))
                        .gossipEndpoint(new ServiceEndpoint(Bytes.EMPTY, 50211, "test.org"))
                        .build());
        stakingInfo.put(
                new EntityNumber(0L),
                StakingNodeInfo.newBuilder().stake(1000).weight(1).build());

        // when
        subject.handleTxBody(state, txBody.build(), configWith(true, true));

        // then
        final var captor = ArgumentCaptor.forClass(Path.class);
        verify(rosterExportHelper).accept(any(), captor.capture());
        final var path = captor.getValue();
        assertEquals("candidate-network.json", path.getFileName().toString());
    }

    @Test
    void usesActiveRosterWeightsIfCandidateHasZeroWeight() {
        final var freezeTime = Timestamp.newBuilder().seconds(123L).nanos(456).build();
        freezeTimeBackingStore.set(freezeTime);
        final var txBody = TransactionBody.newBuilder()
                .freeze(FreezeTransactionBody.newBuilder().freezeType(PREPARE_UPGRADE));
        final var fakeEndpoint = new ServiceEndpoint(Bytes.EMPTY, 50211, "test.org");
        nodes.put(
                new EntityNumber(0L),
                Node.newBuilder()
                        .weight(0)
                        .gossipCaCertificate(Bytes.fromHex("0123"))
                        .gossipEndpoint(fakeEndpoint)
                        .build());
        final var rosterStore = new WritableRosterStore(state.getWritableStates(RosterService.NAME));
        rosterStore.putActiveRoster(
                new Roster(List.of(new RosterEntry(0L, 1, Bytes.fromHex("0123"), List.of(fakeEndpoint)))), 123L);

        subject.handleTxBody(state, txBody.build(), configWith(true, true));

        verify(rosterExportHelper).accept(any(), any());
    }

    @Test
    void worksAroundInvalidCandidate() {
        final var freezeTime = Timestamp.newBuilder().seconds(123L).nanos(456).build();
        freezeTimeBackingStore.set(freezeTime);
        entityCountsBackingStore.set(EntityCounts.newBuilder().numNodes(1).build());
        final var txBody = TransactionBody.newBuilder()
                .freeze(FreezeTransactionBody.newBuilder().freezeType(PREPARE_UPGRADE));
        final var fakeEndpoint = new ServiceEndpoint(Bytes.EMPTY, 50211, "test.org");
        nodes.put(
                new EntityNumber(0L),
                Node.newBuilder()
                        .weight(0)
                        .gossipCaCertificate(Bytes.EMPTY)
                        .gossipEndpoint(fakeEndpoint)
                        .build());
        final var rosterStore = new WritableRosterStore(state.getWritableStates(RosterService.NAME));
        rosterStore.putActiveRoster(
                new Roster(List.of(new RosterEntry(0L, 1, Bytes.fromHex("0123"), List.of(fakeEndpoint)))), 123L);

        subject.handleTxBody(state, txBody.build(), configWith(true, true));

        verify(rosterExportHelper, never()).accept(any(), any());
    }

    @Test
    void updatesCandidateRosterWeightsWhenNotExportingAndRosterLifecycleEnabled() {
        final var freezeTime = Timestamp.newBuilder().seconds(123L).nanos(456).build();
        freezeTimeBackingStore.set(freezeTime);
        final var txBody = TransactionBody.newBuilder()
                .freeze(FreezeTransactionBody.newBuilder().freezeType(PREPARE_UPGRADE));
        nodes.put(
                new EntityNumber(0L),
                Node.newBuilder()
                        .nodeId(0L)
                        .weight(1)
                        .gossipCaCertificate(Bytes.fromHex("0123"))
                        .gossipEndpoint(new ServiceEndpoint(Bytes.EMPTY, 50211, "test.org"))
                        .build());
        nodes.put(
                new EntityNumber(1L),
                Node.newBuilder()
                        .nodeId(1L)
                        .weight(2L)
                        .gossipCaCertificate(Bytes.fromHex("0123"))
                        .gossipEndpoint(new ServiceEndpoint(Bytes.EMPTY, 50211, "test.org"))
                        .build());
        nodes.put(
                new EntityNumber(2L),
                Node.newBuilder()
                        .nodeId(2L)
                        .weight(3L)
                        .gossipCaCertificate(Bytes.fromHex("0123"))
                        .gossipEndpoint(new ServiceEndpoint(Bytes.EMPTY, 50211, "test.org"))
                        .build());
        stakingInfo.put(
                new EntityNumber(0L),
                StakingNodeInfo.newBuilder().stake(1000).weight(1).build());
        stakingInfo.put(
                new EntityNumber(1),
                StakingNodeInfo.newBuilder().stake(1000).deleted(true).weight(1).build());

        subject.handleTxBody(state, txBody.build(), configWith(true, false));
        final var candidateRosterHash = state.getWritableStates(RosterService.NAME)
                .<RosterState>getSingleton(ROSTER_STATE_STATE_ID)
                .get()
                .candidateRosterHash();
        final var candidateRoster = state.getWritableStates(RosterService.NAME)
                .<ProtoBytes, Roster>get(ROSTERS_STATE_ID)
                .get(new ProtoBytes(candidateRosterHash));
        assertEquals(candidateRoster.rosterEntries().size(), 3);
        // Updates the stake value for node 0 as weight in the candidate roster
        assertEquals(candidateRoster.rosterEntries().get(0).weight(), 1000);
        // node 1 is deleted, so weight is zero
        assertEquals(candidateRoster.rosterEntries().get(1).weight(), 0);
        // node 2 is newly added in the candidate roster, so weight will be zero
        assertEquals(candidateRoster.rosterEntries().get(2).weight(), 0);
    }

    private Configuration configWith(final boolean createCandidateRoster, final boolean exportCandidateRoster) {
        return HederaTestConfigBuilder.create()
                .withValue("addressBook.createCandidateRosterOnPrepareUpgrade", "" + createCandidateRoster)
                .withValue("networkAdmin.exportCandidateRoster", "" + exportCandidateRoster)
                .withValue("networkAdmin.candidateRosterExportFile", "candidate-network.json")
                .getOrCreateConfig();
    }
}
