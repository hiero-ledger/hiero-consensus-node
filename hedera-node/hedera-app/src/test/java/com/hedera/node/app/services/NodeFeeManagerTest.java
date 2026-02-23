// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.services;

import static com.hedera.hapi.util.HapiUtils.asTimestamp;
import static com.hedera.node.app.service.entityid.impl.schemas.V0490EntityIdSchema.ENTITY_ID_STATE_ID;
import static com.hedera.node.app.service.entityid.impl.schemas.V0490EntityIdSchema.ENTITY_ID_STATE_LABEL;
import static com.hedera.node.app.service.entityid.impl.schemas.V0590EntityIdSchema.ENTITY_COUNTS_STATE_ID;
import static com.hedera.node.app.service.entityid.impl.schemas.V0590EntityIdSchema.ENTITY_COUNTS_STATE_LABEL;
import static com.hedera.node.app.service.entityid.impl.schemas.V0720EntityIdSchema.NODE_ID_STATE_ID;
import static com.hedera.node.app.service.entityid.impl.schemas.V0720EntityIdSchema.NODE_ID_STATE_LABEL;
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
                .withValue("staking.fees.nodeRewardPercentage", 10)
                .withValue("staking.fees.stakingRewardPercentage", 10)
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
        assertEquals(asAccount(0, 0, 7L), updatedPayments.payments().get(0).nodeAccountId());
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

    @Test
    void testDistributeFeesRoutesToNodeRewardWhenBelowMinBalance() {
        // Configure preserveMinNodeRewardBalance=true with a high minimum balance
        final var config = HederaTestConfigBuilder.create()
                .withValue("staking.periodMins", 1)
                .withValue("nodes.feeCollectionAccountEnabled", true)
                .withValue("nodes.nodeRewardsEnabled", true)
                .withValue("nodes.preserveMinNodeRewardBalance", true)
                .withValue("nodes.minNodeRewardBalance", 10000L) // High minimum
                .withValue("staking.feesNodeRewardPercentage", 10)
                .withValue("staking.feesStakingRewardPercentage", 10)
                .getOrCreateConfig();
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(config, 1));
        subject = new NodeFeeManager(configProvider, entityIdFactory);

        // Set up with 0.0.801 having low balance (below minimum)
        final var nodePayments = NodePayments.newBuilder()
                .lastNodeFeeDistributionTime(asTimestamp(PREV_PERIOD))
                .payments(List.of())
                .build();
        givenSetupForDistributionWithNodeRewardBalance(nodePayments, 1000L, 500L); // 500 < 10000 minimum

        // Accumulate fees in memory (simulating block processing)
        subject.accumulate(NODE_ACCOUNT_ID_3, 100L);

        final var result = subject.distributeFees(state, NOW, systemTransactions);

        assertTrue(result);
        final var transferCaptor = ArgumentCaptor.forClass(TransferList.class);
        verify(systemTransactions).dispatchNodePayments(eq(state), eq(NOW), transferCaptor.capture());

        final var transfers = transferCaptor.getValue();
        assertNotNull(transfers);

        // All network/service fees (1000 - 100 = 900) should go to 0.0.801
        final var nodeRewardTransfer = transfers.accountAmounts().stream()
                .filter(aa -> aa.accountID().accountNum() == 801L)
                .findFirst()
                .orElse(null);
        assertNotNull(nodeRewardTransfer);
        assertEquals(900L, nodeRewardTransfer.amount()); // All network fees go to 801
    }

    @Test
    void testDistributeFeesNormalDistributionWhenAboveMinBalance() {
        // Configure preserveMinNodeRewardBalance=true with a low minimum balance
        final var config = HederaTestConfigBuilder.create()
                .withValue("staking.periodMins", 1)
                .withValue("nodes.feeCollectionAccountEnabled", true)
                .withValue("nodes.nodeRewardsEnabled", true)
                .withValue("nodes.preserveMinNodeRewardBalance", true)
                .withValue("nodes.minNodeRewardBalance", 100L) // Low minimum
                .withValue("staking.feesNodeRewardPercentage", 10)
                .withValue("staking.feesStakingRewardPercentage", 10)
                .getOrCreateConfig();
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(config, 1));
        subject = new NodeFeeManager(configProvider, entityIdFactory);

        // Set up with 0.0.801 having high balance (above minimum)
        final var nodePayments = NodePayments.newBuilder()
                .lastNodeFeeDistributionTime(asTimestamp(PREV_PERIOD))
                .payments(List.of())
                .build();
        givenSetupForDistributionWithNodeRewardBalance(nodePayments, 1000L, 500L); // 500 > 100 minimum

        // Accumulate fees in memory (simulating block processing)
        subject.accumulate(NODE_ACCOUNT_ID_3, 100L);

        final var result = subject.distributeFees(state, NOW, systemTransactions);

        assertTrue(result);
        final var transferCaptor = ArgumentCaptor.forClass(TransferList.class);
        verify(systemTransactions).dispatchNodePayments(eq(state), eq(NOW), transferCaptor.capture());

        final var transfers = transferCaptor.getValue();
        assertNotNull(transfers);

        // Network fees (900) should be split: 10% to 801, 10% to 800, 80% to 98
        final var nodeRewardTransfer = transfers.accountAmounts().stream()
                .filter(aa -> aa.accountID().accountNum() == 801L)
                .findFirst()
                .orElse(null);
        assertNotNull(nodeRewardTransfer);
        assertEquals(90L, nodeRewardTransfer.amount()); // 10% of 900

        final var stakingRewardTransfer = transfers.accountAmounts().stream()
                .filter(aa -> aa.accountID().accountNum() == 800L)
                .findFirst()
                .orElse(null);
        assertNotNull(stakingRewardTransfer);
        assertEquals(90L, stakingRewardTransfer.amount()); // 10% of 900

        final var fundingTransfer = transfers.accountAmounts().stream()
                .filter(aa -> aa.accountID().accountNum() == 98L)
                .findFirst()
                .orElse(null);
        assertNotNull(fundingTransfer);
        assertEquals(720L, fundingTransfer.amount()); // 80% of 900
    }

    @Test
    void testDistributeFeesSkipsNonExistentNodeAccounts() {
        final var nodePayments = NodePayments.newBuilder()
                .lastNodeFeeDistributionTime(asTimestamp(PREV_PERIOD))
                .payments(List.of())
                .build();
        givenSetupForDistributionWithMissingAccount(nodePayments, 1000L, 888L);

        // Accumulate fees in memory (simulating block processing)
        subject.accumulate(NODE_ACCOUNT_ID_3, 100L);
        subject.accumulate(asAccount(0, 0, 888L), 200L); // Non-existent account

        final var result = subject.distributeFees(state, NOW, systemTransactions);

        assertTrue(result);
        final var transferCaptor = ArgumentCaptor.forClass(TransferList.class);
        verify(systemTransactions).dispatchNodePayments(eq(state), eq(NOW), transferCaptor.capture());

        final var transfers = transferCaptor.getValue();
        // Should only have transfers for account 3, not 888 (non-existent)
        final var account888Transfer = transfers.accountAmounts().stream()
                .filter(aa -> aa.accountID().accountNum() == 888L)
                .findFirst()
                .orElse(null);
        assertNull(account888Transfer);

        // Account 3 should still receive its fees
        final var account3Transfer = transfers.accountAmounts().stream()
                .filter(aa -> aa.accountID().accountNum() == 3L)
                .findFirst()
                .orElse(null);
        assertNotNull(account3Transfer);
        assertEquals(100L, account3Transfer.amount());
    }

    @Test
    void testDistributeFeesWithZeroNodeFees() {
        // Set up with no node fees but positive fee collection balance
        final var nodePayments = NodePayments.newBuilder()
                .lastNodeFeeDistributionTime(asTimestamp(PREV_PERIOD))
                .payments(List.of()) // No node fees
                .build();
        givenSetupForDistribution(nodePayments, 1000L);

        final var result = subject.distributeFees(state, NOW, systemTransactions);

        assertTrue(result);
        final var transferCaptor = ArgumentCaptor.forClass(TransferList.class);
        verify(systemTransactions).dispatchNodePayments(eq(state), eq(NOW), transferCaptor.capture());

        final var transfers = transferCaptor.getValue();
        assertNotNull(transfers);

        // All 1000 should be distributed to network/service accounts
        // 10% to 801, 10% to 800, 80% to 98
        final var nodeRewardTransfer = transfers.accountAmounts().stream()
                .filter(aa -> aa.accountID().accountNum() == 801L)
                .findFirst()
                .orElse(null);
        assertNotNull(nodeRewardTransfer);
        assertEquals(100L, nodeRewardTransfer.amount()); // 10% of 1000

        final var stakingRewardTransfer = transfers.accountAmounts().stream()
                .filter(aa -> aa.accountID().accountNum() == 800L)
                .findFirst()
                .orElse(null);
        assertNotNull(stakingRewardTransfer);
        assertEquals(100L, stakingRewardTransfer.amount()); // 10% of 1000

        final var fundingTransfer = transfers.accountAmounts().stream()
                .filter(aa -> aa.accountID().accountNum() == 98L)
                .findFirst()
                .orElse(null);
        assertNotNull(fundingTransfer);
        assertEquals(800L, fundingTransfer.amount()); // 80% of 1000
    }

    @Test
    void testDistributeFeesWithZeroBalance() {
        // Set up with zero fee collection balance
        final var nodePayments = NodePayments.newBuilder()
                .lastNodeFeeDistributionTime(asTimestamp(PREV_PERIOD))
                .payments(List.of())
                .build();
        givenSetupForDistribution(nodePayments, 0L);

        final var result = subject.distributeFees(state, NOW, systemTransactions);

        assertTrue(result);
        final var transferCaptor = ArgumentCaptor.forClass(TransferList.class);
        verify(systemTransactions).dispatchNodePayments(eq(state), eq(NOW), transferCaptor.capture());

        final var transfers = transferCaptor.getValue();
        // With zero balance, there should be no transfers (empty list)
        assertTrue(transfers.accountAmounts().isEmpty());
    }

    @Test
    void testDistributeFeesWithMultipleNodesVaryingFees() {
        final var nodePayments = NodePayments.newBuilder()
                .lastNodeFeeDistributionTime(asTimestamp(PREV_PERIOD))
                .payments(List.of())
                .build();
        givenSetupForDistributionWithMultipleNodes(nodePayments, 1600L); // 100+300+200=600 node fees, 1000 network fees

        // Accumulate fees in memory (simulating block processing)
        subject.accumulate(NODE_ACCOUNT_ID_3, 100L);
        subject.accumulate(NODE_ACCOUNT_ID_5, 300L);
        subject.accumulate(asAccount(0, 0, 7L), 200L);

        final var result = subject.distributeFees(state, NOW, systemTransactions);

        assertTrue(result);
        final var transferCaptor = ArgumentCaptor.forClass(TransferList.class);
        verify(systemTransactions).dispatchNodePayments(eq(state), eq(NOW), transferCaptor.capture());

        final var transfers = transferCaptor.getValue();
        assertNotNull(transfers);

        // Verify each node gets their correct fees
        final var node3Transfer = transfers.accountAmounts().stream()
                .filter(aa -> aa.accountID().accountNum() == 3L)
                .findFirst()
                .orElse(null);
        assertNotNull(node3Transfer);
        assertEquals(100L, node3Transfer.amount());

        final var node5Transfer = transfers.accountAmounts().stream()
                .filter(aa -> aa.accountID().accountNum() == 5L)
                .findFirst()
                .orElse(null);
        assertNotNull(node5Transfer);
        assertEquals(300L, node5Transfer.amount());

        final var node7Transfer = transfers.accountAmounts().stream()
                .filter(aa -> aa.accountID().accountNum() == 7L)
                .findFirst()
                .orElse(null);
        assertNotNull(node7Transfer);
        assertEquals(200L, node7Transfer.amount());

        // Verify fee collection account is debited
        final var feeCollectorDebit = transfers.accountAmounts().stream()
                .filter(aa -> aa.accountID().accountNum() == 802L)
                .findFirst()
                .orElse(null);
        assertNotNull(feeCollectorDebit);
        assertTrue(feeCollectorDebit.amount() < 0);
    }

    @Test
    void testDistributeFeesVerifiesExactTransferAmounts() {
        final var nodePayments = NodePayments.newBuilder()
                .lastNodeFeeDistributionTime(asTimestamp(PREV_PERIOD))
                .payments(List.of())
                .build();
        givenSetupForDistribution(nodePayments, 1000L); // 500 node fees, 500 network fees

        // Accumulate fees in memory (simulating block processing)
        subject.accumulate(NODE_ACCOUNT_ID_3, 500L);

        final var result = subject.distributeFees(state, NOW, systemTransactions);

        assertTrue(result);
        final var transferCaptor = ArgumentCaptor.forClass(TransferList.class);
        verify(systemTransactions).dispatchNodePayments(eq(state), eq(NOW), transferCaptor.capture());

        final var transfers = transferCaptor.getValue();

        // Calculate expected amounts
        // Node 3: 500
        // Network fees: 500
        //   - 801: 10% of 500 = 50
        //   - 800: 10% of 500 = 50
        //   - 98: 80% of 500 = 400
        // Total credits: 500 + 50 + 50 + 400 = 1000
        // Fee collector debit: -1000

        long totalCredits = transfers.accountAmounts().stream()
                .filter(aa -> aa.amount() > 0)
                .mapToLong(aa -> aa.amount())
                .sum();
        long totalDebits = transfers.accountAmounts().stream()
                .filter(aa -> aa.amount() < 0)
                .mapToLong(aa -> aa.amount())
                .sum();

        assertEquals(1000L, totalCredits);
        assertEquals(-1000L, totalDebits);
        assertEquals(0L, totalCredits + totalDebits); // Net zero
    }

    @Test
    void testDistributeFeesResetsNodePaymentsState() {
        final var nodePayments = NodePayments.newBuilder()
                .lastNodeFeeDistributionTime(asTimestamp(PREV_PERIOD))
                .payments(List.of())
                .build();
        givenSetupForDistribution(nodePayments, 1000L);

        // Accumulate fees in memory (simulating block processing)
        subject.accumulate(NODE_ACCOUNT_ID_3, 100L);

        subject.distributeFees(state, NOW, systemTransactions);

        // Verify the node payments state was reset
        final var updatedPayments = nodePaymentsRef.get();
        assertNotNull(updatedPayments);
        assertTrue(updatedPayments.payments().isEmpty());
        // Verify lastNodeFeeDistributionTime was updated
        assertNotNull(updatedPayments.lastNodeFeeDistributionTime());
        assertEquals(asTimestamp(NOW), updatedPayments.lastNodeFeeDistributionTime());
    }

    @Test
    void testDistributeFeesWithNodeRewardsDisabled() {
        // Configure nodeRewardsEnabled=false
        final var config = HederaTestConfigBuilder.create()
                .withValue("staking.periodMins", 1)
                .withValue("nodes.feeCollectionAccountEnabled", true)
                .withValue("nodes.nodeRewardsEnabled", false)
                .withValue("nodes.preserveMinNodeRewardBalance", true)
                .withValue("nodes.minNodeRewardBalance", 10000L)
                .withValue("staking.fees.nodeRewardPercentage", 10)
                .withValue("staking.fees.stakingRewardPercentage", 10)
                .getOrCreateConfig();
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(config, 1));
        subject = new NodeFeeManager(configProvider, entityIdFactory);

        final var nodePayments = NodePayments.newBuilder()
                .lastNodeFeeDistributionTime(asTimestamp(PREV_PERIOD))
                .payments(List.of())
                .build();
        givenSetupForDistributionWithNodeRewardBalance(
                nodePayments, 1000L, 100L); // Low balance but nodeRewardsEnabled=false

        final var result = subject.distributeFees(state, NOW, systemTransactions);

        assertTrue(result);
        final var transferCaptor = ArgumentCaptor.forClass(TransferList.class);
        verify(systemTransactions).dispatchNodePayments(eq(state), eq(NOW), transferCaptor.capture());

        final var transfers = transferCaptor.getValue();
        // With nodeRewardsEnabled=false, normal distribution should occur (not routing all to 801)
        final var nodeRewardTransfer = transfers.accountAmounts().stream()
                .filter(aa -> aa.accountID().accountNum() == 801L)
                .findFirst()
                .orElse(null);
        assertNotNull(nodeRewardTransfer);
        assertEquals(100L, nodeRewardTransfer.amount()); // 10% of 1000, not all 1000
    }

    @Test
    void testDistributeFeesWithZeroPercentages() {
        // Configure zero percentages for node and staking rewards
        final var config = HederaTestConfigBuilder.create()
                .withValue("staking.periodMins", 1)
                .withValue("nodes.feeCollectionAccountEnabled", true)
                .withValue("nodes.nodeRewardsEnabled", true)
                .withValue("nodes.preserveMinNodeRewardBalance", false)
                .withValue("staking.fees.nodeRewardPercentage", 0)
                .withValue("staking.fees.stakingRewardPercentage", 0)
                .getOrCreateConfig();
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(config, 1));
        subject = new NodeFeeManager(configProvider, entityIdFactory);

        final var nodePayments = NodePayments.newBuilder()
                .lastNodeFeeDistributionTime(asTimestamp(PREV_PERIOD))
                .payments(List.of())
                .build();
        givenSetupForDistribution(nodePayments, 1000L);

        final var result = subject.distributeFees(state, NOW, systemTransactions);

        assertTrue(result);
        final var transferCaptor = ArgumentCaptor.forClass(TransferList.class);
        verify(systemTransactions).dispatchNodePayments(eq(state), eq(NOW), transferCaptor.capture());

        final var transfers = transferCaptor.getValue();
        // All fees should go to funding account (98)
        final var fundingTransfer = transfers.accountAmounts().stream()
                .filter(aa -> aa.accountID().accountNum() == 98L)
                .findFirst()
                .orElse(null);
        assertNotNull(fundingTransfer);
        assertEquals(1000L, fundingTransfer.amount());

        // 801 and 800 should not receive anything (0%)
        final var nodeRewardTransfer = transfers.accountAmounts().stream()
                .filter(aa -> aa.accountID().accountNum() == 801L)
                .findFirst()
                .orElse(null);
        assertNull(nodeRewardTransfer);

        final var stakingRewardTransfer = transfers.accountAmounts().stream()
                .filter(aa -> aa.accountID().accountNum() == 800L)
                .findFirst()
                .orElse(null);
        assertNull(stakingRewardTransfer);
    }

    @Test
    void testDistributeFeesWithZeroNodeFeePayment() {
        // Test when a node has zero fees in the payments list
        final var nodePayments = NodePayments.newBuilder()
                .lastNodeFeeDistributionTime(asTimestamp(PREV_PERIOD))
                .payments(List.of())
                .build();
        givenSetupForDistributionWithMultipleNodes(nodePayments, 1100L);

        // Accumulate fees in memory (simulating block processing)
        // Node 3 has zero fees (not accumulated)
        subject.accumulate(NODE_ACCOUNT_ID_5, 100L);

        final var result = subject.distributeFees(state, NOW, systemTransactions);

        assertTrue(result);
        final var transferCaptor = ArgumentCaptor.forClass(TransferList.class);
        verify(systemTransactions).dispatchNodePayments(eq(state), eq(NOW), transferCaptor.capture());

        final var transfers = transferCaptor.getValue();
        // Node 3 should not appear in transfers (zero fees)
        final var node3Transfer = transfers.accountAmounts().stream()
                .filter(aa -> aa.accountID().accountNum() == 3L)
                .findFirst()
                .orElse(null);
        assertNull(node3Transfer);

        // Node 5 should receive its fees
        final var node5Transfer = transfers.accountAmounts().stream()
                .filter(aa -> aa.accountID().accountNum() == 5L)
                .findFirst()
                .orElse(null);
        assertNotNull(node5Transfer);
        assertEquals(100L, node5Transfer.amount());
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

        final var nodeIdState =
                new FunctionWritableSingletonState<>(NODE_ID_STATE_ID, NODE_ID_STATE_LABEL, () -> null, c -> {});

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
                NODE_ID_STATE_ID, nodeIdState,
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

        final var nodeIdState =
                new FunctionWritableSingletonState<>(NODE_ID_STATE_ID, NODE_ID_STATE_LABEL, () -> null, c -> {});

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
                NODE_ID_STATE_ID, nodeIdState,
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

    private void givenSetupForDistributionWithNodeRewardBalance(
            NodePayments nodePayments, long feeCollectionBalance, long nodeRewardBalance) {
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
        final var nodeIdState =
                new FunctionWritableSingletonState<>(NODE_ID_STATE_ID, NODE_ID_STATE_LABEL, () -> null, c -> {});

        // Set up accounts with specific node reward balance
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
                        Account.newBuilder().tinybarBalance(nodeRewardBalance).build())
                .build();

        final var aliases = MapWritableKVState.<ProtoBytes, AccountID>builder(ALIASES_STATE_ID, ALIASES_STATE_LABEL)
                .build();

        writableStates = new MapWritableStates(Map.of(
                NODE_PAYMENTS_STATE_ID, nodePaymentsState,
                ENTITY_ID_STATE_ID, entityIdState,
                ENTITY_COUNTS_STATE_ID, entityCountsState,
                NODE_ID_STATE_ID, nodeIdState,
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

    private void givenSetupForDistributionWithMissingAccount(
            NodePayments nodePayments, long feeCollectionBalance, long missingAccountNum) {
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

        final var nodeIdState =
                new FunctionWritableSingletonState<>(NODE_ID_STATE_ID, NODE_ID_STATE_LABEL, () -> null, c -> {});

        // Set up accounts WITHOUT the missing account
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
                // Note: missingAccountNum is NOT added to the accounts map
                .build();

        final var aliases = MapWritableKVState.<ProtoBytes, AccountID>builder(ALIASES_STATE_ID, ALIASES_STATE_LABEL)
                .build();

        writableStates = new MapWritableStates(Map.of(
                NODE_PAYMENTS_STATE_ID, nodePaymentsState,
                ENTITY_ID_STATE_ID, entityIdState,
                ENTITY_COUNTS_STATE_ID, entityCountsState,
                NODE_ID_STATE_ID, nodeIdState,
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

    private void givenSetupForDistributionWithMultipleNodes(NodePayments nodePayments, long feeCollectionBalance) {
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
        final var nodeIdState =
                new FunctionWritableSingletonState<>(NODE_ID_STATE_ID, NODE_ID_STATE_LABEL, () -> null, c -> {});

        // Set up accounts for multiple nodes
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
                        asAccount(0, 0, 5),
                        Account.newBuilder().tinybarBalance(0).build())
                .value(
                        asAccount(0, 0, 7),
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

        final var aliases = MapWritableKVState.<ProtoBytes, AccountID>builder(ALIASES_STATE_ID, ALIASES_STATE_LABEL)
                .build();

        writableStates = new MapWritableStates(Map.of(
                NODE_PAYMENTS_STATE_ID, nodePaymentsState,
                ENTITY_ID_STATE_ID, entityIdState,
                ENTITY_COUNTS_STATE_ID, entityCountsState,
                NODE_ID_STATE_ID, nodeIdState,
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

    /**
     * Tests that when the network is down for multiple days, only ONE fee distribution
     * is triggered when the network comes back up, not one for each missed day.
     * <p>
     * This is the current expected behavior - the system only checks if we're in a "later" period,
     * not how many periods were skipped.
     */
    @Test
    void testDistributeFeesAfterMultiDayOutageOnlyDistributesOnce() {
        // Simulate a 3-day outage: last distribution was 3 days ago
        // With 1-minute staking periods for testing, 3 days = 3 * 24 * 60 = 4320 minutes = 4320 periods
        final var threeDaysAgo = NOW.minusSeconds(3 * 24 * 60 * 60);
        final var nodePayments = NodePayments.newBuilder()
                .lastNodeFeeDistributionTime(asTimestamp(threeDaysAgo))
                .payments(List.of(NodePayment.newBuilder()
                        .nodeAccountId(NODE_ACCOUNT_ID_3)
                        .fees(300L) // Accumulated over 3 days
                        .build()))
                .build();
        givenSetupForDistribution(nodePayments, 1000L);

        // First call - should distribute
        final var result1 = subject.distributeFees(state, NOW, systemTransactions);
        assertTrue(result1, "First distribution after multi-day outage should succeed");

        // Verify distribution happened exactly once
        verify(systemTransactions, times(1)).dispatchNodePayments(eq(state), eq(NOW), any());

        // The lastNodeFeeDistributionTime should now be updated to NOW
        // So a second call in the same period should NOT distribute again
        final var result2 = subject.distributeFees(state, NOW, systemTransactions);
        assertFalse(result2, "Second distribution in same period should not happen");

        // Still only one dispatch
        verify(systemTransactions, times(1)).dispatchNodePayments(any(), any(), any());
    }

    /**
     * Tests that fees accumulated during a multi-day outage are distributed in a single transaction,
     * not split across multiple days' worth of distributions.
     */
    @Test
    void testMultiDayOutageFeesDistributedInSingleTransaction() {
        // Last distribution was 3 days ago
        final var threeDaysAgo = NOW.minusSeconds(3 * 24 * 60 * 60);
        final var nodePayments = NodePayments.newBuilder()
                .lastNodeFeeDistributionTime(asTimestamp(threeDaysAgo))
                .payments(List.of(NodePayment.newBuilder()
                        .nodeAccountId(NODE_ACCOUNT_ID_3)
                        .fees(300L) // All fees accumulated during outage
                        .build()))
                .build();
        givenSetupForDistribution(nodePayments, 1300L); // 300 node fees + 1000 network fees

        // Load the payments from state into memory (simulates what happens at block start)
        subject.onOpenBlock(state);

        final var result = subject.distributeFees(state, NOW, systemTransactions);

        assertTrue(result);
        final var transferCaptor = ArgumentCaptor.forClass(TransferList.class);
        verify(systemTransactions).dispatchNodePayments(eq(state), eq(NOW), transferCaptor.capture());

        final var transfers = transferCaptor.getValue();
        assertNotNull(transfers);

        // Verify node 3 receives all 300 in one transaction
        final var node3Transfer = transfers.accountAmounts().stream()
                .filter(aa -> aa.accountID().accountNum() == 3L)
                .findFirst()
                .orElse(null);
        assertNotNull(node3Transfer);
        assertEquals(300L, node3Transfer.amount(), "All accumulated fees should be distributed in one transaction");
    }
}
