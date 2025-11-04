// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle.steps;

import static com.hedera.node.app.service.file.impl.schemas.V0490FileSchema.FILES_STATE_ID;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.FileID;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.file.FileAppendTransactionBody;
import com.hedera.hapi.node.file.FileUpdateTransactionBody;
import com.hedera.hapi.node.state.file.File;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.blocks.impl.streaming.BlockNodeConnectionManager;
import com.hedera.node.app.config.ConfigProviderImpl;
import com.hedera.node.app.fees.ExchangeRateManager;
import com.hedera.node.app.fees.FeeManager;
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
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mock.Strictness;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SystemFileUpdatesTest implements TransactionFactory {

    private static final Bytes FILE_BYTES = Bytes.wrap("Hello World");
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
    void feeManagerUpdatedOnFileUpdate() {
        // given
        final var configuration = configProvider.getConfiguration();
        final var config = configuration.getConfigData(FilesConfig.class);

        final var fileNum = config.feeSchedules();
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
        verify(feeManager, times(1)).update(FileUtilities.getFileContent(state, fileID));
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
                return CompletableFuture.completedFuture(null);
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
                return CompletableFuture.completedFuture(null);
            });

            // when
            subject.handleTxBody(state, txBody);

            // then
            verify(blockNodeConnectionManager, times(1)).start();
            verify(throttleServiceManager, times(1)).refreshThrottleConfiguration();
        }
    }
}
