// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle.steps;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CONFIG_FILE_PART_UPLOADED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.FEE_SCHEDULE_FILE_PART_UPLOADED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.file.impl.schemas.V0490FileSchema.FILES_STATE_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.FileID;
import com.hedera.hapi.node.base.ServicesConfigurationList;
import com.hedera.hapi.node.base.Setting;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.file.FileAppendTransactionBody;
import com.hedera.hapi.node.file.FileUpdateTransactionBody;
import com.hedera.hapi.node.state.file.File;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.hapi.node.transaction.ExchangeRate;
import com.hedera.hapi.node.transaction.ExchangeRateSet;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.blocks.impl.streaming.BlockNodeConnectionManager;
import com.hedera.node.app.config.ConfigProviderImpl;
import com.hedera.node.app.fees.ExchangeRateManager;
import com.hedera.node.app.fees.FeeManager;
import com.hedera.node.app.fees.FeeService;
import com.hedera.node.app.fees.schemas.V0490FeeSchema;
import com.hedera.node.app.fixtures.state.FakeState;
import com.hedera.node.app.service.entityid.EntityIdFactory;
import com.hedera.node.app.service.file.FileService;
import com.hedera.node.app.spi.fixtures.TransactionFactory;
import com.hedera.node.app.spi.fixtures.ids.FakeEntityIdFactoryImpl;
import com.hedera.node.app.throttle.ThrottleServiceManager;
import com.hedera.node.app.util.FileUtilities;
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.converter.BytesConverter;
import com.hedera.node.config.converter.LongPairConverter;
import com.hedera.node.config.data.BlockStreamConfig;
import com.hedera.node.config.data.FilesConfig;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.node.config.data.LedgerConfig;
import com.hedera.node.config.types.BlockStreamWriterMode;
import com.hedera.node.config.types.LongPair;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.Mock.Strictness;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SystemFileUpdatesTest implements TransactionFactory {

    private static final Bytes FILE_BYTES = Bytes.wrap("Hello World");
    private static final Bytes UNPARSEABLE_BYTES = Bytes.wrap("NOT_A_SERVICES_CONFIGURATION_LIST");
    private static final Bytes CONFIG_LIST_BYTES =
            ServicesConfigurationList.PROTOBUF.toBytes(ServicesConfigurationList.newBuilder()
                    .nameValue(Setting.newBuilder()
                            .name("tokens.maxPerAccount")
                            .value("1000")
                            .build())
                    .build());
    private static final Instant CONSENSUS_NOW = Instant.parse("2000-01-01T00:00:00Z");
    private long SHARD;
    private long REALM;
    private EntityIdFactory idFactory;

    @Mock(strictness = Strictness.LENIENT)
    private ConfigProviderImpl configProvider;

    private FakeState state;

    private Map<FileID, File> files;

    private SystemFileUpdates subject;

    @Mock
    private ExchangeRateManager exchangeRateManager;

    @Mock
    private FeeManager feeManager;

    @Mock
    private ThrottleServiceManager throttleServiceManager;

    @Mock
    private BlockNodeConnectionManager blockNodeConnectionManager;

    @BeforeEach
    void setUp() {
        files = new HashMap<>();
        state = new FakeState().addService(FileService.NAME, Map.of(FILES_STATE_ID, files));

        final var config = new TestConfigBuilder(false)
                .withConverter(Bytes.class, new BytesConverter())
                .withConverter(LongPair.class, new LongPairConverter())
                .withConfigDataType(FilesConfig.class)
                .withConfigDataType(HederaConfig.class)
                .withConfigDataType(LedgerConfig.class)
                .withConfigDataType(BlockStreamConfig.class)
                .getOrCreateConfig();
        when(configProvider.getConfiguration()).thenReturn(new VersionedConfigImpl(config, 1L));
        SHARD = config.getConfigData(HederaConfig.class).shard();
        REALM = config.getConfigData(HederaConfig.class).realm();
        idFactory = new FakeEntityIdFactoryImpl(SHARD, REALM);
        subject = new SystemFileUpdates(
                configProvider, exchangeRateManager, feeManager, throttleServiceManager, blockNodeConnectionManager);
    }

    @SuppressWarnings("ConstantConditions")
    @Test
    void testMethodsWithInvalidArguments() {
        // given
        final var txBody = simpleCryptoTransfer().body();

        // then
        assertThatThrownBy(() -> new SystemFileUpdates(
                        null, exchangeRateManager, feeManager, throttleServiceManager, blockNodeConnectionManager))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SystemFileUpdates(
                        configProvider, exchangeRateManager, feeManager, null, blockNodeConnectionManager))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SystemFileUpdates(
                        configProvider, null, feeManager, throttleServiceManager, blockNodeConnectionManager))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SystemFileUpdates(
                        configProvider, exchangeRateManager, null, throttleServiceManager, blockNodeConnectionManager))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> subject.handleTxBody(null, txBody)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> subject.handleTxBody(state, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> subject.handleTxBody(state, txBody)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void testCrytpoTransferShouldBeNoOp() {
        // given
        final var txBody = TransactionBody.newBuilder()
                .cryptoTransfer(CryptoTransferTransactionBody.DEFAULT)
                .build();

        // then
        assertThatCode(() -> subject.handleTxBody(state, txBody)).doesNotThrowAnyException();
    }

    @Test
    void resyncTargetCapturesFacilityFileAndAppliedContents() {
        final var config = configProvider.getConfiguration().getConfigData(FilesConfig.class);
        final var fileID = idFactory.newFileId(config.throttleDefinitions());
        files.put(fileID, File.newBuilder().contents(FILE_BYTES).build());

        final var target = subject.resyncTarget(state, fileUpdateOf(fileID));

        assertThat(target)
                .contains(new SystemFileUpdates.ResyncTarget(
                        SystemFileUpdates.ResyncTarget.Facility.THROTTLE_DEFINITIONS, fileID, FILE_BYTES));
    }

    @Test
    void resyncTargetIsEmptyForNonFacilityFiles() {
        assertThat(subject.resyncTarget(state, fileUpdateOf(idFactory.newFileId(1001L))))
                .isEmpty();
        assertThat(subject.resyncTarget(state, TransactionBody.DEFAULT)).isEmpty();
    }

    @Test
    void resyncIfChangedSkipsWhenCommittedContentsMatchAppliedContents() {
        final var config = configProvider.getConfiguration().getConfigData(FilesConfig.class);
        final var fileID = idFactory.newFileId(config.throttleDefinitions());
        files.put(fileID, File.newBuilder().contents(FILE_BYTES).build());

        subject.resyncIfChanged(
                state,
                new SystemFileUpdates.ResyncTarget(
                        SystemFileUpdates.ResyncTarget.Facility.THROTTLE_DEFINITIONS, fileID, FILE_BYTES));

        verifyNoInteractions(throttleServiceManager);
    }

    @Test
    void resyncIfChangedRederivesFromCommittedContentsWhenTheyDiffer() {
        final var config = configProvider.getConfiguration().getConfigData(FilesConfig.class);
        final var fileID = idFactory.newFileId(config.throttleDefinitions());
        files.put(fileID, File.newBuilder().contents(FILE_BYTES).build());

        subject.resyncIfChanged(
                state,
                new SystemFileUpdates.ResyncTarget(
                        SystemFileUpdates.ResyncTarget.Facility.THROTTLE_DEFINITIONS,
                        fileID,
                        Bytes.wrap("rolled back")));

        verify(throttleServiceManager).recreateThrottles(FILE_BYTES);
    }

    @ParameterizedTest
    @EnumSource(SystemFileUpdates.ResyncTarget.Facility.class)
    void resyncTargetMapsEachFacilityFileForUpdatesAndAppends(final SystemFileUpdates.ResyncTarget.Facility facility) {
        final var fileID = idFactory.newFileId(fileNumOf(facility));
        files.put(fileID, File.newBuilder().contents(FILE_BYTES).build());
        final var expected = new SystemFileUpdates.ResyncTarget(facility, fileID, FILE_BYTES);

        assertThat(subject.resyncTarget(state, fileUpdateOf(fileID))).contains(expected);
        assertThat(subject.resyncTarget(state, fileAppendOf(fileID))).contains(expected);
    }

    @Test
    void resyncFromStateRestoresExchangeRatesWithCommittedMidnightRates() {
        final var fileID = idFactory.newFileId(fileNumOf(SystemFileUpdates.ResyncTarget.Facility.EXCHANGE_RATES));
        files.put(fileID, File.newBuilder().contents(FILE_BYTES).build());
        final var midnightRates = ExchangeRateSet.newBuilder()
                .currentRate(ExchangeRate.newBuilder().hbarEquiv(1).centEquiv(12))
                .nextRate(ExchangeRate.newBuilder().hbarEquiv(1).centEquiv(15))
                .build();
        state.addService(
                FeeService.NAME, Map.of(V0490FeeSchema.MIDNIGHT_RATES_STATE_ID, new AtomicReference<>(midnightRates)));

        subject.resyncIfChanged(
                state,
                new SystemFileUpdates.ResyncTarget(
                        SystemFileUpdates.ResyncTarget.Facility.EXCHANGE_RATES, fileID, Bytes.wrap("rolled back")));

        verify(exchangeRateManager).init(state, FILE_BYTES, midnightRates);
    }

    @Test
    void resyncFromStateReDerivesSimpleFeesFromAParseableCommittedFile() {
        final var fileID = idFactory.newFileId(fileNumOf(SystemFileUpdates.ResyncTarget.Facility.SIMPLE_FEES));
        files.put(fileID, File.newBuilder().contents(FILE_BYTES).build());
        // The committed file parses as a full schedule, so recovery re-derives from it
        when(feeManager.updateSimpleFees(FILE_BYTES, false)).thenReturn(SUCCESS);

        subject.resyncIfChanged(
                state,
                new SystemFileUpdates.ResyncTarget(
                        SystemFileUpdates.ResyncTarget.Facility.SIMPLE_FEES, fileID, Bytes.wrap("rolled back")));

        verify(feeManager).updateSimpleFees(FILE_BYTES, false);
        verify(feeManager, never()).setSimpleFeesSchedule(any());
    }

    @Test
    void resyncFromStateRestoresPriorSimpleFeesScheduleWhenCommittedFileIsAPartialChunk() {
        final var fileID = idFactory.newFileId(fileNumOf(SystemFileUpdates.ResyncTarget.Facility.SIMPLE_FEES));
        files.put(fileID, File.newBuilder().contents(FILE_BYTES).build());
        // The committed file holds only a partial upload chunk, so it does not install
        when(feeManager.updateSimpleFees(FILE_BYTES, false)).thenReturn(FEE_SCHEDULE_FILE_PART_UPLOADED);

        subject.resyncIfChanged(
                state,
                new SystemFileUpdates.ResyncTarget(
                        SystemFileUpdates.ResyncTarget.Facility.SIMPLE_FEES, fileID, Bytes.wrap("rolled back")));

        // Recovery falls back to the schedule captured before the rolled-back update (null here, as the target was
        // built without a captured schedule)
        verify(feeManager).setSimpleFeesSchedule(null);
    }

    @Test
    void resyncTargetCapturesTheActiveSimpleFeesScheduleBeforeTheUpdate() {
        final var fileID = idFactory.newFileId(fileNumOf(SystemFileUpdates.ResyncTarget.Facility.SIMPLE_FEES));
        files.put(fileID, File.newBuilder().contents(FILE_BYTES).build());
        final var activeSchedule = FeeSchedule.DEFAULT;
        when(feeManager.currentSimpleFeesSchedule()).thenReturn(activeSchedule);

        final var target = subject.resyncTarget(state, fileUpdateOf(fileID)).orElseThrow();

        // The target carries the schedule active before the update, so recovery can restore it from a partial file
        assertThat(target.priorSimpleFees()).isEqualTo(activeSchedule);
    }

    @Test
    void resyncTargetCapturesNullSimpleFeesScheduleAtGenesis() {
        final var fileID = idFactory.newFileId(fileNumOf(SystemFileUpdates.ResyncTarget.Facility.SIMPLE_FEES));
        files.put(fileID, File.newBuilder().contents(FILE_BYTES).build());
        // No schedule has been installed yet (genesis): currentSimpleFeesSchedule() returns null
        when(feeManager.currentSimpleFeesSchedule()).thenReturn(null);

        final var target = subject.resyncTarget(state, fileUpdateOf(fileID)).orElseThrow();

        assertThat(target.priorSimpleFees()).isNull();
    }

    @Test
    void resyncFromStateRestoresNetworkPropertiesAndRefreshesThrottleConfiguration() {
        final var permissions = givenCommittedPropertiesAndPermissions();
        final var fileID = idFactory.newFileId(fileNumOf(SystemFileUpdates.ResyncTarget.Facility.NETWORK_PROPERTIES));

        subject.resyncIfChanged(
                state,
                new SystemFileUpdates.ResyncTarget(
                        SystemFileUpdates.ResyncTarget.Facility.NETWORK_PROPERTIES, fileID, Bytes.wrap("rolled back")));

        verify(configProvider).update(CONFIG_LIST_BYTES, permissions);
        // Recovery refreshes throttle configuration while preserving accumulated usage and the congestion period
        verify(throttleServiceManager).refreshThrottleConfigurationPreservingUsage();
        verifyNoInteractions(blockNodeConnectionManager);
    }

    @Test
    void resyncFromStateRestoresHapiPermissionsOnly() {
        final var permissions = givenCommittedPropertiesAndPermissions();
        final var fileID = idFactory.newFileId(fileNumOf(SystemFileUpdates.ResyncTarget.Facility.HAPI_PERMISSIONS));

        subject.resyncIfChanged(
                state,
                new SystemFileUpdates.ResyncTarget(
                        SystemFileUpdates.ResyncTarget.Facility.HAPI_PERMISSIONS, fileID, Bytes.wrap("rolled back")));

        verify(configProvider).update(CONFIG_LIST_BYTES, permissions);
        verifyNoInteractions(throttleServiceManager, blockNodeConnectionManager);
    }

    private Bytes givenCommittedPropertiesAndPermissions() {
        final var permissions = Bytes.wrap("committed permissions");
        files.put(
                idFactory.newFileId(fileNumOf(SystemFileUpdates.ResyncTarget.Facility.NETWORK_PROPERTIES)),
                File.newBuilder().contents(CONFIG_LIST_BYTES).build());
        files.put(
                idFactory.newFileId(fileNumOf(SystemFileUpdates.ResyncTarget.Facility.HAPI_PERMISSIONS)),
                File.newBuilder().contents(permissions).build());
        return permissions;
    }

    private long fileNumOf(final SystemFileUpdates.ResyncTarget.Facility facility) {
        final var config = configProvider.getConfiguration().getConfigData(FilesConfig.class);
        return switch (facility) {
            case EXCHANGE_RATES -> config.exchangeRates();
            case SIMPLE_FEES -> config.simpleFeesSchedules();
            case NETWORK_PROPERTIES -> config.networkProperties();
            case HAPI_PERMISSIONS -> config.hapiPermissions();
            case THROTTLE_DEFINITIONS -> config.throttleDefinitions();
        };
    }

    private TransactionBody fileAppendOf(final FileID fileID) {
        return TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder()
                        .accountID(idFactory.newAccountId(50L))
                        .build())
                .fileAppend(FileAppendTransactionBody.newBuilder().fileID(fileID))
                .build();
    }

    private TransactionBody fileUpdateOf(final FileID fileID) {
        return TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder()
                        .accountID(idFactory.newAccountId(50L))
                        .build())
                .fileUpdate(FileUpdateTransactionBody.newBuilder().fileID(fileID))
                .build();
    }

    @Test
    void testUpdateNetworkPropertiesFile() {
        // given
        final var configuration = configProvider.getConfiguration();
        final var config = configuration.getConfigData(FilesConfig.class);
        final var fileID = idFactory.newFileId(config.networkProperties());
        final var txBody = TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder()
                        .accountID(idFactory.newAccountId(50L))
                        .build())
                .fileUpdate(FileUpdateTransactionBody.newBuilder().fileID(fileID));
        files.put(fileID, File.newBuilder().contents(FILE_BYTES).build());
        final var permissionFileID = idFactory.newFileId(config.hapiPermissions());
        final var permissionContent = Bytes.wrap("Good-bye World");
        files.put(
                permissionFileID, File.newBuilder().contents(permissionContent).build());

        // when
        subject.handleTxBody(state, txBody.build());

        // then
        verify(configProvider).update(eq(FILE_BYTES), eq(permissionContent));
    }

    @Test
    void testAppendNetworkPropertiesFile() {
        // given
        final var configuration = configProvider.getConfiguration();
        final var config = configuration.getConfigData(FilesConfig.class);
        final var fileID = idFactory.newFileId(config.networkProperties());
        final var txBody = TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder()
                        .accountID(idFactory.newAccountId(50L))
                        .build())
                .fileAppend(FileAppendTransactionBody.newBuilder().fileID(fileID));
        files.put(fileID, File.newBuilder().contents(FILE_BYTES).build());
        final var permissionFileID = idFactory.newFileId(config.hapiPermissions());
        final var permissionContent = Bytes.wrap("Good-bye World");
        files.put(
                permissionFileID, File.newBuilder().contents(permissionContent).build());

        // when
        subject.handleTxBody(state, txBody.build());

        // then
        verify(configProvider).update(eq(FILE_BYTES), eq(permissionContent));
    }

    @Test
    void testUpdatePermissionsFile() {
        // given
        final var configuration = configProvider.getConfiguration();
        final var config = configuration.getConfigData(FilesConfig.class);
        final var fileID = idFactory.newFileId(config.hapiPermissions());
        final var txBody = TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder()
                        .accountID(idFactory.newAccountId(50L))
                        .build())
                .fileUpdate(FileUpdateTransactionBody.newBuilder().fileID(fileID));
        files.put(fileID, File.newBuilder().contents(FILE_BYTES).build());
        final var networkPropertiesFileID = idFactory.newFileId(config.networkProperties());
        final var networkPropertiesContent = Bytes.wrap("Good-bye World");
        files.put(
                networkPropertiesFileID,
                File.newBuilder().contents(networkPropertiesContent).build());

        // when
        subject.handleTxBody(state, txBody.build());

        // then
        verify(configProvider).update(eq(networkPropertiesContent), eq(FILE_BYTES));
    }

    @Test
    void testAppendPermissionsFile() {
        // given
        final var configuration = configProvider.getConfiguration();
        final var config = configuration.getConfigData(FilesConfig.class);

        final var fileID = idFactory.newFileId(config.hapiPermissions());
        final var txBody = TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder()
                        .accountID(idFactory.newAccountId(50L))
                        .build())
                .fileAppend(FileAppendTransactionBody.newBuilder().fileID(fileID));
        files.put(fileID, File.newBuilder().contents(FILE_BYTES).build());
        final var networkPropertiesFileID = idFactory.newFileId(config.networkProperties());
        final var networkPropertiesContent = Bytes.wrap("Good-bye World");
        files.put(
                networkPropertiesFileID,
                File.newBuilder().contents(networkPropertiesContent).build());

        // when
        subject.handleTxBody(state, txBody.build());

        // then
        verify(configProvider).update(eq(networkPropertiesContent), eq(FILE_BYTES));
    }

    @Test
    void unparseableNetworkPropertiesUpdateIsNotSuccess() {
        // given
        final var config = configProvider.getConfiguration().getConfigData(FilesConfig.class);
        final var fileID = idFactory.newFileId(config.networkProperties());
        files.put(fileID, File.newBuilder().contents(UNPARSEABLE_BYTES).build());
        files.put(
                idFactory.newFileId(config.hapiPermissions()),
                File.newBuilder().contents(CONFIG_LIST_BYTES).build());

        // when
        final var status = subject.handleTxBody(state, updateOf(fileID));

        // then
        assertThat(status).isEqualTo(CONFIG_FILE_PART_UPLOADED);
        // Must still rebuild config, else a restarted node diverges; see FacilityInitModule
        verify(configProvider).update(eq(UNPARSEABLE_BYTES), eq(CONFIG_LIST_BYTES));
    }

    @Test
    void parseableNetworkPropertiesUpdateIsSuccess() {
        // given
        final var config = configProvider.getConfiguration().getConfigData(FilesConfig.class);
        final var fileID = idFactory.newFileId(config.networkProperties());
        files.put(fileID, File.newBuilder().contents(CONFIG_LIST_BYTES).build());
        files.put(
                idFactory.newFileId(config.hapiPermissions()),
                File.newBuilder().contents(CONFIG_LIST_BYTES).build());

        // when
        final var status = subject.handleTxBody(state, updateOf(fileID));

        // then
        assertThat(status).isEqualTo(SUCCESS);
        verify(configProvider).update(eq(CONFIG_LIST_BYTES), eq(CONFIG_LIST_BYTES));
    }

    @Test
    void unparseableHapiPermissionsUpdateIsNotSuccess() {
        // given
        final var config = configProvider.getConfiguration().getConfigData(FilesConfig.class);
        final var fileID = idFactory.newFileId(config.hapiPermissions());
        files.put(fileID, File.newBuilder().contents(UNPARSEABLE_BYTES).build());
        files.put(
                idFactory.newFileId(config.networkProperties()),
                File.newBuilder().contents(CONFIG_LIST_BYTES).build());

        // when
        final var status = subject.handleTxBody(state, updateOf(fileID));

        // then
        assertThat(status).isEqualTo(CONFIG_FILE_PART_UPLOADED);
        verify(configProvider).update(eq(CONFIG_LIST_BYTES), eq(UNPARSEABLE_BYTES));
    }

    @Test
    void parseableHapiPermissionsUpdateIsSuccess() {
        // given
        final var config = configProvider.getConfiguration().getConfigData(FilesConfig.class);
        final var fileID = idFactory.newFileId(config.hapiPermissions());
        files.put(fileID, File.newBuilder().contents(CONFIG_LIST_BYTES).build());
        files.put(
                idFactory.newFileId(config.networkProperties()),
                File.newBuilder().contents(CONFIG_LIST_BYTES).build());

        // when
        final var status = subject.handleTxBody(state, updateOf(fileID));

        // then
        assertThat(status).isEqualTo(SUCCESS);
    }

    @Test
    void unparseableAppendToNetworkPropertiesIsNotSuccess() {
        // given
        final var config = configProvider.getConfiguration().getConfigData(FilesConfig.class);
        final var fileID = idFactory.newFileId(config.networkProperties());
        files.put(fileID, File.newBuilder().contents(UNPARSEABLE_BYTES).build());
        files.put(
                idFactory.newFileId(config.hapiPermissions()),
                File.newBuilder().contents(CONFIG_LIST_BYTES).build());
        final var txBody = TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder()
                        .accountID(idFactory.newAccountId(50L))
                        .build())
                .fileAppend(FileAppendTransactionBody.newBuilder().fileID(fileID))
                .build();

        // when
        final var status = subject.handleTxBody(state, txBody);

        // then
        assertThat(status).isEqualTo(CONFIG_FILE_PART_UPLOADED);
    }

    @Test
    void truncatedNetworkPropertiesContentsIsNotSuccess() {
        // given a partial upload's intermediate state
        final var truncated = CONFIG_LIST_BYTES.slice(0, CONFIG_LIST_BYTES.length() - 1);
        final var config = configProvider.getConfiguration().getConfigData(FilesConfig.class);
        final var fileID = idFactory.newFileId(config.networkProperties());
        files.put(fileID, File.newBuilder().contents(truncated).build());
        files.put(
                idFactory.newFileId(config.hapiPermissions()),
                File.newBuilder().contents(CONFIG_LIST_BYTES).build());

        // when
        final var status = subject.handleTxBody(state, updateOf(fileID));

        // then
        assertThat(status).isEqualTo(CONFIG_FILE_PART_UPLOADED);
    }

    @Test
    void emptyNetworkPropertiesContentsIsSuccess() {
        // given empty contents, as when clearing the override file
        final var config = configProvider.getConfiguration().getConfigData(FilesConfig.class);
        final var fileID = idFactory.newFileId(config.networkProperties());
        files.put(fileID, File.newBuilder().contents(Bytes.EMPTY).build());
        files.put(
                idFactory.newFileId(config.hapiPermissions()),
                File.newBuilder().contents(CONFIG_LIST_BYTES).build());

        // when
        final var status = subject.handleTxBody(state, updateOf(fileID));

        // then
        assertThat(status).isEqualTo(SUCCESS);
    }

    @Test
    void onlyTheTargetedFileDeterminesTheStatus() {
        // given a pre-existing unparseable permissions file
        final var config = configProvider.getConfiguration().getConfigData(FilesConfig.class);
        final var fileID = idFactory.newFileId(config.networkProperties());
        files.put(fileID, File.newBuilder().contents(CONFIG_LIST_BYTES).build());
        files.put(
                idFactory.newFileId(config.hapiPermissions()),
                File.newBuilder().contents(UNPARSEABLE_BYTES).build());

        // when
        final var status = subject.handleTxBody(state, updateOf(fileID));

        // then
        assertThat(status).isEqualTo(SUCCESS);
    }

    private TransactionBody updateOf(@NonNull final FileID fileID) {
        return TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder()
                        .accountID(idFactory.newAccountId(50L))
                        .build())
                .fileUpdate(FileUpdateTransactionBody.newBuilder().fileID(fileID))
                .build();
    }

    @Test
    void throttleMangerUpdatedOnFileUpdate() {
        // given
        final var configuration = configProvider.getConfiguration();
        final var config = configuration.getConfigData(FilesConfig.class);

        final var fileNum = config.throttleDefinitions();
        final var fileID = FileID.newBuilder().fileNum(fileNum).build();
        final var txBody = TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder()
                        .accountID(AccountID.newBuilder().accountNum(50L).build())
                        .build())
                .fileUpdate(FileUpdateTransactionBody.newBuilder().fileID(fileID));
        files.put(fileID, File.newBuilder().contents(FILE_BYTES).build());

        // when
        subject.handleTxBody(state, txBody.build());

        // then
        verify(throttleServiceManager).recreateThrottles(FileUtilities.getFileContent(state, fileID));
    }

    @Test
    void exchangeRateManagerUpdatedOnFileUpdate() {
        // given
        final var configuration = configProvider.getConfiguration();
        final var config = configuration.getConfigData(FilesConfig.class);

        final var fileNum = config.exchangeRates();
        final var fileID = FileID.newBuilder().fileNum(fileNum).build();
        final var txBody = TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder()
                        .accountID(AccountID.newBuilder().accountNum(50L).build())
                        .build())
                .fileUpdate(FileUpdateTransactionBody.newBuilder().fileID(fileID));
        files.put(fileID, File.newBuilder().contents(FILE_BYTES).build());

        // when
        subject.handleTxBody(state, txBody.build());

        // then
        verify(exchangeRateManager, times(1))
                .update(
                        FileUtilities.getFileContent(state, fileID),
                        AccountID.newBuilder().accountNum(50L).build());
    }

    @Test
    void feeManagerUpdatedOnSimpleFeesFileUpdate() {
        // given
        final var configuration = configProvider.getConfiguration();
        final var config = configuration.getConfigData(FilesConfig.class);

        final var fileNum = config.simpleFeesSchedules();
        final var fileID = FileID.newBuilder().fileNum(fileNum).build();
        final var txBody = TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder()
                        .accountID(AccountID.newBuilder().accountNum(50L).build())
                        .build())
                .fileUpdate(FileUpdateTransactionBody.newBuilder().fileID(fileID));
        files.put(fileID, File.newBuilder().contents(FILE_BYTES).build());
        // when
        subject.handleTxBody(state, txBody.build());
        // then
        verify(feeManager, times(1)).updateSimpleFees(FileUtilities.getFileContent(state, fileID));
    }

    @Test
    void disablesGrpcStreamingWhenWriterModeChangesFromFileAndGrpcToFile() {
        // given
        final var initialConfig = new TestConfigBuilder(false)
                .withConverter(Bytes.class, new BytesConverter())
                .withConverter(LongPair.class, new LongPairConverter())
                .withConfigDataType(FilesConfig.class)
                .withConfigDataType(HederaConfig.class)
                .withConfigDataType(LedgerConfig.class)
                .withConfigDataType(BlockStreamConfig.class)
                .withValue("blockStream.writerMode", BlockStreamWriterMode.FILE_AND_GRPC)
                .getOrCreateConfig();
        final var updatedConfig = new TestConfigBuilder(false)
                .withConverter(Bytes.class, new BytesConverter())
                .withConverter(LongPair.class, new LongPairConverter())
                .withConfigDataType(FilesConfig.class)
                .withConfigDataType(HederaConfig.class)
                .withConfigDataType(LedgerConfig.class)
                .withConfigDataType(BlockStreamConfig.class)
                .withValue("blockStream.writerMode", BlockStreamWriterMode.FILE)
                .getOrCreateConfig();

        when(configProvider.getConfiguration())
                .thenReturn(new VersionedConfigImpl(initialConfig, 1L))
                .thenReturn(new VersionedConfigImpl(updatedConfig, 2L));

        final var filesConfig = initialConfig.getConfigData(FilesConfig.class);
        final var networkPropsId = idFactory.newFileId(filesConfig.networkProperties());
        final var permissionsId = idFactory.newFileId(filesConfig.hapiPermissions());
        files.put(networkPropsId, File.newBuilder().contents(FILE_BYTES).build());
        files.put(permissionsId, File.newBuilder().contents(Bytes.wrap("perms")).build());

        final var txBody = TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder()
                        .accountID(idFactory.newAccountId(50L))
                        .build())
                .fileUpdate(FileUpdateTransactionBody.newBuilder().fileID(networkPropsId))
                .build();

        try (final MockedStatic<CompletableFuture> mocked = Mockito.mockStatic(CompletableFuture.class)) {
            mocked.when(() -> CompletableFuture.runAsync(any(Runnable.class))).thenAnswer(inv -> {
                final Runnable r = inv.getArgument(0);
                r.run();
                return new CompletableFuture<>();
            });

            // when
            subject.handleTxBody(state, txBody);

            // then
            verify(blockNodeConnectionManager, times(1)).shutdown();
            verify(throttleServiceManager, times(1)).refreshThrottleConfiguration();
        }
    }

    @Test
    void enablesGrpcStreamingWhenWriterModeChangesFromFileToFileAndGrpc() {
        // given
        final var initialConfig = new TestConfigBuilder(false)
                .withConverter(Bytes.class, new BytesConverter())
                .withConverter(LongPair.class, new LongPairConverter())
                .withConfigDataType(FilesConfig.class)
                .withConfigDataType(HederaConfig.class)
                .withConfigDataType(LedgerConfig.class)
                .withConfigDataType(BlockStreamConfig.class)
                .withValue("blockStream.writerMode", BlockStreamWriterMode.FILE)
                .getOrCreateConfig();
        final var updatedConfig = new TestConfigBuilder(false)
                .withConverter(Bytes.class, new BytesConverter())
                .withConverter(LongPair.class, new LongPairConverter())
                .withConfigDataType(FilesConfig.class)
                .withConfigDataType(HederaConfig.class)
                .withConfigDataType(LedgerConfig.class)
                .withConfigDataType(BlockStreamConfig.class)
                .withValue("blockStream.writerMode", BlockStreamWriterMode.FILE_AND_GRPC)
                .getOrCreateConfig();

        when(configProvider.getConfiguration())
                .thenReturn(new VersionedConfigImpl(initialConfig, 1L))
                .thenReturn(new VersionedConfigImpl(updatedConfig, 2L));

        final var filesConfig = initialConfig.getConfigData(FilesConfig.class);
        final var networkPropsId = idFactory.newFileId(filesConfig.networkProperties());
        final var permissionsId = idFactory.newFileId(filesConfig.hapiPermissions());
        files.put(networkPropsId, File.newBuilder().contents(FILE_BYTES).build());
        files.put(permissionsId, File.newBuilder().contents(Bytes.wrap("perms")).build());

        final var txBody = TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder()
                        .accountID(idFactory.newAccountId(50L))
                        .build())
                .fileUpdate(FileUpdateTransactionBody.newBuilder().fileID(networkPropsId))
                .build();

        try (final MockedStatic<CompletableFuture> mocked = Mockito.mockStatic(CompletableFuture.class)) {
            mocked.when(() -> CompletableFuture.runAsync(any(Runnable.class))).thenAnswer(inv -> {
                final Runnable r = inv.getArgument(0);
                r.run();
                return new CompletableFuture<>();
            });

            // when
            subject.handleTxBody(state, txBody);

            // then
            verify(blockNodeConnectionManager, times(1)).start();
            verify(throttleServiceManager, times(1)).refreshThrottleConfiguration();
        }
    }
}
