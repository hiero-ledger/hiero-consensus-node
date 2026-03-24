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
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.FileID;
import com.hedera.hapi.node.base.TransferList;
import com.hedera.hapi.node.state.blockrecords.BlockInfo;
import com.hedera.hapi.node.state.blockrecords.NodeMigrationRootHashVote;
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
import com.hedera.node.app.service.token.NodeRewardAmounts;
import com.hedera.node.app.service.token.NodeRewardGroups;
import com.hedera.node.app.services.ServicesRegistry;
import com.hedera.node.app.spi.AppContext;
import com.hedera.node.app.spi.info.NetworkInfo;
import com.hedera.node.app.spi.info.NodeInfo;
import com.hedera.node.app.spi.migrate.StartupNetworks;
import com.hedera.node.app.spi.records.SelfNodeAccountIdManager;
import com.hedera.node.app.spi.workflows.SystemContext;
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
    private static final AccountID PAYER_ID =
            AccountID.newBuilder().accountNum(800L).build();

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
    private MigrationRootHashSubmissions migrationRootHashSubmissions;

    @Mock
    private State state;

    @Mock(strictness = Mock.Strictness.LENIENT)
    private NodeInfo creatorNodeInfo;

    @Mock
    private SystemTransactions.StateChangeStreaming stateChangeStreaming;

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
                wrappedRecordBlockHashMigration,
                migrationRootHashSubmissions);
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
                wrappedRecordBlockHashMigration,
                migrationRootHashSubmissions);

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
    void testDispatchNodeRewardsWithEmptyAmounts() {
        final var rewardAmounts = new NodeRewardAmounts(PAYER_ID);

        subject.dispatchNodeRewards(state, NOW, rewardAmounts);

        // Should not dispatch anything when rewardAmounts is empty
        verifyNoInteractions(parentTxnFactory);
        verifyNoInteractions(dispatchProcessor);
    }

    @Test
    void testDispatchNodeRewardsWithNullState() {
        final var rewardAmounts = new NodeRewardAmounts(PAYER_ID);

        assertThrows(NullPointerException.class, () -> subject.dispatchNodeRewards(null, NOW, rewardAmounts));
    }

    @Test
    void testDispatchNodeRewardsWithNullNow() {
        final var rewardAmounts = new NodeRewardAmounts(PAYER_ID);

        assertThrows(NullPointerException.class, () -> subject.dispatchNodeRewards(state, null, rewardAmounts));
    }

    @Test
    void testDispatchNodeRewardsWithNullRewardAmounts() {
        assertThrows(NullPointerException.class, () -> subject.dispatchNodeRewards(state, NOW, null));
    }

    @Test
    void testDispatchNodeRewardsSuccess() {
        final var rewardAmounts = new NodeRewardAmounts(PAYER_ID);
        rewardAmounts.addConsensusNodeReward(
                3L, AccountID.newBuilder().accountNum(3L).build(), 100L);

        final var mockSystemContext = mock(SystemContext.class);
        final var spySubject = spy(subject);
        doReturn(mockSystemContext).when(spySubject).newSystemContext(any(), any(), any(), any(), any());

        spySubject.dispatchNodeRewards(state, NOW, rewardAmounts);

        verify(mockSystemContext).dispatchAdmin(any());
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
                wrappedRecordBlockHashMigration,
                migrationRootHashSubmissions);

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
                wrappedRecordBlockHashMigration,
                migrationRootHashSubmissions);

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
                wrappedRecordBlockHashMigration,
                migrationRootHashSubmissions);

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
                wrappedRecordBlockHashMigration,
                migrationRootHashSubmissions);

        subject.doPostUpgradeSetup(NOW, state);

        // Verify fileSchema() was never accessed since file already exists
        verify(fileService, never()).fileSchema();
    }

    @Test
    void postUpgradeSetupDefersArchivingJumpstartFileWhenMigrationResultPresent(@TempDir Path tempDir)
            throws IOException {
        final var config = HederaTestConfigBuilder.create()
                .withValue("blockStream.streamMode", "BLOCKS")
                .withValue("consensus.handleMaxPrecedingRecords", 3)
                .withValue("scheduling.reservedSystemTxnNanos", 1000)
                .withValue("hedera.firstUserEntity", 1001)
                .withValue("hedera.transactionMaxValidDuration", 180)
                .withValue("accounts.systemAdmin", 50)
                .withValue("nodes.enableDAB", false)
                .withValue("hedera.recordStream.liveWritePrevWrappedRecordHashes", true)
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

        // Set up block info mock (values match migration so no update needed)
        @SuppressWarnings("unchecked")
        final WritableSingletonStateBase<BlockInfo> blockInfoSingleton = mock(WritableSingletonStateBase.class);
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
                wrappedRecordBlockHashMigration,
                migrationRootHashSubmissions);

        subject.doPostUpgradeSetup(NOW, state);

        assertTrue(Files.exists(jumpstartFile), "Jumpstart file should remain until vote is observed in state");
        assertFalse(
                Files.exists(tempDir.resolve("archived_jumpstart.bin")),
                "Archived jumpstart file should not exist yet");
        verify(blockInfoSingleton).put(any());
        verify(blockInfoSingleton).commit();
        verify(migrationRootHashSubmissions, never()).submitStartupVoteIfActive(any());
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
                .withValue("hedera.recordStream.liveWritePrevWrappedRecordHashes", true)
                .getOrCreateConfig();
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(config, 1));
        given(networkInfo.selfNodeInfo()).willReturn(creatorNodeInfo);
        given(entityIdFactory.newAccountId(anyLong())).willReturn(NODE_ACCOUNT_ID);
        final ReadableStates readableStates = mock(ReadableStates.class);
        final ReadableKVState<FileID, File> filesState = mock(ReadableKVState.class);
        given(state.getReadableStates(FileService.NAME)).willReturn(readableStates);
        given(readableStates.<FileID, File>get(FILES_STATE_ID)).willReturn(filesState);
        given(filesState.get(any())).willReturn(File.DEFAULT);
        final WritableStates blockRecordStates = mock(WritableStates.class);
        @SuppressWarnings("unchecked")
        final WritableSingletonStateBase<BlockInfo> blockInfoSingleton = mock(WritableSingletonStateBase.class);
        given(state.getWritableStates(BlockRecordService.NAME)).willReturn(blockRecordStates);
        given(blockRecordStates.<BlockInfo>getSingleton(BLOCKS_STATE_ID)).willReturn(blockInfoSingleton);
        given(blockInfoSingleton.get()).willReturn(BlockInfo.DEFAULT);

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
                wrappedRecordBlockHashMigration,
                migrationRootHashSubmissions);

        subject.doPostUpgradeSetup(NOW, state);

        verify(blockInfoSingleton).put(any());
        verify(blockInfoSingleton).commit();
        // jumpstartFilePath() should never be called when result is null
        verify(wrappedRecordBlockHashMigration, never()).jumpstartFilePath();
        verify(migrationRootHashSubmissions, never()).submitStartupVoteIfActive(any());
    }

    @Test
    void postUpgradeSetupOverwritesBlockInfo() {
        final var config = HederaTestConfigBuilder.create()
                .withValue("blockStream.streamMode", "BLOCKS")
                .withValue("consensus.handleMaxPrecedingRecords", 3)
                .withValue("scheduling.reservedSystemTxnNanos", 1000)
                .withValue("hedera.firstUserEntity", 1001)
                .withValue("hedera.transactionMaxValidDuration", 180)
                .withValue("accounts.systemAdmin", 50)
                .withValue("nodes.enableDAB", false)
                .withValue("hedera.recordStream.liveWritePrevWrappedRecordHashes", true)
                .getOrCreateConfig();
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(config, 1));
        given(networkInfo.selfNodeInfo()).willReturn(creatorNodeInfo);
        given(entityIdFactory.newAccountId(anyLong())).willReturn(NODE_ACCOUNT_ID);
        final ReadableStates readableStates = mock(ReadableStates.class);
        final ReadableKVState<FileID, File> filesState = mock(ReadableKVState.class);
        given(state.getReadableStates(FileService.NAME)).willReturn(readableStates);
        given(readableStates.<FileID, File>get(FILES_STATE_ID)).willReturn(filesState);
        given(filesState.get(any())).willReturn(File.DEFAULT);

        // Migration result with specific values
        final var migrationRootHash = Bytes.wrap(new byte[] {1, 2, 3});
        final var migrationIntermediateHashes = List.of(Bytes.wrap(new byte[] {4, 5, 6}));
        final long migrationLeafCount = 42L;
        given(wrappedRecordBlockHashMigration.result())
                .willReturn(new WrappedRecordBlockHashMigration.Result(
                        Bytes.EMPTY, migrationRootHash, migrationIntermediateHashes, migrationLeafCount));

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
                wrappedRecordBlockHashMigration,
                migrationRootHashSubmissions);

        subject.doPostUpgradeSetup(NOW, state);

        // Post-upgrade setup initializes voting metadata on first upgrade.
        verify(blockInfoSingleton).put(any());
        verify(blockInfoSingleton).commit();
        verify(migrationRootHashSubmissions, never()).submitStartupVoteIfActive(any());
    }

    @Test
    void maybeSubmitStartupMigrationVoteSubmitsWhenSelfVoteAbsent() {
        final var migrationResult = new WrappedRecordBlockHashMigration.Result(
                Bytes.wrap(new byte[] {9}), Bytes.wrap(new byte[] {1}), List.of(Bytes.wrap(new byte[] {2})), 3L);
        given(wrappedRecordBlockHashMigration.result()).willReturn(migrationResult);
        given(networkInfo.selfNodeInfo()).willReturn(creatorNodeInfo);
        given(creatorNodeInfo.nodeId()).willReturn(0L);

        final WritableStates blockRecordStates = mock(WritableStates.class);
        @SuppressWarnings("unchecked")
        final WritableSingletonState<BlockInfo> blockInfoSingleton = mock(WritableSingletonState.class);
        given(state.getWritableStates(BlockRecordService.NAME)).willReturn(blockRecordStates);
        given(blockRecordStates.<BlockInfo>getSingleton(BLOCKS_STATE_ID)).willReturn(blockInfoSingleton);
        given(blockInfoSingleton.get())
                .willReturn(BlockInfo.newBuilder().votingComplete(false).build());

        given(migrationRootHashSubmissions.submitStartupVoteIfActive(any())).willReturn(true);
        subject.maybeSubmitStartupMigrationRootHashVote(state);
        subject.maybeSubmitStartupMigrationRootHashVote(state);

        verify(migrationRootHashSubmissions, times(1)).submitStartupVoteIfActive(any());
    }

    @Test
    void maybeSubmitStartupMigrationVoteSkipsWhenSelfVotePresent() throws IOException {
        final var migrationResult = new WrappedRecordBlockHashMigration.Result(
                Bytes.wrap(new byte[] {9}), Bytes.wrap(new byte[] {1}), List.of(Bytes.wrap(new byte[] {2})), 3L);
        given(wrappedRecordBlockHashMigration.result()).willReturn(migrationResult);
        given(networkInfo.selfNodeInfo()).willReturn(creatorNodeInfo);
        given(creatorNodeInfo.nodeId()).willReturn(0L);
        final var jumpstartFile = Files.createTempFile("jumpstart-", ".bin");
        given(wrappedRecordBlockHashMigration.jumpstartFilePath()).willReturn(jumpstartFile);

        final WritableStates blockRecordStates = mock(WritableStates.class);
        @SuppressWarnings("unchecked")
        final WritableSingletonState<BlockInfo> blockInfoSingleton = mock(WritableSingletonState.class);
        given(state.getWritableStates(BlockRecordService.NAME)).willReturn(blockRecordStates);
        given(blockRecordStates.<BlockInfo>getSingleton(BLOCKS_STATE_ID)).willReturn(blockInfoSingleton);
        given(blockInfoSingleton.get())
                .willReturn(BlockInfo.newBuilder()
                        .votingComplete(false)
                        .migrationRootHashVotes(List.of(NodeMigrationRootHashVote.newBuilder()
                                .nodeId(new com.hedera.hapi.platform.state.NodeId(0L))
                                .vote(com.hedera.hapi.services.auxiliary.blockrecords
                                        .MigrationRootHashVoteTransactionBody.newBuilder()
                                        .build())
                                .build()))
                        .build());

        subject.maybeSubmitStartupMigrationRootHashVote(state);
        subject.maybeSubmitStartupMigrationRootHashVote(state);

        verify(migrationRootHashSubmissions, never()).submitStartupVoteIfActive(any());
        verify(wrappedRecordBlockHashMigration, times(1)).jumpstartFilePath();
        assertFalse(Files.exists(jumpstartFile));
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

    @Test
    void currentBlockNumberUsesRecordBlockNumberInRecordsMode() throws Exception {
        final var config = HederaTestConfigBuilder.create()
                .withValue("blockStream.streamMode", "RECORDS")
                .withValue("consensus.handleMaxPrecedingRecords", 3)
                .withValue("scheduling.reservedSystemTxnNanos", 1000)
                .withValue("hedera.firstUserEntity", 1001)
                .withValue("hedera.transactionMaxValidDuration", 180)
                .withValue("accounts.systemAdmin", 50)
                .getOrCreateConfig();
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(config, 1));
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
                wrappedRecordBlockHashMigration,
                migrationRootHashSubmissions);
        given(blockRecordManager.blockNo()).willReturn(123L);

        final var method = SystemTransactions.class.getDeclaredMethod("currentBlockNumber");
        method.setAccessible(true);

        assertEquals(123L, method.invoke(subject));
        verify(blockStreamManager, never()).blockNo();
        verify(blockRecordManager).blockNo();
    }

    @Test
    void currentBlockNumberUsesBlockStreamNumberOutsideRecordsMode() throws Exception {
        given(blockStreamManager.blockNo()).willReturn(123L);

        final var method = SystemTransactions.class.getDeclaredMethod("currentBlockNumber");
        method.setAccessible(true);

        assertEquals(123L, method.invoke(subject));
        verify(blockStreamManager).blockNo();
        verify(blockRecordManager, never()).blockNo();
    }
}
