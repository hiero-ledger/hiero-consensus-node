// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle.record;

import static com.hedera.node.app.records.schemas.V0490BlockRecordSchema.BLOCKS_STATE_ID;
import static com.hedera.node.app.service.file.impl.schemas.V0490FileSchema.FILES_STATE_ID;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.FileID;
import com.hedera.hapi.node.base.TransferList;
import com.hedera.hapi.node.state.blockrecords.BlockInfo;
import com.hedera.hapi.node.state.file.File;
import com.hedera.node.app.blocks.BlockStreamManager;
import com.hedera.node.app.fees.ExchangeRateManager;
import com.hedera.node.app.records.BlockRecordManager;
import com.hedera.node.app.records.BlockRecordService;
import com.hedera.node.app.records.impl.WrappedRecordBlockHashMigration;
import com.hedera.node.app.service.entityid.EntityIdFactory;
import com.hedera.node.app.service.file.FileService;
import com.hedera.node.app.service.file.impl.FileServiceImpl;
import com.hedera.node.app.service.file.impl.schemas.V0490FileSchema;
import com.hedera.node.app.service.token.NodeRewardActivity;
import com.hedera.node.app.service.token.NodeRewardGroups;
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
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.spi.WritableSingletonStateBase;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
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
    private WrappedRecordBlockHashMigration wrappedRecordBlockHashMigration;

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
                selfNodeAccountIdManager,
                wrappedRecordBlockHashMigration);
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
                selfNodeAccountIdManager,
                wrappedRecordBlockHashMigration);

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

        // All nodes are inactive (not in activeNodeIds)
        // And minNodeReward is 0, so no rewards should be dispatched
        final var nodeGroups = nodeRewardGroups(
                List.of(),
                List.of(
                        AccountID.newBuilder().accountNum(0L).build(),
                        AccountID.newBuilder().accountNum(1L).build()));

        subject.dispatchNodeRewards(state, NOW, nodeGroups, 100L, nodeRewardsAccountId, 1000L, 0L);

        // Should not dispatch anything when no active nodes and minNodeReward is 0
        verifyNoInteractions(parentTxnFactory);
        verifyNoInteractions(dispatchProcessor);
    }

    @Test
    void testDispatchNodeRewardsWithNullState() {
        final var nodeRewardsAccountId = AccountID.newBuilder().accountNum(801L).build();

        assertThrows(
                NullPointerException.class,
                () -> subject.dispatchNodeRewards(null, NOW, emptyNodeGroups(), 100L, nodeRewardsAccountId, 1000L, 0L));
    }

    @Test
    void testDispatchNodeRewardsWithNullNow() {
        final var nodeRewardsAccountId = AccountID.newBuilder().accountNum(801L).build();

        assertThrows(
                NullPointerException.class,
                () -> subject.dispatchNodeRewards(
                        state, null, emptyNodeGroups(), 100L, nodeRewardsAccountId, 1000L, 0L));
    }

    @Test
    void testDispatchNodeRewardsWithNullNodeGroups() {
        final var nodeRewardsAccountId = AccountID.newBuilder().accountNum(801L).build();

        assertThrows(
                NullPointerException.class,
                () -> subject.dispatchNodeRewards(state, NOW, null, 100L, nodeRewardsAccountId, 1000L, 0L));
    }

    @Test
    void testDispatchNodeRewardsWithNullNodeRewardsAccountId() {
        assertThrows(
                NullPointerException.class,
                () -> subject.dispatchNodeRewards(state, NOW, emptyNodeGroups(), 100L, null, 1000L, 0L));
    }

    @Test
    void testDispatchNodeRewardsWithActiveNodesButAllDeclineReward() {
        final var nodeRewardsAccountId = AccountID.newBuilder().accountNum(801L).build();

        final var nodeGroups = emptyNodeGroups();

        subject.dispatchNodeRewards(state, NOW, nodeGroups, 100L, nodeRewardsAccountId, 1000L, 0L);

        // Should not dispatch anything when all active nodes decline reward (meaning eligible sets are empty)
        verifyNoInteractions(parentTxnFactory);
        verifyNoInteractions(dispatchProcessor);
    }

    @Test
    void testDispatchNodeRewardsWithInactiveNodesAndMinRewardZero() {
        final var nodeRewardsAccountId = AccountID.newBuilder().accountNum(801L).build();

        final var nodeGroups = nodeRewardGroups(
                List.of(), List.of(AccountID.newBuilder().accountNum(4L).build()));

        // minNodeReward is 0, so inactive nodes should not receive rewards
        subject.dispatchNodeRewards(state, NOW, nodeGroups, 100L, nodeRewardsAccountId, 1000L, 0L);

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
                selfNodeAccountIdManager,
                wrappedRecordBlockHashMigration);

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
                selfNodeAccountIdManager,
                wrappedRecordBlockHashMigration);

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
                selfNodeAccountIdManager,
                wrappedRecordBlockHashMigration);

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
                selfNodeAccountIdManager,
                wrappedRecordBlockHashMigration);

        subject.doPostUpgradeSetup(NOW, state);

        // Verify fileSchema() was never accessed since file already exists
        verify(fileService, never()).fileSchema();
    }

    @Test
    void postUpgradeSetupArchivesJumpstartFileWhenMigrationResultPresent(@TempDir Path tempDir) throws IOException {
        final var config = HederaTestConfigBuilder.create()
                .withValue("blockStream.streamMode", "BLOCKS")
                .withValue("consensus.handleMaxPrecedingRecords", 3)
                .withValue("scheduling.reservedSystemTxnNanos", 1000)
                .withValue("hedera.firstUserEntity", 1001)
                .withValue("hedera.transactionMaxValidDuration", 180)
                .withValue("accounts.systemAdmin", 50)
                .withValue("nodes.enableDAB", false)
                .getOrCreateConfig();
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(config, 1));
        given(networkInfo.selfNodeInfo()).willReturn(creatorNodeInfo);
        given(entityIdFactory.newAccountId(anyLong())).willReturn(NODE_ACCOUNT_ID);
        final ReadableStates readableStates = mock(ReadableStates.class);
        final ReadableKVState<FileID, File> filesState = mock(ReadableKVState.class);
        given(state.getReadableStates(FileService.NAME)).willReturn(readableStates);
        given(readableStates.<FileID, File>get(FILES_STATE_ID)).willReturn(filesState);
        given(filesState.get(any())).willReturn(File.DEFAULT);

        // Create a real jumpstart file
        final var jumpstartFile = tempDir.resolve("jumpstart.bin");
        Files.writeString(jumpstartFile, "test");

        given(wrappedRecordBlockHashMigration.result())
                .willReturn(new WrappedRecordBlockHashMigration.Result(Bytes.EMPTY, Bytes.EMPTY, List.of(), 0));
        given(wrappedRecordBlockHashMigration.jumpstartFilePath()).willReturn(jumpstartFile);

        // Set up block info mock (values match migration so no update needed)
        @SuppressWarnings("unchecked")
        final WritableSingletonState<BlockInfo> blockInfoSingleton = mock(WritableSingletonState.class);
        given(blockInfoSingleton.get()).willReturn(BlockInfo.DEFAULT);
        final WritableStates writableStates = mock(WritableStates.class);
        given(state.getWritableStates(BlockRecordService.NAME)).willReturn(writableStates);
        given(writableStates.<BlockInfo>getSingleton(BLOCKS_STATE_ID)).willReturn(blockInfoSingleton);

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
                selfNodeAccountIdManager,
                wrappedRecordBlockHashMigration);

        subject.doPostUpgradeSetup(NOW, state);

        assertFalse(Files.exists(jumpstartFile), "Original jumpstart file should no longer exist");
        assertTrue(Files.exists(tempDir.resolve("archived_jumpstart.bin")), "Archived jumpstart file should exist");
        // Block info values match migration, so no update should have been made
        verify(blockInfoSingleton, never()).put(any());
    }

    @Test
    void postUpgradeSetupDoesNotArchiveWhenMigrationResultIsNull() {
        final var config = HederaTestConfigBuilder.create()
                .withValue("blockStream.streamMode", "BLOCKS")
                .withValue("consensus.handleMaxPrecedingRecords", 3)
                .withValue("scheduling.reservedSystemTxnNanos", 1000)
                .withValue("hedera.firstUserEntity", 1001)
                .withValue("hedera.transactionMaxValidDuration", 180)
                .withValue("accounts.systemAdmin", 50)
                .withValue("nodes.enableDAB", false)
                .getOrCreateConfig();
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(config, 1));
        given(networkInfo.selfNodeInfo()).willReturn(creatorNodeInfo);
        given(entityIdFactory.newAccountId(anyLong())).willReturn(NODE_ACCOUNT_ID);
        final ReadableStates readableStates = mock(ReadableStates.class);
        final ReadableKVState<FileID, File> filesState = mock(ReadableKVState.class);
        given(state.getReadableStates(FileService.NAME)).willReturn(readableStates);
        given(readableStates.<FileID, File>get(FILES_STATE_ID)).willReturn(filesState);
        given(filesState.get(any())).willReturn(File.DEFAULT);

        given(wrappedRecordBlockHashMigration.result()).willReturn(null);

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
                selfNodeAccountIdManager,
                wrappedRecordBlockHashMigration);

        subject.doPostUpgradeSetup(NOW, state);

        // jumpstartFilePath() should never be called when result is null
        verify(wrappedRecordBlockHashMigration, never()).jumpstartFilePath();
    }

    @Test
    void postUpgradeSetupOverwritesBlockInfoWhenMigrationDiffers(@TempDir Path tempDir) throws IOException {
        final var config = HederaTestConfigBuilder.create()
                .withValue("blockStream.streamMode", "BLOCKS")
                .withValue("consensus.handleMaxPrecedingRecords", 3)
                .withValue("scheduling.reservedSystemTxnNanos", 1000)
                .withValue("hedera.firstUserEntity", 1001)
                .withValue("hedera.transactionMaxValidDuration", 180)
                .withValue("accounts.systemAdmin", 50)
                .withValue("nodes.enableDAB", false)
                .getOrCreateConfig();
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(config, 1));
        given(networkInfo.selfNodeInfo()).willReturn(creatorNodeInfo);
        given(entityIdFactory.newAccountId(anyLong())).willReturn(NODE_ACCOUNT_ID);
        final ReadableStates readableStates = mock(ReadableStates.class);
        final ReadableKVState<FileID, File> filesState = mock(ReadableKVState.class);
        given(state.getReadableStates(FileService.NAME)).willReturn(readableStates);
        given(readableStates.<FileID, File>get(FILES_STATE_ID)).willReturn(filesState);
        given(filesState.get(any())).willReturn(File.DEFAULT);

        // Create a real jumpstart file
        final var jumpstartFile = tempDir.resolve("jumpstart.bin");
        Files.writeString(jumpstartFile, "test");

        // Migration result with specific values
        final var migrationRootHash = Bytes.wrap(new byte[] {1, 2, 3});
        final var migrationIntermediateHashes = List.of(Bytes.wrap(new byte[] {4, 5, 6}));
        final long migrationLeafCount = 42L;
        given(wrappedRecordBlockHashMigration.result())
                .willReturn(new WrappedRecordBlockHashMigration.Result(
                        Bytes.EMPTY, migrationRootHash, migrationIntermediateHashes, migrationLeafCount));
        given(wrappedRecordBlockHashMigration.jumpstartFilePath()).willReturn(jumpstartFile);

        // Set up BlockInfo in state with DIFFERENT values than the migration
        final var staleBlockInfo = BlockInfo.newBuilder()
                .previousWrappedRecordBlockRootHash(Bytes.wrap(new byte[] {9, 9, 9}))
                .wrappedIntermediatePreviousBlockRootHashes(List.of(Bytes.wrap(new byte[] {8, 8, 8})))
                .wrappedIntermediateBlockRootsLeafCount(99L)
                .build();

        @SuppressWarnings("unchecked")
        final WritableSingletonStateBase<BlockInfo> blockInfoSingleton = mock(WritableSingletonStateBase.class);
        given(blockInfoSingleton.get()).willReturn(staleBlockInfo);
        final WritableStates writableStates = mock(WritableStates.class);
        given(state.getWritableStates(BlockRecordService.NAME)).willReturn(writableStates);
        given(writableStates.<BlockInfo>getSingleton(BLOCKS_STATE_ID)).willReturn(blockInfoSingleton);

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
                selfNodeAccountIdManager,
                wrappedRecordBlockHashMigration);

        subject.doPostUpgradeSetup(NOW, state);

        // Verify that blockInfoSingleton.put() was called with updated values
        final var captor = org.mockito.ArgumentCaptor.forClass(BlockInfo.class);
        verify(blockInfoSingleton).put(captor.capture());
        final var updatedBlockInfo = captor.getValue();
        assertEquals(migrationRootHash, updatedBlockInfo.previousWrappedRecordBlockRootHash());
        assertEquals(migrationIntermediateHashes, updatedBlockInfo.wrappedIntermediatePreviousBlockRootHashes());
        assertEquals(migrationLeafCount, updatedBlockInfo.wrappedIntermediateBlockRootsLeafCount());
        // Verify commit() was called after put()
        verify(blockInfoSingleton).commit();
    }

    private static NodeRewardGroups nodeRewardGroups(List<AccountID> active, List<AccountID> inactive) {
        return new NodeRewardGroups(
                active.stream()
                        .map(id -> new NodeRewardActivity(id.accountNum(), id, 0, 100, 0))
                        .collect(Collectors.toList()),
                inactive.stream()
                        .map(id -> new NodeRewardActivity(id.accountNum(), id, 101, 100, 0))
                        .collect(Collectors.toList()));
    }

    private static @NonNull NodeRewardGroups emptyNodeGroups() {
        return nodeRewardGroups(List.of(), List.of());
    }
}
