// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle.steps;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CONFIG_FILE_PART_UPLOADED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.util.FileUtilities.observePropertiesAndPermissions;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;

import com.hedera.hapi.node.base.FileID;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.ServicesConfigurationList;
import com.hedera.hapi.node.transaction.ExchangeRateSet;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.blocks.impl.streaming.BlockNodeConnectionManager;
import com.hedera.node.app.config.ConfigProviderImpl;
import com.hedera.node.app.fees.ExchangeRateManager;
import com.hedera.node.app.fees.FeeManager;
import com.hedera.node.app.fees.FeeService;
import com.hedera.node.app.fees.schemas.V0490FeeSchema;
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
import edu.umd.cs.findbugs.annotations.Nullable;
import java.nio.BufferUnderflowException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.hapi.support.fees.FeeSchedule;

/**
 * Simple facility that notifies interested parties when a special file is updated.
 *
 * <p>This is a temporary solution. In the future we want to have specific transactions
 * to update the data that is currently transmitted in these files.
 *
 * <p>On unparseable contents, chunk-uploaded files return a status (fee schedules, network properties, HAPI
 * permissions) while single-transaction files throw (exchange rates, throttles). Only throwing rolls the
 * committed bytes back.
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

        if (fileNum == filesConfig.simpleFeesSchedules()) {
            return feeManager.updateSimpleFees(FileUtilities.getFileContent(state, fileID));
        } else if (fileNum == filesConfig.exchangeRates()) {
            exchangeRateManager.update(FileUtilities.getFileContent(state, fileID), payer);
        } else if (fileNum == filesConfig.networkProperties()) {
            final var status = configListParseStatus(FileUtilities.getFileContent(state, fileID));
            BlockStreamWriterMode currentWriterMode =
                    configuration.getConfigData(BlockStreamConfig.class).writerMode();
            updateConfig(configuration, ConfigType.NETWORK_PROPERTIES, state);
            checkForBlockNodeStreamingChange(currentWriterMode, configProvider);
            throttleServiceManager.refreshThrottleConfiguration();
            return status;
        } else if (fileNum == filesConfig.hapiPermissions()) {
            final var status = configListParseStatus(FileUtilities.getFileContent(state, fileID));
            updateConfig(configuration, ConfigType.API_PERMISSIONS, state);
            return status;
        } else if (fileNum == filesConfig.throttleDefinitions()) {
            return throttleServiceManager.recreateThrottles(FileUtilities.getFileContent(state, fileID));
        }
        return SUCCESS;
    }

    /**
     * A process-global facility backed by a system file that {@link #handleTxBody} updates in memory, together with
     * the system file that backs it and the file contents the facility was updated from, so the facility can be
     * re-derived from committed state.
     *
     * <p>For the configuration facilities (network properties and HAPI permissions) it also carries a
     * {@link ConfigRecoveryContext} captured before the update applied, so recovery resolves the configuration files
     * and restores the throttle usage from pre-update values rather than from the in-memory state the rolled-back
     * update left behind. For the simple-fees facility it carries {@code priorSimpleFees}, the schedule active before
     * the update, so recovery can restore it when the committed file holds only a partial upload chunk. Both are
     * auxiliary recovery data, not part of the target's identity, so they are excluded from {@link #equals(Object)}
     * and {@link #hashCode()}.
     *
     * @param facility the facility that was updated in memory
     * @param fileId the system file backing it
     * @param appliedContents the file contents the facility was updated from
     * @param configContext the pre-update configuration recovery context, or null for a non-configuration facility
     * @param priorSimpleFees the simple-fees schedule active before the update, or null for a non-simple-fees facility
     * or when the prior state was the genesis (no schedule)
     */
    public record ResyncTarget(
            @NonNull Facility facility,
            @NonNull FileID fileId,
            @NonNull Bytes appliedContents,
            @Nullable ConfigRecoveryContext configContext,
            @Nullable FeeSchedule priorSimpleFees) {

        public ResyncTarget(
                @NonNull final Facility facility, @NonNull final FileID fileId, @NonNull final Bytes appliedContents) {
            this(facility, fileId, appliedContents, null, null);
        }

        public ResyncTarget(
                @NonNull final Facility facility,
                @NonNull final FileID fileId,
                @NonNull final Bytes appliedContents,
                @Nullable final ConfigRecoveryContext configContext) {
            this(facility, fileId, appliedContents, configContext, null);
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof ResyncTarget that)) {
                return false;
            }
            return facility == that.facility
                    && fileId.equals(that.fileId)
                    && appliedContents.equals(that.appliedContents);
        }

        @Override
        public int hashCode() {
            return Objects.hash(facility, fileId, appliedContents);
        }

        /** The facilities that {@link #handleTxBody} updates in memory from a system file. */
        public enum Facility {
            EXCHANGE_RATES,
            SIMPLE_FEES,
            NETWORK_PROPERTIES,
            HAPI_PERMISSIONS,
            THROTTLE_DEFINITIONS
        }
    }

    /**
     * The pre-update state a rolled-back configuration update needs to be recovered from committed state: the committed
     * file IDs of both configuration files (so recovery reads them even if the update remapped {@code files.*} to other
     * file numbers), and, for a network-properties update, the throttle usage the update's throttle rebuild discarded.
     *
     * @param networkPropertiesId the committed network-properties file ID
     * @param hapiPermissionsId the committed HAPI-permissions file ID
     * @param throttleUsage the throttle usage captured before the update, or null when the update does not refresh
     * the throttle configuration (i.e. a HAPI-permissions update)
     */
    public record ConfigRecoveryContext(
            @NonNull FileID networkPropertiesId,
            @NonNull FileID hapiPermissionsId,
            @Nullable ThrottleServiceManager.PreservedThrottleUsage throttleUsage) {}

    /**
     * If the given transaction body would update a process-global facility that must always reflect committed state
     * (exchange rates, simple fee schedule, network properties, HAPI permissions, or throttle definitions), returns
     * that facility together with its backing file and the file contents in the given state, which are the contents
     * {@link #handleTxBody} just applied. Otherwise returns empty.
     *
     * <p>The facility is identified here, from the same configuration {@link #handleTxBody} used to apply the update,
     * rather than when a re-sync runs; by then the configuration may itself have changed, and re-deriving the mapping
     * from it could misroute.
     *
     * @param state the state the dispatch updated the file in
     * @param txBody the transaction body of a successfully handled dispatch
     * @return the facility, backing file and applied contents when the body updates a re-syncable facility,
     * otherwise empty
     */
    public Optional<ResyncTarget> resyncTarget(@NonNull final State state, @NonNull final TransactionBody txBody) {
        requireNonNull(state);
        requireNonNull(txBody);
        final FileID fileID;
        if (txBody.hasFileUpdate()) {
            fileID = txBody.fileUpdateOrThrow().fileIDOrThrow();
        } else if (txBody.hasFileAppend()) {
            fileID = txBody.fileAppendOrThrow().fileIDOrThrow();
        } else {
            return Optional.empty();
        }
        final var configuration = configProvider.getConfiguration();
        final var fileNum = fileID.fileNum();
        if (fileNum > configuration.getConfigData(LedgerConfig.class).numReservedSystemEntities()) {
            return Optional.empty();
        }
        final var filesConfig = configuration.getConfigData(FilesConfig.class);
        final ResyncTarget.Facility facility;
        if (fileNum == filesConfig.exchangeRates()) {
            facility = ResyncTarget.Facility.EXCHANGE_RATES;
        } else if (fileNum == filesConfig.simpleFeesSchedules()) {
            facility = ResyncTarget.Facility.SIMPLE_FEES;
        } else if (fileNum == filesConfig.networkProperties()) {
            facility = ResyncTarget.Facility.NETWORK_PROPERTIES;
        } else if (fileNum == filesConfig.hapiPermissions()) {
            facility = ResyncTarget.Facility.HAPI_PERMISSIONS;
        } else if (fileNum == filesConfig.throttleDefinitions()) {
            facility = ResyncTarget.Facility.THROTTLE_DEFINITIONS;
        } else {
            return Optional.empty();
        }
        // For a configuration facility, capture the pre-update committed file IDs of both configuration files (so
        // recovery reads them even if this update remaps files.networkProperties/files.hapiPermissions), and, for a
        // network-properties update, the throttle usage this update's throttle rebuild is about to discard.
        final ConfigRecoveryContext configContext =
                switch (facility) {
                    case NETWORK_PROPERTIES ->
                        new ConfigRecoveryContext(
                                FileUtilities.createFileID(filesConfig.networkProperties(), configuration),
                                FileUtilities.createFileID(filesConfig.hapiPermissions(), configuration),
                                throttleServiceManager.captureThrottleUsage());
                    case HAPI_PERMISSIONS ->
                        new ConfigRecoveryContext(
                                FileUtilities.createFileID(filesConfig.networkProperties(), configuration),
                                FileUtilities.createFileID(filesConfig.hapiPermissions(), configuration),
                                null);
                    default -> null;
                };
        // For a simple-fees update, capture the schedule active before it, so recovery can restore it if the committed
        // file turns out to be only a partial upload chunk that cannot be re-derived into a schedule.
        final FeeSchedule priorSimpleFees =
                facility == ResyncTarget.Facility.SIMPLE_FEES ? feeManager.currentSimpleFeesSchedule() : null;
        return Optional.of(new ResyncTarget(
                facility, fileID, FileUtilities.getFileContent(state, fileID), configContext, priorSimpleFees));
    }

    /**
     * Re-derives the given facility from the given committed state if the committed file contents differ from the
     * contents the facility was updated from. Must be called on the handle thread, after the transaction's changes
     * are committed.
     *
     * @param state the committed state
     * @param target the facility, backing file and applied contents, as returned by
     * {@link #resyncTarget(State, TransactionBody)}
     */
    public void resyncIfChanged(@NonNull final State state, @NonNull final ResyncTarget target) {
        requireNonNull(state);
        requireNonNull(target);
        final var fileChanged =
                !FileUtilities.getFileContent(state, target.fileId()).equals(target.appliedContents());
        // An exchange-rate update paid by the system admin moves the in-memory midnight baseline without changing the
        // file, so a rolled-back such update leaves that baseline out of step with the committed singleton even when
        // the file bytes are unchanged; re-derive then too, or the leaked baseline would skew the next intraday check.
        final var midnightBaselineDrifted = target.facility() == ResyncTarget.Facility.EXCHANGE_RATES
                && !Objects.equals(exchangeRateManager.midnightRates(), committedMidnightRates(state));
        if (fileChanged || midnightBaselineDrifted) {
            resyncFromState(state, target);
        }
    }

    private ExchangeRateSet committedMidnightRates(@NonNull final State state) {
        return state.getReadableStates(FeeService.NAME)
                .<ExchangeRateSet>getSingleton(V0490FeeSchema.MIDNIGHT_RATES_STATE_ID)
                .get();
    }

    /**
     * Re-derives the given in-memory facility from the given committed state. Must be called on the handle thread,
     * after the transaction's changes are committed, so the state reflects the committed file bytes.
     *
     * <p>For exchange rates this restores both the active rate (from file 0.0.112) and the in-memory midnight rates
     * (from the {@code MIDNIGHT_RATES} singleton), matching node (re)initialization; otherwise the midnight rates
     * would be clobbered from the file.
     *
     * @param state the committed state to re-derive the facility from
     * @param target the facility and backing file, as returned by {@link #resyncTarget(State, TransactionBody)}
     */
    public void resyncFromState(@NonNull final State state, @NonNull final ResyncTarget target) {
        requireNonNull(state);
        requireNonNull(target);
        final var fileID = target.fileId();
        switch (target.facility()) {
            case EXCHANGE_RATES -> {
                final var midnightRates = requireNonNull(
                        state.getReadableStates(FeeService.NAME)
                                .<ExchangeRateSet>getSingleton(V0490FeeSchema.MIDNIGHT_RATES_STATE_ID)
                                .get(),
                        "The committed state had no midnight rates");
                exchangeRateManager.init(state, FileUtilities.getFileContent(state, fileID), midnightRates);
            }
            // Prefer re-deriving from the committed file; but it may hold only a partial upload chunk that does not
            // parse as a schedule, in which case restore the schedule captured before the rolled-back update instead.
            case SIMPLE_FEES -> {
                if (feeManager.updateSimpleFees(FileUtilities.getFileContent(state, fileID), false) != SUCCESS) {
                    feeManager.setSimpleFeesSchedule(target.priorSimpleFees());
                }
            }
            case NETWORK_PROPERTIES -> {
                final var configuration = configProvider.getConfiguration();
                final var currentWriterMode =
                        configuration.getConfigData(BlockStreamConfig.class).writerMode();
                final var context = target.configContext();
                updateConfig(
                        configuration,
                        ConfigType.NETWORK_PROPERTIES,
                        state,
                        networkPropertiesIdOf(context, configuration),
                        hapiPermissionsIdOf(context, configuration));
                checkForBlockNodeStreamingChange(currentWriterMode, configProvider);
                if (context != null && context.throttleUsage() != null) {
                    // Restore the usage captured before the rolled-back update discarded it, rather than preserving the
                    // already-discarded (zeroed) current usage.
                    throttleServiceManager.refreshThrottleConfigurationRestoring(context.throttleUsage());
                } else {
                    throttleServiceManager.refreshThrottleConfigurationPreservingUsage();
                }
            }
            case HAPI_PERMISSIONS -> {
                final var configuration = configProvider.getConfiguration();
                final var context = target.configContext();
                updateConfig(
                        configuration,
                        ConfigType.API_PERMISSIONS,
                        state,
                        networkPropertiesIdOf(context, configuration),
                        hapiPermissionsIdOf(context, configuration));
            }
            // Re-reading the committed definitions rebuilds the throttle buckets with reset usage, exactly as a
            // committed 0.0.123 update does; all nodes do this deterministically, so it does not cause divergence.
            case THROTTLE_DEFINITIONS ->
                throttleServiceManager.recreateThrottles(FileUtilities.getFileContent(state, fileID));
        }
    }

    /**
     * Returns whether the given contents parse as a {@link ServicesConfigurationList}; if not, no
     * configuration change was applied.
     *
     * @param contents the assembled contents of the file
     * @return the status to report
     */
    private ResponseCodeEnum configListParseStatus(@NonNull final Bytes contents) {
        try {
            ServicesConfigurationList.PROTOBUF.parseStrict(contents.toReadableSequentialData());
            return SUCCESS;
        } catch (ParseException | BufferUnderflowException | NullPointerException ignore) {
            // The failures that make ConfigProviderImpl.addByteSource() drop this source
            return CONFIG_FILE_PART_UPLOADED;
        }
    }

    private void checkForBlockNodeStreamingChange(
            BlockStreamWriterMode currentWriterMode, ConfigProviderImpl configProvider) {
        final var newWriterMode = configProvider
                .getConfiguration()
                .getConfigData(BlockStreamConfig.class)
                .writerMode();
        if (currentWriterMode == BlockStreamWriterMode.FILE_AND_GRPC && newWriterMode == BlockStreamWriterMode.FILE) {
            // We have changed from FILE_AND_GRPC to FILE only
            logger.info(
                    "Disabling gRPC Block Node streaming as the network properties have changed writerMode from FILE_AND_GRPC to FILE only");
            applyCommittedBlockNodeStreaming();
        } else if (currentWriterMode == BlockStreamWriterMode.FILE
                && newWriterMode == BlockStreamWriterMode.FILE_AND_GRPC) {
            logger.info(
                    "Enabling gRPC Block Node streaming as the network properties have changed writerMode from FILE to FILE_AND_GRPC");
            applyCommittedBlockNodeStreaming();
        }
    }

    /**
     * Asynchronously reconciles block node streaming to the committed writer mode, reading that mode when the task
     * runs rather than when it is queued. A rolled-back writer-mode update and its recovery each queue one of these
     * tasks with no ordering between them; because each reads the committed mode at run time, streaming converges on
     * that mode whatever order the tasks run in, instead of ending in the state of whichever happened to run last.
     */
    private void applyCommittedBlockNodeStreaming() {
        CompletableFuture.runAsync(() -> {
            final var committedWriterMode = configProvider
                    .getConfiguration()
                    .getConfigData(BlockStreamConfig.class)
                    .writerMode();
            if (committedWriterMode == BlockStreamWriterMode.FILE_AND_GRPC) {
                blockNodeConnectionManager.start();
            } else if (committedWriterMode == BlockStreamWriterMode.FILE) {
                blockNodeConnectionManager.shutdown();
            }
        });
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

    /**
     * Re-derives the configuration during recovery from the given committed file IDs rather than from the file numbers
     * in the in-memory configuration, which the rolled-back update may have remapped. Both configuration files are
     * read, since {@link ConfigProviderImpl#update} takes both sources; the IDs are the committed ones carried by the
     * resync target (or, absent a target context, the ones from the current configuration).
     *
     * @param configuration the current configuration
     * @param configType whether network properties or API permissions are being recovered (used only for logging)
     * @param state the committed state to read from
     * @param propertiesId the committed network-properties file ID
     * @param permissionsId the committed HAPI-permissions file ID
     */
    private void updateConfig(
            @NonNull final Configuration configuration,
            @NonNull final ConfigType configType,
            @NonNull final State state,
            @NonNull final FileID propertiesId,
            @NonNull final FileID permissionsId) {
        final var properties = FileUtilities.getFileContent(state, propertiesId);
        final var permissions = FileUtilities.getFileContent(state, permissionsId);
        configProvider.update(properties, permissions);
        if (configType == ConfigType.NETWORK_PROPERTIES) {
            logContentsOf("Network properties", properties);
        } else {
            logContentsOf("API permissions", permissions);
        }
    }

    /**
     * Resolves the committed network-properties file ID for recovery: the one captured before the update when the
     * target carries a {@link ConfigRecoveryContext}, otherwise the current configuration's.
     */
    private FileID networkPropertiesIdOf(
            @Nullable final ConfigRecoveryContext context, @NonNull final Configuration configuration) {
        return context != null
                ? context.networkPropertiesId()
                : FileUtilities.createFileID(
                        configuration.getConfigData(FilesConfig.class).networkProperties(), configuration);
    }

    /**
     * Resolves the committed HAPI-permissions file ID for recovery: the one captured before the update when the target
     * carries a {@link ConfigRecoveryContext}, otherwise the current configuration's.
     */
    private FileID hapiPermissionsIdOf(
            @Nullable final ConfigRecoveryContext context, @NonNull final Configuration configuration) {
        return context != null
                ? context.hapiPermissionsId()
                : FileUtilities.createFileID(
                        configuration.getConfigData(FilesConfig.class).hapiPermissions(), configuration);
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
