// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows;

import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.blocks.schemas.V0560BlockStreamSchema.BLOCK_STREAM_INFO_STATE_ID;
import static com.hedera.node.app.records.BlockRecordService.EPOCH;
import static com.hedera.node.app.records.schemas.V0490BlockRecordSchema.BLOCKS_STATE_ID;
import static com.hedera.node.app.service.token.impl.api.TokenServiceApiProvider.TOKEN_SERVICE_API_PROVIDER;
import static com.hedera.node.app.util.FileUtilities.createFileID;
import static com.hedera.node.app.util.FileUtilities.getFileContent;
import static com.hedera.node.app.util.FileUtilities.observePropertiesAndPermissions;
import static com.hedera.node.config.types.StreamMode.RECORDS;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.FileID;
import com.hedera.hapi.node.state.blockrecords.BlockInfo;
import com.hedera.hapi.node.state.blockstream.BlockStreamInfo;
import com.hedera.hapi.node.state.file.File;
import com.hedera.node.app.blocks.BlockStreamService;
import com.hedera.node.app.config.BootstrapConfigProviderImpl;
import com.hedera.node.app.config.ConfigProviderImpl;
import com.hedera.node.app.fees.ExchangeRateManager;
import com.hedera.node.app.fees.FeeManager;
import com.hedera.node.app.records.BlockRecordService;
import com.hedera.node.app.service.file.ReadableFileStore;
import com.hedera.node.app.service.file.impl.FileServiceImpl;
import com.hedera.node.app.service.schedule.ScheduleService;
import com.hedera.node.app.service.schedule.ScheduleServiceApi;
import com.hedera.node.app.service.token.api.TokenServiceApi;
import com.hedera.node.app.spi.AppContext;
import com.hedera.node.app.spi.api.ServiceApiProvider;
import com.hedera.node.app.spi.fees.FeeCharging;
import com.hedera.node.app.state.WorkingStateAccessor;
import com.hedera.node.app.store.ReadableStoreFactory;
import com.hedera.node.app.throttle.ThrottleServiceManager;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.FilesConfig;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.node.config.types.StreamMode;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.State;
import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Module that provides initialization for the state-dependent facilities used to execute transactions.
 * These include the fees, exchange rates, and throttling facilities; as well as the {@link WorkingStateAccessor}.
 */
@Module
public interface FacilityInitModule {

    Logger log = LogManager.getLogger(FacilityInitModule.class);

    @FunctionalInterface
    interface FacilityInitializer {
        void initialize(@NonNull State state, @NonNull StreamMode streamMode);
    }

    @Provides
    @Singleton
    static Supplier<FeeCharging> provideBaseFeeCharging(@NonNull final AppContext appContext) {
        requireNonNull(appContext);
        return appContext.feeChargingSupplier();
    }

    @Provides
    @Singleton
    static Map<Class<?>, ServiceApiProvider<?>> provideApiProviders(@NonNull final ScheduleService scheduleService) {
        requireNonNull(scheduleService);
        return Map.of(
                TokenServiceApi.class,
                TOKEN_SERVICE_API_PROVIDER,
                ScheduleServiceApi.class,
                scheduleService.apiProvider());
    }

    @Binds
    @Singleton
    ConfigProvider bindConfigProvider(@NonNull ConfigProviderImpl configProvider);

    /**
     * Provides the initialization for the state-dependent facilities used to execute transactions.
     *
     * @param feeManager the {@link FeeManager} to initialize
     * @param exchangeRateManager the {@link ExchangeRateManager} to initialize
     * @param throttleServiceManager the {@link ThrottleServiceManager} to initialize
     * @param workingStateAccessor the {@link WorkingStateAccessor} to update with the working state
     * @return the initialization function
     */
    @Provides
    @Singleton
    static FacilityInitializer initFacilities(
            @NonNull final FeeManager feeManager,
            @NonNull final FileServiceImpl fileService,
            @NonNull final ConfigProviderImpl configProvider,
            @NonNull final BootstrapConfigProviderImpl bootstrapConfigProvider,
            @NonNull final ExchangeRateManager exchangeRateManager,
            @NonNull final ThrottleServiceManager throttleServiceManager,
            @NonNull final WorkingStateAccessor workingStateAccessor) {
        return (state, streamMode) -> {
            requireNonNull(state);
            requireNonNull(streamMode);
            if (hasHandledGenesisTxn(state, streamMode)) {
                initializeExchangeRateManager(state, configProvider, exchangeRateManager);
                initializeFeeManager(state, configProvider, feeManager);
                observePropertiesAndPermissions(state, configProvider.getConfiguration(), (properties, permissions) -> {
                    if (!Bytes.EMPTY.equals(properties) || !Bytes.EMPTY.equals(permissions)) {
                        configProvider.update(properties, permissions);
                    }
                });
                throttleServiceManager.init(state, throttleDefinitionsFrom(state, configProvider));
            } else {
                final var schema = fileService.fileSchema();
                final var bootstrapConfig = bootstrapConfigProvider.getConfiguration();
                exchangeRateManager.init(state, schema.genesisExchangeRates(bootstrapConfig));
                feeManager.update(schema.genesisFeeSchedules(bootstrapConfig));
                throttleServiceManager.init(state, schema.genesisThrottleDefinitions(bootstrapConfig));
            }
            workingStateAccessor.setState(state);
        };
    }

