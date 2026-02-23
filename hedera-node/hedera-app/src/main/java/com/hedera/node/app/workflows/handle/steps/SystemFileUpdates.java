// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle.steps;

import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.util.FileUtilities.observePropertiesAndPermissions;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;

import com.hedera.hapi.node.base.FileID;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.ServicesConfigurationList;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.blocks.impl.streaming.BlockNodeConnectionManager;
import com.hedera.node.app.config.ConfigProviderImpl;
import com.hedera.node.app.fees.ExchangeRateManager;
import com.hedera.node.app.fees.FeeManager;
import com.hedera.node.app.throttle.ThrottleServiceManager;
import com.hedera.node.app.util.FileUtilities;
import com.hedera.node.config.data.BlockStreamConfig;
import com.hedera.node.config.data.FilesConfig;
import com.hedera.node.config.data.LedgerConfig;
import com.hedera.node.config.types.BlockStreamWriterMode;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.State;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Simple facility that notifies interested parties when a special file is updated.
 *
 * <p>This is a temporary solution. In the future we want to have specific transactions
 * to update the data that is currently transmitted in these files.
 */
@Singleton
public class SystemFileUpdates {
    private static final Logger logger = LogManager.getLogger(SystemFileUpdates.class);

    private final ConfigProviderImpl configProvider;
    private final ExchangeRateManager exchangeRateManager;
    private final FeeManager feeManager;
    private final ThrottleServiceManager throttleServiceManager;
    private final BlockNodeConnectionManager blockNodeConnectionManager;

    /**
     * Creates a new instance of this class.
     *
     * @param configProvider the configuration provider
     */
    @Inject
    public SystemFileUpdates(
            @NonNull final ConfigProviderImpl configProvider,
            @NonNull final ExchangeRateManager exchangeRateManager,
            @NonNull final FeeManager feeManager,
            @NonNull final ThrottleServiceManager throttleServiceManager,
            @NonNull final BlockNodeConnectionManager blockNodeConnectionManager) {
        this.configProvider = requireNonNull(configProvider, "configProvider must not be null");
        this.exchangeRateManager = requireNonNull(exchangeRateManager, "exchangeRateManager must not be null");
        this.feeManager = requireNonNull(feeManager, "feeManager must not be null");
        this.throttleServiceManager = requireNonNull(throttleServiceManager);
        this.blockNodeConnectionManager = requireNonNull(blockNodeConnectionManager);
    }

    /**
     * Checks whether the given transaction body is a file update or file append of a special file and eventually
     * notifies the registered facility.
     *
     * @param state the current state (the updated file content needs to be committed to the state)
     * @param txBody the transaction body
     */
    public ResponseCodeEnum handleTxBody(@NonNull final State state, @NonNull final TransactionBody txBody) {
        requireNonNull(state, "state must not be null");
        requireNonNull(txBody, "txBody must not be null");

        // Try to extract the file ID from the transaction body, if it is FileUpdate or FileAppend.
        final FileID fileID;
        if (txBody.hasFileUpdate()) {
            fileID = txBody.fileUpdateOrThrow().fileIDOrThrow();
        } else if (txBody.hasFileAppend()) {
            fileID = txBody.fileAppendOrThrow().fileIDOrThrow();
        } else {
            return SUCCESS;
        }

        // Check if the file is a special file
        final var configuration = configProvider.getConfiguration();
        final var ledgerConfig = configuration.getConfigData(LedgerConfig.class);
        final var fileNum = fileID.fileNum();
        final var payer = txBody.transactionIDOrThrow().accountIDOrThrow();
        if (fileNum > ledgerConfig.numReservedSystemEntities()) {
            return SUCCESS;
        }

        // If it is a special file, call the updater.
        // We load the file only, if there is an updater for it.
        final var filesConfig = configuration.getConfigData(FilesConfig.class);

        if (fileNum == filesConfig.feeSchedules()) {
            return feeManager.update(FileUtilities.getFileContent(state, fileID));
        } else if (fileNum == filesConfig.simpleFeesSchedules()) {
            return feeManager.updateSimpleFees(FileUtilities.getFileContent(state, fileID));
        } else if (fileNum == filesConfig.exchangeRates()) {
            exchangeRateManager.update(FileUtilities.getFileContent(state, fileID), payer);
        } else if (fileNum == filesConfig.networkProperties()) {
            BlockStreamWriterMode currentWriterMode =
                    configuration.getConfigData(BlockStreamConfig.class).writerMode();
            updateConfig(configuration, ConfigType.NETWORK_PROPERTIES, state);
            checkForBlockNodeStreamingChange(currentWriterMode, configProvider);
            throttleServiceManager.refreshThrottleConfiguration();
        } else if (fileNum == filesConfig.hapiPermissions()) {
            updateConfig(configuration, ConfigType.API_PERMISSIONS, state);
        } else if (fileNum == filesConfig.throttleDefinitions()) {
            return throttleServiceManager.recreateThrottles(FileUtilities.getFileContent(state, fileID));
        }
        return SUCCESS;
    }

    private void checkForBlockNodeStreamingChange(
            BlockStreamWriterMode currentWriterMode, ConfigProviderImpl configProvider) {
        if (currentWriterMode == BlockStreamWriterMode.FILE_AND_GRPC
                && configProvider
                                .getConfiguration()
                                .getConfigData(BlockStreamConfig.class)
                                .writerMode()
                        == BlockStreamWriterMode.FILE) {
            // We have changed from FILE_AND_GRPC to FILE only
            logger.info(
                    "Disabling gRPC Block Node streaming as the network properties have changed writerMode from FILE_AND_GRPC to FILE only");
            CompletableFuture.runAsync(blockNodeConnectionManager::shutdown);
        } else if (currentWriterMode == BlockStreamWriterMode.FILE
                && configProvider
                                .getConfiguration()
                                .getConfigData(BlockStreamConfig.class)
                                .writerMode()
                        == BlockStreamWriterMode.FILE_AND_GRPC) {
            logger.info(
                    "Enabling gRPC Block Node streaming as the network properties have changed writerMode from FILE to FILE_AND_GRPC");
            CompletableFuture.runAsync(blockNodeConnectionManager::start);
        }
    }

    private enum ConfigType {
        NETWORK_PROPERTIES,
        API_PERMISSIONS,
    }

    private void updateConfig(
            @NonNull final Configuration configuration,
            @NonNull final ConfigType configType,
            @NonNull final State state) {
        observePropertiesAndPermissions(state, configuration, (properties, permissions) -> {
            configProvider.update(properties, permissions);
            if (configType == ConfigType.NETWORK_PROPERTIES) {
                logContentsOf("Network properties", properties);
            } else {
                logContentsOf("API permissions", permissions);
            }
        });
    }

    private void logContentsOf(@NonNull final String configFileName, @NonNull final Bytes contents) {
        try {
            final var configList = ServicesConfigurationList.PROTOBUF.parseStrict(contents.toReadableSequentialData());
            final var printableConfigList = configList.nameValue().stream()
                    .map(pair -> pair.name() + "=" + pair.value())
                    .collect(joining("\n\t"));
            logger.info(
                    "Refreshing properties with following overrides to {}:\n\t{}",
                    configFileName,
                    printableConfigList.isBlank() ? "<NONE>" : printableConfigList);
        } catch (ParseException ignore) {
            // If this isn't parseable we won't have updated anything, also don't log
        }
    }
}
