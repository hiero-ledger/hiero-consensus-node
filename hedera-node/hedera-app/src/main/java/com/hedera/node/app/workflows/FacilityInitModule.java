// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows;

import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.records.BlockRecordService.EPOCH;
import static com.hedera.node.app.records.schemas.V0490BlockRecordSchema.BLOCK_INFO_STATE_KEY;
import static com.hedera.node.app.util.FileUtilities.createFileID;
import static com.hedera.node.app.util.FileUtilities.getFileContent;
import static com.hedera.node.app.util.FileUtilities.observePropertiesAndPermissions;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.FileID;
import com.hedera.hapi.node.state.blockrecords.BlockInfo;
import com.hedera.hapi.node.state.file.File;
import com.hedera.node.app.config.BootstrapConfigProviderImpl;
import com.hedera.node.app.config.ConfigProviderImpl;
import com.hedera.node.app.fees.ExchangeRateManager;
import com.hedera.node.app.fees.FeeManager;
import com.hedera.node.app.records.BlockRecordService;
import com.hedera.node.app.service.file.ReadableFileStore;
import com.hedera.node.app.service.file.impl.FileServiceImpl;
import com.hedera.node.app.state.WorkingStateAccessor;
import com.hedera.node.app.store.ReadableStoreFactory;
import com.hedera.node.app.throttle.ThrottleServiceManager;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.FilesConfig;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.State;
import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Optional;
import java.util.function.Consumer;
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
    static Consumer<State> initFacilities(
            @NonNull final FeeManager feeManager,
            @NonNull final FileServiceImpl fileService,
            @NonNull final ConfigProviderImpl configProvider,
            @NonNull final BootstrapConfigProviderImpl bootstrapConfigProvider,
            @NonNull final ExchangeRateManager exchangeRateManager,
            @NonNull final ThrottleServiceManager throttleServiceManager,
            @NonNull final WorkingStateAccessor workingStateAccessor) {
        return state -> {
            if (hasHandledGenesisTxn(state)) {
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

    private static boolean hasHandledGenesisTxn(@NonNull final State state) {
        final var blockInfo = state.getReadableStates(BlockRecordService.NAME)
                .<BlockInfo>getSingleton(BLOCK_INFO_STATE_KEY)
                .get();
        return !EPOCH.equals(Optional.ofNullable(blockInfo)
                .map(BlockInfo::consTimeOfLastHandledTxn)
                .orElse(EPOCH));
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
