// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.services;

import static com.hedera.hapi.util.HapiUtils.asTimestamp;
import static com.hedera.node.app.service.entityid.impl.schemas.V0490EntityIdSchema.ENTITY_ID_STATE_ID;
import static com.hedera.node.app.service.entityid.impl.schemas.V0490EntityIdSchema.ENTITY_ID_STATE_LABEL;
import static com.hedera.node.app.service.entityid.impl.schemas.V0590EntityIdSchema.ENTITY_COUNTS_STATE_ID;
import static com.hedera.node.app.service.entityid.impl.schemas.V0590EntityIdSchema.ENTITY_COUNTS_STATE_LABEL;
import static com.hedera.node.app.service.token.impl.handlers.BaseCryptoHandler.asAccount;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.ACCOUNTS_STATE_ID;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.ACCOUNTS_STATE_LABEL;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.ALIASES_STATE_ID;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.ALIASES_STATE_LABEL;
import static com.hedera.node.app.service.token.impl.schemas.V0700TokenSchema.NODE_PAYMENTS_STATE_ID;
import static com.hedera.node.app.service.token.impl.schemas.V0700TokenSchema.NODE_PAYMENTS_STATE_LABEL;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TransferList;
import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.hapi.node.state.entity.EntityCounts;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.NodePayment;
import com.hedera.hapi.node.state.token.NodePayments;
import com.hedera.node.app.service.entityid.EntityIdFactory;
import com.hedera.node.app.service.entityid.EntityIdService;
import com.hedera.node.app.service.token.TokenService;
import com.hedera.node.app.spi.fixtures.ids.FakeEntityIdFactoryImpl;
import com.hedera.node.app.workflows.handle.record.SystemTransactions;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.swirlds.state.State;
import com.swirlds.state.spi.WritableSingletonStateBase;
import com.swirlds.state.test.fixtures.FunctionReadableSingletonState;
import com.swirlds.state.test.fixtures.FunctionWritableSingletonState;
import com.swirlds.state.test.fixtures.MapReadableStates;
import com.swirlds.state.test.fixtures.MapWritableKVState;
import com.swirlds.state.test.fixtures.MapWritableStates;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NodeFeeManagerTest {
    private static final Instant NOW = Instant.ofEpochSecond(1_234_567L);
    private static final Instant PREV_PERIOD = NOW.minusSeconds(1500);
    private static final AccountID NODE_ACCOUNT_ID_3 = asAccount(0, 0, 3);
    private static final AccountID NODE_ACCOUNT_ID_5 = asAccount(0, 0, 5);

    @Mock(strictness = Mock.Strictness.LENIENT)
    private ConfigProvider configProvider;

    @Mock(strictness = Mock.Strictness.LENIENT)
    private State state;

    @Mock
    private SystemTransactions systemTransactions;

    private MapWritableStates writableStates;
    private MapReadableStates readableStates;

    private EntityIdFactory entityIdFactory = new FakeEntityIdFactoryImpl(0, 0);

    private final AtomicReference<NodePayments> nodePaymentsRef = new AtomicReference<>();
    private WritableSingletonStateBase<NodePayments> nodePaymentsState;
    private NodeFeeManager subject;

    @BeforeEach
    void setUp() {
        final var config = HederaTestConfigBuilder.create()
                .withValue("staking.periodMins", 1)
                .withValue("nodes.feeCollectionAccountEnabled", true)
                .withValue("nodes.nodeRewardsEnabled", true)
                .withValue("nodes.preserveMinNodeRewardBalance", false)
                .withValue("staking.feesNodeRewardPercentage", 10)
                .withValue("staking.feesStakingRewardPercentage", 10)
                .getOrCreateConfig();
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(config, 1));
        subject = new NodeFeeManager(configProvider, entityIdFactory);
    }

    @Test
    void testAccumulateAddsFees() {
        givenSetup(NodePayments.DEFAULT);

        subject.onOpenBlock(state);
        assertEquals(0, nodePaymentsRef.get().payments().size());

        subject.accumulate(NODE_ACCOUNT_ID_3, 100L);
        subject.accumulate(NODE_ACCOUNT_ID_3, 50L);
        subject.accumulate(NODE_ACCOUNT_ID_5, 200L);
        subject.onCloseBlock(state);

        // Verify the state was updated
        final var updatedPayments = nodePaymentsRef.get();
        assertNotNull(updatedPayments);
        assertEquals(2, updatedPayments.payments().size());

        // Verify the fees were merged correctly
        final var payment3 = updatedPayments.payments().stream()
                .filter(p -> p.nodeAccountId().equals(NODE_ACCOUNT_ID_3))
                .findFirst()
                .orElseThrow();
        assertEquals(150L, payment3.fees());

        final var payment5 = updatedPayments.payments().stream()
                .filter(p -> p.nodeAccountId().equals(NODE_ACCOUNT_ID_5))
                .findFirst()
                .orElseThrow();
        assertEquals(200L, payment5.fees());
    }

    @Test
    void testResetNodeFeesClearsMap() {
        subject.accumulate(NODE_ACCOUNT_ID_3, 100L);
        subject.accumulate(NODE_ACCOUNT_ID_5, 200L);
        subject.resetNodeFees();

        // After reset, onCloseBlock should write empty payments
        givenSetup(NodePayments.DEFAULT);
        subject.onOpenBlock(state);
        subject.onCloseBlock(state);

        final var updatedPayments = nodePaymentsRef.get();
        assertNotNull(updatedPayments);
        assertTrue(updatedPayments.payments().isEmpty());
    }

    @Test
    void testOnOpenBlockLoadsStateIntoMemory() {
        final var initialPayments = NodePayments.newBuilder()
                .payments(List.of(
                        NodePayment.newBuilder()
                                .nodeAccountId(NODE_ACCOUNT_ID_3)
                                .fees(100L)
                                .build(),
                        NodePayment.newBuilder()
                                .nodeAccountId(NODE_ACCOUNT_ID_5)
                                .fees(200L)
                                .build()))
                .build();
        givenSetup(initialPayments);

        // onOpenBlock loads state into memory
        subject.onOpenBlock(state);
        assertEquals(2, nodePaymentsRef.get().payments().size());

        // Accumulate more fees - this should merge with existing
        subject.accumulate(NODE_ACCOUNT_ID_3, 50L);
        subject.onCloseBlock(state);

        // Verify the state was updated with merged fees
        final var updatedPayments = nodePaymentsRef.get();
        assertNotNull(updatedPayments);
        assertEquals(2, updatedPayments.payments().size());

        // Find the payment for account 3 and verify it has 150 (100 from state + 50 accumulated)
        final var payment3 = updatedPayments.payments().stream()
                .filter(p -> p.nodeAccountId().equals(NODE_ACCOUNT_ID_3))
                .findFirst()
                .orElseThrow();
        assertEquals(150L, payment3.fees());

        // Account 5 should still have 200 (unchanged from state)
        final var payment5 = updatedPayments.payments().stream()
                .filter(p -> p.nodeAccountId().equals(NODE_ACCOUNT_ID_5))
                .findFirst()
                .orElseThrow();
        assertEquals(200L, payment5.fees());
    }

    @Test
    void testOnCloseBlockWritesStateBack() {
        givenSetup(NodePayments.DEFAULT);

        subject.onOpenBlock(state);
        subject.accumulate(asAccount(0, 0, 7L), 300L);
        subject.onCloseBlock(state);

        // Verify the state was updated (commit is called internally by onCloseBlock)
        final var updatedPayments = nodePaymentsRef.get();
        assertNotNull(updatedPayments);
        assertEquals(1, updatedPayments.payments().size());
        assertEquals(7L, updatedPayments.payments().get(0).nodeAccountId());
        assertEquals(300L, updatedPayments.payments().get(0).fees());
    }

    @Test
    void testDistributeFeesWhenDisabled() {
        final var config = HederaTestConfigBuilder.create()
                .withValue("nodes.feeCollectionAccountEnabled", false)
                .getOrCreateConfig();
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(config, 1));
        subject = new NodeFeeManager(configProvider, entityIdFactory);

        final var result = subject.distributeFees(state, NOW, systemTransactions);

        assertFalse(result);
        verify(systemTransactions, never()).dispatchNodePayments(any(), any(), any());
    }

    @Test
    void testDistributeFeesWhenCurrentPeriod() {
        // Set up with last distribution time in current period
        // With 1 minute staking period, we need to ensure both times are in the same period
        // NOW = 1234567 seconds. To be in the same 1-minute period, use a time within the same minute
        // 1234567 / 60 = 20576.11... so period 20576 starts at 20576 * 60 = 1234560
        // Use a time that's definitely in the same period (same minute)
        final var sameMinuteTime = Instant.ofEpochSecond(1234565L); // 2 seconds before NOW, same minute
        final var nodePayments = NodePayments.newBuilder()
                .lastNodeFeeDistributionTime(asTimestamp(sameMinuteTime))
                .payments(List.of())
                .build();
        givenSetupForDistribution(nodePayments, 1000L);

        final var result = subject.distributeFees(state, NOW, systemTransactions);

        assertFalse(result);
        verify(systemTransactions, never()).dispatchNodePayments(any(), any(), any());
    }

    @Test
    void testDistributeFeesWhenPreviousPeriod() {
        // Set up with last distribution time in previous period
        final var nodePayments = NodePayments.newBuilder()
                .lastNodeFeeDistributionTime(asTimestamp(PREV_PERIOD))
                .payments(List.of(NodePayment.newBuilder()
                        .nodeAccountId(NODE_ACCOUNT_ID_3)
                        .fees(100L)
                        .build()))
                .build();
        givenSetupForDistribution(nodePayments, 1000L);

        final var result = subject.distributeFees(state, NOW, systemTransactions);

        assertTrue(result);
        final var transferCaptor = ArgumentCaptor.forClass(TransferList.class);
        verify(systemTransactions).dispatchNodePayments(eq(state), eq(NOW), transferCaptor.capture());

        final var transfers = transferCaptor.getValue();
        assertNotNull(transfers);
        assertFalse(transfers.accountAmounts().isEmpty());
    }

    @Test
    void testDistributeFeesWhenNeverPaid() {
        // Set up with null last distribution time (genesis case)
        final var nodePayments = NodePayments.newBuilder()
                .lastNodeFeeDistributionTime((Timestamp) null)
                .payments(List.of())
                .build();
        givenSetupForDistribution(nodePayments, 1000L);

        final var result = subject.distributeFees(state, NOW, systemTransactions);

        // In NEVER case, we reset but don't distribute
        assertTrue(result);
    }

    @Test
    void testDistributeFeesSkipsDeletedNodeAccounts() {
        final var nodePayments = NodePayments.newBuilder()
                .lastNodeFeeDistributionTime(asTimestamp(PREV_PERIOD))
                .payments(List.of(
                        NodePayment.newBuilder()
                                .nodeAccountId(NODE_ACCOUNT_ID_3)
                                .fees(100L)
                                .build(),
                        NodePayment.newBuilder()
                                .nodeAccountId(asAccount(0, 0, 999L))
                                .fees(200L)
                                .build()))
                .build();
        givenSetupForDistributionWithDeletedAccount(nodePayments, 1000L, 999L);

        final var result = subject.distributeFees(state, NOW, systemTransactions);

        assertTrue(result);
        final var transferCaptor = ArgumentCaptor.forClass(TransferList.class);
        verify(systemTransactions).dispatchNodePayments(eq(state), eq(NOW), transferCaptor.capture());

        final var transfers = transferCaptor.getValue();
        // Should only have transfers for account 3, not 999 (deleted)
        // Plus the fee collection account debit and network/service fee distributions
        assertNotNull(transfers);
    }

    @Test
    void testOnOpenBlockDoesNothingWhenDisabled() {
        final var config = HederaTestConfigBuilder.create()
                .withValue("nodes.feeCollectionAccountEnabled", false)
                .getOrCreateConfig();
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(config, 1));
        subject = new NodeFeeManager(configProvider, entityIdFactory);

        subject.onOpenBlock(state);

        verify(state, never()).getReadableStates(any());
    }

    @Test
    void testOnCloseBlockDoesNothingWhenDisabled() {
        final var config = HederaTestConfigBuilder.create()
                .withValue("nodes.feeCollectionAccountEnabled", false)
                .getOrCreateConfig();
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(config, 1));
        subject = new NodeFeeManager(configProvider, entityIdFactory);

        subject.onCloseBlock(state);

        verify(state, never()).getWritableStates(any());
    }

    private void givenSetup(NodePayments nodePayments) {
        nodePaymentsState = new FunctionWritableSingletonState<>(
                NODE_PAYMENTS_STATE_ID, NODE_PAYMENTS_STATE_LABEL, nodePaymentsRef::get, nodePaymentsRef::set);
        nodePaymentsRef.set(nodePayments);

        // Create a readable singleton state for onOpenBlock
        final var readableNodePaymentsState = new FunctionReadableSingletonState<>(
                NODE_PAYMENTS_STATE_ID, NODE_PAYMENTS_STATE_LABEL, nodePaymentsRef::get);

        // Use MapWritableStates which properly commits to backing store
        writableStates = MapWritableStates.builder().state(nodePaymentsState).build();

        readableStates =
                MapReadableStates.builder().state(readableNodePaymentsState).build();

        lenient().when(state.getReadableStates(TokenService.NAME)).thenReturn(readableStates);
        lenient().when(state.getWritableStates(TokenService.NAME)).thenReturn(writableStates);
    }

    private void givenSetupForDistribution(NodePayments nodePayments, long feeCollectionBalance) {
        nodePaymentsState = new FunctionWritableSingletonState<>(
                NODE_PAYMENTS_STATE_ID, NODE_PAYMENTS_STATE_LABEL, nodePaymentsRef::get, nodePaymentsRef::set);
        nodePaymentsRef.set(nodePayments);

        final var readableNodePaymentsState = new FunctionReadableSingletonState<>(
                NODE_PAYMENTS_STATE_ID, NODE_PAYMENTS_STATE_LABEL, nodePaymentsRef::get);

        final var entityIdState = new FunctionWritableSingletonState<>(
                ENTITY_ID_STATE_ID,
                ENTITY_ID_STATE_LABEL,
                () -> EntityNumber.newBuilder().build(),
                c -> {});
        final var entityCountsState = new FunctionWritableSingletonState<>(
                ENTITY_COUNTS_STATE_ID, ENTITY_COUNTS_STATE_LABEL, () -> EntityCounts.DEFAULT, c -> {});

        // Set up accounts
        final var accounts = MapWritableKVState.<AccountID, Account>builder(ACCOUNTS_STATE_ID, ACCOUNTS_STATE_LABEL)
                .value(
                        asAccount(0, 0, 802),
                        Account.newBuilder()
                                .tinybarBalance(feeCollectionBalance)
                                .build())
                .value(
                        asAccount(0, 0, 3),
                        Account.newBuilder().tinybarBalance(0).build())
                .value(
                        asAccount(0, 0, 98),
                        Account.newBuilder().tinybarBalance(0).build())
                .value(
                        asAccount(0, 0, 800),
                        Account.newBuilder().tinybarBalance(0).build())
                .value(
                        asAccount(0, 0, 801),
                        Account.newBuilder().tinybarBalance(0).build())
                .build();

        // Set up aliases (empty, but required by ReadableAccountStoreImpl)
        final var aliases = MapWritableKVState.<ProtoBytes, AccountID>builder(ALIASES_STATE_ID, ALIASES_STATE_LABEL)
                .build();

        writableStates = new MapWritableStates(Map.of(
                NODE_PAYMENTS_STATE_ID, nodePaymentsState,
                ENTITY_ID_STATE_ID, entityIdState,
                ENTITY_COUNTS_STATE_ID, entityCountsState,
                ACCOUNTS_STATE_ID, accounts,
                ALIASES_STATE_ID, aliases));

        final var readableEntityIdState = new FunctionReadableSingletonState<>(
                ENTITY_ID_STATE_ID, ENTITY_ID_STATE_LABEL, () -> EntityNumber.newBuilder()
                        .build());
        final var readableEntityCountsState = new FunctionReadableSingletonState<>(
                ENTITY_COUNTS_STATE_ID, ENTITY_COUNTS_STATE_LABEL, () -> EntityCounts.DEFAULT);

        readableStates = new MapReadableStates(Map.of(
                NODE_PAYMENTS_STATE_ID, readableNodePaymentsState,
                ENTITY_ID_STATE_ID, readableEntityIdState,
                ENTITY_COUNTS_STATE_ID, readableEntityCountsState));

        lenient().when(state.getReadableStates(TokenService.NAME)).thenReturn(readableStates);
        lenient().when(state.getWritableStates(TokenService.NAME)).thenReturn(writableStates);
        lenient().when(state.getWritableStates(EntityIdService.NAME)).thenReturn(writableStates);
        lenient().when(state.getReadableStates(EntityIdService.NAME)).thenReturn(readableStates);
    }

    private void givenSetupForDistributionWithDeletedAccount(
            NodePayments nodePayments, long feeCollectionBalance, long deletedAccountNum) {
        nodePaymentsState = new FunctionWritableSingletonState<>(
                NODE_PAYMENTS_STATE_ID, NODE_PAYMENTS_STATE_LABEL, nodePaymentsRef::get, nodePaymentsRef::set);
        nodePaymentsRef.set(nodePayments);

        final var readableNodePaymentsState = new FunctionReadableSingletonState<>(
                NODE_PAYMENTS_STATE_ID, NODE_PAYMENTS_STATE_LABEL, nodePaymentsRef::get);

        final var entityIdState = new FunctionWritableSingletonState<>(
                ENTITY_ID_STATE_ID,
                ENTITY_ID_STATE_LABEL,
                () -> EntityNumber.newBuilder().build(),
                c -> {});
        final var entityCountsState = new FunctionWritableSingletonState<>(
                ENTITY_COUNTS_STATE_ID, ENTITY_COUNTS_STATE_LABEL, () -> EntityCounts.DEFAULT, c -> {});

        // Set up accounts with a deleted account
        final var accounts = MapWritableKVState.<AccountID, Account>builder(ACCOUNTS_STATE_ID, ACCOUNTS_STATE_LABEL)
                .value(
                        asAccount(0, 0, 802),
                        Account.newBuilder()
                                .tinybarBalance(feeCollectionBalance)
                                .build())
                .value(
                        asAccount(0, 0, 3),
                        Account.newBuilder().tinybarBalance(0).build())
                .value(
                        asAccount(0, 0, deletedAccountNum),
                        Account.newBuilder().deleted(true).build())
                .value(
                        asAccount(0, 0, 98),
                        Account.newBuilder().tinybarBalance(0).build())
                .value(
                        asAccount(0, 0, 800),
                        Account.newBuilder().tinybarBalance(0).build())
                .value(
                        asAccount(0, 0, 801),
                        Account.newBuilder().tinybarBalance(0).build())
                .build();

        // Set up aliases (empty, but required by ReadableAccountStoreImpl)
        final var aliases = MapWritableKVState.<ProtoBytes, AccountID>builder(ALIASES_STATE_ID, ALIASES_STATE_LABEL)
                .build();

        writableStates = new MapWritableStates(Map.of(
                NODE_PAYMENTS_STATE_ID, nodePaymentsState,
                ENTITY_ID_STATE_ID, entityIdState,
                ENTITY_COUNTS_STATE_ID, entityCountsState,
                ACCOUNTS_STATE_ID, accounts,
                ALIASES_STATE_ID, aliases));

        final var readableEntityIdState = new FunctionReadableSingletonState<>(
                ENTITY_ID_STATE_ID, ENTITY_ID_STATE_LABEL, () -> EntityNumber.newBuilder()
                        .build());
        final var readableEntityCountsState = new FunctionReadableSingletonState<>(
                ENTITY_COUNTS_STATE_ID, ENTITY_COUNTS_STATE_LABEL, () -> EntityCounts.DEFAULT);

        readableStates = new MapReadableStates(Map.of(
                NODE_PAYMENTS_STATE_ID, readableNodePaymentsState,
                ENTITY_ID_STATE_ID, readableEntityIdState,
                ENTITY_COUNTS_STATE_ID, readableEntityCountsState));

        lenient().when(state.getReadableStates(TokenService.NAME)).thenReturn(readableStates);
        lenient().when(state.getWritableStates(TokenService.NAME)).thenReturn(writableStates);
        lenient().when(state.getWritableStates(EntityIdService.NAME)).thenReturn(writableStates);
        lenient().when(state.getReadableStates(EntityIdService.NAME)).thenReturn(readableStates);
    }
}
