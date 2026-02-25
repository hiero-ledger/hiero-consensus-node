// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle.record;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

import static com.hedera.node.app.service.file.impl.schemas.V0490FileSchema.FILES_STATE_ID;

import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.FileID;
import com.hedera.hapi.node.base.TransferList;
import com.hedera.hapi.node.state.file.File;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.node.app.blocks.BlockStreamManager;
import com.hedera.node.app.fees.ExchangeRateManager;
import com.hedera.node.app.records.BlockRecordManager;
import com.hedera.node.app.service.entityid.EntityIdFactory;
import com.hedera.node.app.service.file.FileService;
import com.hedera.node.app.service.file.impl.FileServiceImpl;
import com.hedera.node.app.service.file.impl.schemas.V0490FileSchema;
import com.hedera.node.app.services.ServicesRegistry;
import com.hedera.node.app.spi.AppContext;
import com.hedera.node.app.spi.info.NetworkInfo;
import com.hedera.node.app.spi.info.NodeInfo;
import com.hedera.node.app.spi.migrate.StartupNetworks;
import com.hedera.node.app.spi.records.SelfNodeAccountIdManager;
import com.hedera.node.app.state.HederaRecordCache;
import com.hedera.node.app.workflows.handle.DispatchProcessor;
import com.hedera.node.app.workflows.handle.steps.ParentTxnFactory;
import com.hedera.node.app.workflows.handle.steps.StakePeriodChanges;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.platform.system.InitTrigger;
import com.swirlds.state.State;
import com.swirlds.state.spi.ReadableKVState;
import com.swirlds.state.spi.ReadableStates;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SystemTransactionsTest {
    private static final Instant NOW = Instant.ofEpochSecond(1234567L);
    private static final AccountID NODE_ACCOUNT_ID =
            AccountID.newBuilder().accountNum(3L).build();

    @Mock(strictness = Mock.Strictness.LENIENT)
    private InitTrigger initTrigger;

    @Mock
    private ParentTxnFactory parentTxnFactory;

    @Mock
    private ServicesRegistry servicesRegistry;

    @Mock
    private FileServiceImpl fileService;

    @Mock(strictness = Mock.Strictness.LENIENT)
    private NetworkInfo networkInfo;

    @Mock(strictness = Mock.Strictness.LENIENT)
    private ConfigProvider configProvider;

    @Mock
    private DispatchProcessor dispatchProcessor;

    @Mock(strictness = Mock.Strictness.LENIENT)
    private AppContext appContext;

    @Mock
    private BlockRecordManager blockRecordManager;

    @Mock
    private BlockStreamManager blockStreamManager;

    @Mock
    private ExchangeRateManager exchangeRateManager;

    @Mock
    private HederaRecordCache recordCache;

    @Mock
    private StartupNetworks startupNetworks;

    @Mock
    private StakePeriodChanges stakePeriodChanges;

    @Mock(strictness = Mock.Strictness.LENIENT)
    private EntityIdFactory entityIdFactory;

    @Mock
    private SelfNodeAccountIdManager selfNodeAccountIdManager;

    @Mock
    private State state;

    @Mock(strictness = Mock.Strictness.LENIENT)
    private NodeInfo creatorNodeInfo;

    private SystemTransactions subject;

    @BeforeEach
    void setUp() {
        final var config = HederaTestConfigBuilder.create()
                .withValue("blockStream.streamMode", "BLOCKS")
                .withValue("consensus.handleMaxPrecedingRecords", 3)
                .withValue("scheduling.reservedSystemTxnNanos", 1000)
                .withValue("hedera.firstUserEntity", 1001)
                .withValue("hedera.transactionMaxValidDuration", 180)
                .withValue("accounts.systemAdmin", 50)
                .getOrCreateConfig();
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(config, 1));
        given(appContext.idFactory()).willReturn(entityIdFactory);
        given(initTrigger.name()).willReturn("EVENT_STREAM_RECOVERY");

        // Set up creator node info for address book
        given(creatorNodeInfo.nodeId()).willReturn(0L);
        given(creatorNodeInfo.accountId()).willReturn(NODE_ACCOUNT_ID);
        given(creatorNodeInfo.sigCertBytes()).willReturn(Bytes.EMPTY);
        given(networkInfo.addressBook()).willReturn(List.of(creatorNodeInfo));

        subject = new SystemTransactions(
                initTrigger,
                parentTxnFactory,
                fileService,
                networkInfo,
                configProvider,
                dispatchProcessor,
                appContext,
                servicesRegistry,
                blockRecordManager,
                blockStreamManager,
                exchangeRateManager,
                recordCache,
                startupNetworks,
                stakePeriodChanges,
                selfNodeAccountIdManager);
    }

    @Test
    void testResetNextDispatchNonce() {
        // The nonce starts at 1 and should reset to 1
        subject.resetNextDispatchNonce();
        // No exception means success - the nonce is private so we can't directly verify
        // but we can verify the method doesn't throw
        assertDoesNotThrow(() -> subject.resetNextDispatchNonce());
    }

    @Test
    void testFirstReservedSystemTimeForNonGenesis() {
        // For non-genesis, the calculation should be:
        // firstEventTime - 1ns - maxPrecedingRecords - reservedSystemTxnNanos
        // = NOW - 1 - 3 - 1000 = NOW - 1004 nanos
        final var result = subject.firstReservedSystemTimeFor(NOW);

        assertNotNull(result);
        assertTrue(result.isBefore(NOW));
        // Should be NOW minus (1 + 3 + 1000) = 1004 nanos
        assertEquals(NOW.minusNanos(1004), result);
    }

    @Test
    void testFirstReservedSystemTimeForGenesis() {
        // For genesis, we need to also subtract firstUserEntity
        given(initTrigger.name()).willReturn("GENESIS");

        final var config = HederaTestConfigBuilder.create()
                .withValue("blockStream.streamMode", "BLOCKS")
                .withValue("consensus.handleMaxPrecedingRecords", 3)
                .withValue("scheduling.reservedSystemTxnNanos", 1000)
                .withValue("hedera.firstUserEntity", 1001)
                .withValue("hedera.transactionMaxValidDuration", 180)
                .withValue("accounts.systemAdmin", 50)
                .getOrCreateConfig();
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(config, 1));

        // Recreate subject with GENESIS trigger
        subject = new SystemTransactions(
                InitTrigger.GENESIS,
                parentTxnFactory,
                fileService,
                networkInfo,
                configProvider,
                dispatchProcessor,
                appContext,
                servicesRegistry,
                blockRecordManager,
                blockStreamManager,
                exchangeRateManager,
                recordCache,
                startupNetworks,
                stakePeriodChanges,
                selfNodeAccountIdManager);

        final var result = subject.firstReservedSystemTimeFor(NOW);

        assertNotNull(result);
        assertTrue(result.isBefore(NOW));
        // Should be NOW minus (1 + 3 + 1000 + 1001) = 2005 nanos
        assertEquals(NOW.minusNanos(2005), result);
    }

    @Test
    void testDispatchNodePaymentsWithEmptyTransfers() {
        final var emptyTransfers = TransferList.newBuilder().build();

        subject.dispatchNodePayments(state, NOW, emptyTransfers);

        // Should not dispatch anything when transfers are empty
        verifyNoInteractions(parentTxnFactory);
        verifyNoInteractions(dispatchProcessor);
    }

    @Test
    void testDispatchNodePaymentsWithNullState() {
        final var transfers = TransferList.newBuilder()
                .accountAmounts(List.of(AccountAmount.newBuilder()
                        .accountID(AccountID.newBuilder().accountNum(3L).build())
                        .amount(100L)
                        .build()))
                .build();

        assertThrows(NullPointerException.class, () -> subject.dispatchNodePayments(null, NOW, transfers));
    }

    @Test
    void testDispatchNodePaymentsWithNullNow() {
        final var transfers = TransferList.newBuilder()
                .accountAmounts(List.of(AccountAmount.newBuilder()
                        .accountID(AccountID.newBuilder().accountNum(3L).build())
                        .amount(100L)
                        .build()))
                .build();

        assertThrows(NullPointerException.class, () -> subject.dispatchNodePayments(state, null, transfers));
    }

    @Test
    void testDispatchNodePaymentsWithNullTransfers() {
        assertThrows(NullPointerException.class, () -> subject.dispatchNodePayments(state, NOW, null));
    }

    @Test
    void testDispatchNodeRewardsWithEmptyActiveNodes() {
        final var nodeRewardsAccountId = AccountID.newBuilder().accountNum(801L).build();
        final var rosterEntries = List.of(
                RosterEntry.newBuilder().nodeId(0L).build(),
                RosterEntry.newBuilder().nodeId(1L).build());

        // All nodes are inactive (not in activeNodeIds)
        // And minNodeReward is 0, so no rewards should be dispatched
        subject.dispatchNodeRewards(state, NOW, List.of(), 100L, nodeRewardsAccountId, 1000L, 0L, rosterEntries);

        // Should not dispatch anything when no active nodes and minNodeReward is 0
        verifyNoInteractions(parentTxnFactory);
        verifyNoInteractions(dispatchProcessor);
    }

    @Test
    void testDispatchNodeRewardsWithNullState() {
        final var nodeRewardsAccountId = AccountID.newBuilder().accountNum(801L).build();

        assertThrows(
                NullPointerException.class,
                () -> subject.dispatchNodeRewards(
                        null, NOW, List.of(0L), 100L, nodeRewardsAccountId, 1000L, 0L, List.of()));
    }

    @Test
    void testDispatchNodeRewardsWithNullNow() {
        final var nodeRewardsAccountId = AccountID.newBuilder().accountNum(801L).build();

        assertThrows(
                NullPointerException.class,
                () -> subject.dispatchNodeRewards(
                        state, null, List.of(0L), 100L, nodeRewardsAccountId, 1000L, 0L, List.of()));
    }

    @Test
    void testDispatchNodeRewardsWithNullActiveNodeIds() {
        final var nodeRewardsAccountId = AccountID.newBuilder().accountNum(801L).build();

        assertThrows(
                NullPointerException.class,
                () -> subject.dispatchNodeRewards(state, NOW, null, 100L, nodeRewardsAccountId, 1000L, 0L, List.of()));
    }

    @Test
    void testDispatchNodeRewardsWithNullNodeRewardsAccountId() {
        assertThrows(
                NullPointerException.class,
                () -> subject.dispatchNodeRewards(state, NOW, List.of(0L), 100L, null, 1000L, 0L, List.of()));
    }

    @Test
    void testDispatchNodeRewardsWithActiveNodesButAllDeclineReward() {
        final var nodeRewardsAccountId = AccountID.newBuilder().accountNum(801L).build();
        final var rosterEntries = List.of(RosterEntry.newBuilder().nodeId(0L).build());

        // Mock node info that declines reward
        final var nodeInfo = mock(NodeInfo.class);
        given(nodeInfo.declineReward()).willReturn(true);
        given(networkInfo.nodeInfo(0L)).willReturn(nodeInfo);

        subject.dispatchNodeRewards(state, NOW, List.of(0L), 100L, nodeRewardsAccountId, 1000L, 0L, rosterEntries);

        // Should not dispatch anything when all active nodes decline reward
        verifyNoInteractions(parentTxnFactory);
        verifyNoInteractions(dispatchProcessor);
    }

    @Test
    void testDispatchNodeRewardsWithNullNodeInfo() {
        final var nodeRewardsAccountId = AccountID.newBuilder().accountNum(801L).build();
        final var rosterEntries = List.of(RosterEntry.newBuilder().nodeId(0L).build());

        // Mock null node info
        given(networkInfo.nodeInfo(0L)).willReturn(null);

        subject.dispatchNodeRewards(state, NOW, List.of(0L), 100L, nodeRewardsAccountId, 1000L, 0L, rosterEntries);

        // Should not dispatch anything when node info is null
        verifyNoInteractions(parentTxnFactory);
        verifyNoInteractions(dispatchProcessor);
    }

    @Test
    void testDispatchNodeRewardsWithNullRosterEntries() {
        final var nodeRewardsAccountId = AccountID.newBuilder().accountNum(801L).build();

        assertThrows(
                NullPointerException.class,
                () -> subject.dispatchNodeRewards(
                        state, NOW, List.of(0L), 100L, nodeRewardsAccountId, 1000L, 0L, null));
    }

    @Test
    void testDispatchNodeRewardsWithEmptyRosterEntriesAndEmptyActiveNodes() {
        final var nodeRewardsAccountId = AccountID.newBuilder().accountNum(801L).build();

        // Empty active nodes and empty roster entries - no rewards to distribute
        subject.dispatchNodeRewards(state, NOW, List.of(), 100L, nodeRewardsAccountId, 1000L, 0L, List.of());

        // Should not dispatch anything
        verifyNoInteractions(parentTxnFactory);
        verifyNoInteractions(dispatchProcessor);
    }

    @Test
    void testDispatchNodeRewardsWithInactiveNodesAndMinRewardZero() {
        final var nodeRewardsAccountId = AccountID.newBuilder().accountNum(801L).build();
        // Node 1 is in roster but not in active list
        final var rosterEntries = List.of(RosterEntry.newBuilder().nodeId(1L).build());

        // Mock node info for inactive node
        final var inactiveNodeInfo = mock(NodeInfo.class);
        given(inactiveNodeInfo.declineReward()).willReturn(false);
        given(inactiveNodeInfo.accountId())
                .willReturn(AccountID.newBuilder().accountNum(4L).build());
        given(networkInfo.nodeInfo(1L)).willReturn(inactiveNodeInfo);

        // minNodeReward is 0, so inactive nodes should not receive rewards
        subject.dispatchNodeRewards(state, NOW, List.of(), 100L, nodeRewardsAccountId, 1000L, 0L, rosterEntries);

        // Should not dispatch anything when no active nodes and minNodeReward is 0
        verifyNoInteractions(parentTxnFactory);
        verifyNoInteractions(dispatchProcessor);
    }

    @Test
    void testFirstReservedSystemTimeForWithZeroReservedNanos() {
        // Create config with 0 reserved nanos
        final var config = HederaTestConfigBuilder.create()
                .withValue("blockStream.streamMode", "BLOCKS")
                .withValue("consensus.handleMaxPrecedingRecords", 3)
                .withValue("scheduling.reservedSystemTxnNanos", 0)
                .withValue("hedera.firstUserEntity", 1001)
                .withValue("hedera.transactionMaxValidDuration", 180)
                .withValue("accounts.systemAdmin", 50)
                .getOrCreateConfig();
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(config, 1));

        // Recreate subject
        subject = new SystemTransactions(
                initTrigger,
                parentTxnFactory,
                fileService,
                networkInfo,
                configProvider,
                dispatchProcessor,
                appContext,
                servicesRegistry,
                blockRecordManager,
                blockStreamManager,
                exchangeRateManager,
                recordCache,
                startupNetworks,
                stakePeriodChanges,
                selfNodeAccountIdManager);

        final var result = subject.firstReservedSystemTimeFor(NOW);

        assertNotNull(result);
        // Should be NOW minus (1 + 3 + 0) = 4 nanos
        assertEquals(NOW.minusNanos(4), result);
    }

    @Test
    void testFirstReservedSystemTimeForWithLargeReservedNanos() {
        // Create config with large reserved nanos
        final var config = HederaTestConfigBuilder.create()
                .withValue("blockStream.streamMode", "BLOCKS")
                .withValue("consensus.handleMaxPrecedingRecords", 3)
                .withValue("scheduling.reservedSystemTxnNanos", 5000)
                .withValue("hedera.firstUserEntity", 1001)
                .withValue("hedera.transactionMaxValidDuration", 180)
                .withValue("accounts.systemAdmin", 50)
                .getOrCreateConfig();

        // Reset and reconfigure the mock
        reset(configProvider);
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(config, 1));

        // Recreate subject
        subject = new SystemTransactions(
                initTrigger,
                parentTxnFactory,
                fileService,
                networkInfo,
                configProvider,
                dispatchProcessor,
                appContext,
                servicesRegistry,
                blockRecordManager,
                blockStreamManager,
                exchangeRateManager,
                recordCache,
                startupNetworks,
                stakePeriodChanges,
                selfNodeAccountIdManager);

        final var result = subject.firstReservedSystemTimeFor(NOW);

        assertNotNull(result);
        // Should be NOW minus (1 + 3 + 5000) = 5004 nanos
        assertEquals(NOW.minusNanos(5004), result);
    }

    @Test
    void testResetNextDispatchNonceMultipleTimes() {
        // Reset multiple times should not throw
        assertDoesNotThrow(() -> {
            subject.resetNextDispatchNonce();
            subject.resetNextDispatchNonce();
            subject.resetNextDispatchNonce();
        });
    }

    @Test
    void testDispatchNodePaymentsWithNonEmptyTransfersButEmptyAccountAmounts() {
        // TransferList with empty accountAmounts list
        final var transfers =
                TransferList.newBuilder().accountAmounts(List.of()).build();

        subject.dispatchNodePayments(state, NOW, transfers);

        // Should not dispatch anything when account amounts are empty
        verifyNoInteractions(parentTxnFactory);
        verifyNoInteractions(dispatchProcessor);
    }

    @Test
    @SuppressWarnings("unchecked")
    void testPostUpgradeCreatesSimpleFeesFileWhenMissing() {
        // Reconfigure with fees.createSimpleFeeSchedule=true and nodes.enableDAB=false
        final var config = HederaTestConfigBuilder.create()
                .withValue("blockStream.streamMode", "BLOCKS")
                .withValue("consensus.handleMaxPrecedingRecords", 3)
                .withValue("scheduling.reservedSystemTxnNanos", 1000)
                .withValue("hedera.firstUserEntity", 1001)
                .withValue("hedera.transactionMaxValidDuration", 180)
                .withValue("accounts.systemAdmin", 50)
                .withValue("fees.createSimpleFeeSchedule", "true")
                .withValue("nodes.enableDAB", "false")
                .getOrCreateConfig();
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(config, 1));

        // Mock selfNodeInfo for setSelfNodeAccountId call
        final var selfNodeInfo = mock(NodeInfo.class);
        given(selfNodeInfo.accountId()).willReturn(NODE_ACCOUNT_ID);
        given(networkInfo.selfNodeInfo()).willReturn(selfNodeInfo);

        // Mock state to return empty files state (file 0.0.113 not present)
        final ReadableStates readableStates = mock(ReadableStates.class);
        final ReadableKVState<FileID, File> filesState = mock(ReadableKVState.class);
        given(state.getReadableStates(FileService.NAME)).willReturn(readableStates);
        given(readableStates.<FileID, File>get(FILES_STATE_ID)).willReturn(filesState);
        given(filesState.get(any())).willReturn(null);

        // Mock fileService.fileSchema() to return a mock schema
        final var fileSchema = mock(V0490FileSchema.class);
        given(fileService.fileSchema()).willReturn(fileSchema);

        // Recreate subject with updated config
        subject = new SystemTransactions(
                initTrigger,
                parentTxnFactory,
                fileService,
                networkInfo,
                configProvider,
                dispatchProcessor,
                appContext,
                servicesRegistry,
                blockRecordManager,
                blockStreamManager,
                exchangeRateManager,
                recordCache,
                startupNetworks,
                stakePeriodChanges,
                selfNodeAccountIdManager);

        subject.doPostUpgradeSetup(NOW, state);

        // Verify createGenesisSimpleFeesSchedule was called since file was missing
        verify(fileSchema).createGenesisSimpleFeesSchedule(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testPostUpgradeSkipsSimpleFeesFileCreationWhenPresent() {
        // Reconfigure with fees.createSimpleFeeSchedule=true and nodes.enableDAB=false
        final var config = HederaTestConfigBuilder.create()
                .withValue("blockStream.streamMode", "BLOCKS")
                .withValue("consensus.handleMaxPrecedingRecords", 3)
                .withValue("scheduling.reservedSystemTxnNanos", 1000)
                .withValue("hedera.firstUserEntity", 1001)
                .withValue("hedera.transactionMaxValidDuration", 180)
                .withValue("accounts.systemAdmin", 50)
                .withValue("fees.createSimpleFeeSchedule", "true")
                .withValue("nodes.enableDAB", "false")
                .getOrCreateConfig();
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(config, 1));

        // Mock selfNodeInfo for setSelfNodeAccountId call
        final var selfNodeInfo = mock(NodeInfo.class);
        given(selfNodeInfo.accountId()).willReturn(NODE_ACCOUNT_ID);
        given(networkInfo.selfNodeInfo()).willReturn(selfNodeInfo);

        // Mock state to return files state WITH file 0.0.113 present
        final ReadableStates readableStates = mock(ReadableStates.class);
        final ReadableKVState<FileID, File> filesState = mock(ReadableKVState.class);
        given(state.getReadableStates(FileService.NAME)).willReturn(readableStates);
        given(readableStates.<FileID, File>get(FILES_STATE_ID)).willReturn(filesState);
        given(filesState.get(any())).willReturn(File.DEFAULT);

        // Recreate subject with updated config
        subject = new SystemTransactions(
                initTrigger,
                parentTxnFactory,
                fileService,
                networkInfo,
                configProvider,
                dispatchProcessor,
                appContext,
                servicesRegistry,
                blockRecordManager,
                blockStreamManager,
                exchangeRateManager,
                recordCache,
                startupNetworks,
                stakePeriodChanges,
                selfNodeAccountIdManager);

        subject.doPostUpgradeSetup(NOW, state);

        // Verify fileSchema() was never accessed since file already exists
        verify(fileService, never()).fileSchema();
    }
}