    private static void initializeExchangeRateManager(
            @NonNull final State state,
            @NonNull final ConfigProvider configProvider,
            @NonNull final ExchangeRateManager exchangeRateManager) {
        final var filesConfig = configProvider.getConfiguration().getConfigData(FilesConfig.class);
        final var fileNum = filesConfig.exchangeRates();
        final var file = requireNonNull(
                getFileFromStorage(state, configProvider, fileNum),
                "The initialized state had no exchange rates file 0.0." + fileNum);
        exchangeRateManager.init(state, file.contents());
    }

    private static void initializeFeeManager(
            @NonNull final State state,
            @NonNull final ConfigProvider configProvider,
            @NonNull final FeeManager feeManager) {
        log.info("Initializing fee schedules");
        final var filesConfig = configProvider.getConfiguration().getConfigData(FilesConfig.class);
        final var fileNum = filesConfig.feeSchedules();
        final var file = requireNonNull(
                getFileFromStorage(state, configProvider, fileNum),
                "The initialized state had no fee schedule file 0.0." + fileNum);
        final var status = feeManager.update(file.contents());
        if (status != SUCCESS) {
            // (FUTURE) Ideally this would be a fatal error, but unlike the exchange rates file, it
            // is possible with the current design for state to include a partial fee schedules file,
            // so we cannot fail hard here
            log.error("State file 0.0.{} did not contain parseable fee schedules ({})", fileNum, status);
        }
    }

    private static boolean hasHandledGenesisTxn(@NonNull final State state, @NonNull final StreamMode streamMode) {
        if (streamMode == RECORDS) {
            final var blockInfo = state.getReadableStates(BlockRecordService.NAME)
                    .<BlockInfo>getSingleton(BLOCKS_STATE_ID)
                    .get();
            return !EPOCH.equals(Optional.ofNullable(blockInfo)
                    .map(BlockInfo::consTimeOfLastHandledTxn)
                    .orElse(EPOCH));
        } else {
            final var blockStreamInfo = state.getReadableStates(BlockStreamService.NAME)
                    .<BlockStreamInfo>getSingleton(BLOCK_STREAM_INFO_STATE_ID)
                    .get();
            return !EPOCH.equals(Optional.ofNullable(blockStreamInfo)
                    .map(BlockStreamInfo::lastHandleTime)
                    .orElse(EPOCH));
        }
    }

    private static @Nullable File getFileFromStorage(
            @NonNull final State state, @NonNull final ConfigProvider configProvider, final long fileNum) {
        final var readableFileStore = new ReadableStoreFactory(state).getStore(ReadableFileStore.class);
        final var hederaConfig = configProvider.getConfiguration().getConfigData(HederaConfig.class);
        final var fileId = FileID.newBuilder()
                .fileNum(fileNum)
                .shardNum(hederaConfig.shard())
                .realmNum(hederaConfig.realm())
                .build();
        return readableFileStore.getFileLeaf(fileId);
    }

    private static Bytes throttleDefinitionsFrom(
            @NonNull final State state, @NonNull final ConfigProvider configProvider) {
        final var config = configProvider.getConfiguration();
        final var filesConfig = config.getConfigData(FilesConfig.class);
        final var throttleDefinitionsId = createFileID(filesConfig.throttleDefinitions(), config);
        return getFileContent(state, throttleDefinitionsId);
    }
}
