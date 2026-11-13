// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle.steps;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CONFIG_FILE_PART_UPLOADED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_FEE_FILE;
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
import com.hedera.node.app.spi.workflows.HandleException;
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
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
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
 * committed bytes back. The simple fee schedule is a partial exception: a status that could still be an
 * in-progress chunk upload is returned, but one the registered calculators cannot evaluate
 * ({@code INVALID_FEE_FILE}) throws instead — see {@link FeeManager#updateSimpleFees(Bytes, boolean)} for why this
 * depends on every schedule actually shipped fitting in a single transaction, so chunking is not in play today.
 */
@Singleton
public class SystemFileUpdates {
    private static final Logger logger = LogManager.getLogger(SystemFileUpdates.class);

    private final ConfigProviderImpl configProvider;
    private final ExchangeRateManager exchangeRateManager;
    private final FeeManager feeManager;
    private final ThrottleServiceManager throttleServiceManager;
    private final BlockNodeConnectionManager blockNodeConnectionManager;
    // Serializes block-node streaming reconciliation so that, when several writer-mode changes each queue a task, the
    // tasks run in submission (hence commit) order and the last one -- reading the newest committed mode -- sets the
    // final streaming state. A concurrent executor cannot guarantee that, so a stale task could finish last.
    private final Executor streamingReconciler;

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
        this(
                configProvider,
                exchangeRateManager,
                feeManager,
                throttleServiceManager,
                blockNodeConnectionManager,
                Executors.newSingleThreadExecutor(runnable -> {
                    final var thread = new Thread(runnable, "block-node-streaming-reconciler");
                    thread.setDaemon(true);
                    return thread;
                }));
    }

    /**
     * Visible for testing: injects the executor that serializes block-node streaming reconciliation, so a test can
     * supply a deterministic one.
     */
    SystemFileUpdates(
            @NonNull final ConfigProviderImpl configProvider,
            @NonNull final ExchangeRateManager exchangeRateManager,
            @NonNull final FeeManager feeManager,
            @NonNull final ThrottleServiceManager throttleServiceManager,
            @NonNull final BlockNodeConnectionManager blockNodeConnectionManager,
            @NonNull final Executor streamingReconciler) {
        this.configProvider = requireNonNull(configProvider, "configProvider must not be null");
        this.exchangeRateManager = requireNonNull(exchangeRateManager, "exchangeRateManager must not be null");
        this.feeManager = requireNonNull(feeManager, "feeManager must not be null");
        this.throttleServiceManager = requireNonNull(throttleServiceManager);
        this.blockNodeConnectionManager = requireNonNull(blockNodeConnectionManager);
        this.streamingReconciler = requireNonNull(streamingReconciler);
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
            // Strict, regardless of what the 1-arg overload defaults to: a schedule the registered calculators
            // cannot evaluate must never reach committed state, so a hard rejection here throws to roll the file
            // write itself back, instead of leaving the bytes in 0.0.113 for a later restart to pick up.
            final var status = feeManager.updateSimpleFees(FileUtilities.getFileContent(state, fileID), true);
            if (status == INVALID_FEE_FILE) {
                throw new HandleException(status);
            }
            return status;
        } else if (fileNum == filesConfig.exchangeRates()) {
            exchangeRateManager.update(FileUtilities.getFileContent(state, fileID), payer, state);
        } else if (fileNum == filesConfig.networkProperties()) {
            final var status = configListParseStatus(FileUtilities.getFileContent(state, fileID));
            updateConfig(configuration, ConfigType.NETWORK_PROPERTIES, state);
            // Rebuilding the configuration-dependent throttles discards their accumulated usage and restarts the
            // minimum congestion period, so only do it when a setting they are built from actually changed; an update
            // that leaves those settings alone (including one that rewrites the file with identical contents) must not
            // reset usage, whether or not it later rolls back.
            if (ThrottleServiceManager.throttleConfigurationChanged(configuration, configProvider.getConfiguration())) {
                throttleServiceManager.refreshThrottleConfiguration();
            }
            // A block node streaming transition is not applied here; the writer mode this update proposes is only
            // tentative until the transaction commits. The transition is queued from reconcileBlockNodeStreaming(),
            // against the committed writer mode.
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
     * from committed ids rather than from the in-memory state the rolled-back update left behind. For the facilities
     * whose update rebuilds throttles it carries {@code priorThrottleUsage}, the usage captured before the update. For
     * the simple-fees facility it carries {@code priorSimpleFees}, the schedule active before the update, so recovery
     * can restore it when the committed file holds only a partial upload chunk. These are auxiliary recovery data, not
     * part of the target's identity, so they are excluded from {@link #equals(Object)} and {@link #hashCode()}.
     *
     * @param facility the facility that was updated in memory
     * @param fileId the system file backing it
     * @param appliedContents the file contents the facility was updated from
     * @param configContext the pre-update configuration recovery context, or null for a non-configuration facility
     * @param priorSimpleFees the simple-fees schedule active before the update, or null for a non-simple-fees facility
     * or when the prior state was the genesis (no schedule)
     * @param priorThrottleUsage the throttle usage and congestion-level starts captured before the update, for the
     * facilities whose update rebuilds throttles (network properties and throttle definitions), otherwise null
     * @param priorWriterMode the block stream writer mode in effect before the update, for an update of a configuration
     * file (network properties or HAPI permissions), otherwise null
     * @param rolledBack whether a dispatch that updated this facility had its savepoint stack rolled back, in which
     * case recovery runs regardless of whether the committed file contents differ from the applied ones
     */
    public record ResyncTarget(
            @NonNull Facility facility,
            @NonNull FileID fileId,
            @NonNull Bytes appliedContents,
            @Nullable ConfigRecoveryContext configContext,
            @Nullable FeeSchedule priorSimpleFees,
            @Nullable ThrottleServiceManager.PreservedThrottleUsage priorThrottleUsage,
            @Nullable BlockStreamWriterMode priorWriterMode,
            boolean rolledBack) {

        public ResyncTarget(
                @NonNull final Facility facility, @NonNull final FileID fileId, @NonNull final Bytes appliedContents) {
            this(facility, fileId, appliedContents, null, null, null, null, false);
        }

        public ResyncTarget(
                @NonNull final Facility facility,
                @NonNull final FileID fileId,
                @NonNull final Bytes appliedContents,
                @Nullable final ConfigRecoveryContext configContext) {
            this(facility, fileId, appliedContents, configContext, null, null, null, false);
        }

        public ResyncTarget(
                @NonNull final Facility facility,
                @NonNull final FileID fileId,
                @NonNull final Bytes appliedContents,
                @Nullable final ConfigRecoveryContext configContext,
                @Nullable final FeeSchedule priorSimpleFees) {
            this(facility, fileId, appliedContents, configContext, priorSimpleFees, null, null, false);
        }

        /**
         * Returns a copy of this target carrying the given throttle usage baseline instead of its own, so targets
         * recovered together can restore the single baseline captured before the first of their updates.
         *
         * @param usage the throttle usage baseline to carry
         * @return the copy
         */
        public ResyncTarget withPriorThrottleUsage(
                @Nullable final ThrottleServiceManager.PreservedThrottleUsage usage) {
            return usage == priorThrottleUsage
                    ? this
                    : new ResyncTarget(
                            facility,
                            fileId,
                            appliedContents,
                            configContext,
                            priorSimpleFees,
                            usage,
                            priorWriterMode,
                            rolledBack);
        }

        /**
         * Returns a copy of this target marked as updated by a dispatch whose savepoint stack was rolled back, so the
         * facility must be re-derived from committed state even if the committed file contents equal the applied ones.
         *
         * @return the rolled-back copy
         */
        public ResyncTarget asRolledBack() {
            return rolledBack
                    ? this
                    : new ResyncTarget(
                            facility,
                            fileId,
                            appliedContents,
                            configContext,
                            priorSimpleFees,
                            priorThrottleUsage,
                            priorWriterMode,
                            true);
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
     * file IDs of both configuration files, so recovery reads them even if the update remapped {@code files.*} to other
     * file numbers.
     *
     * @param networkPropertiesId the committed network-properties file ID
     * @param hapiPermissionsId the committed HAPI-permissions file ID
     */
    public record ConfigRecoveryContext(
            @NonNull FileID networkPropertiesId, @NonNull FileID hapiPermissionsId) {}

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
        // For a configuration facility, capture the pre-update committed file IDs of both configuration files, so
        // recovery reads them even if this update remaps files.networkProperties/files.hapiPermissions.
        final ConfigRecoveryContext configContext = switch (facility) {
            case NETWORK_PROPERTIES, HAPI_PERMISSIONS ->
                new ConfigRecoveryContext(
                        FileUtilities.createFileID(filesConfig.networkProperties(), configuration),
                        FileUtilities.createFileID(filesConfig.hapiPermissions(), configuration));
            default -> null;
        };
        // For an update that may rebuild throttles, capture the usage and congestion-level starts the rebuild is about
        // to discard, so recovery can restore them if the update is rolled back.
        final ThrottleServiceManager.PreservedThrottleUsage priorThrottleUsage = switch (facility) {
            case NETWORK_PROPERTIES, THROTTLE_DEFINITIONS -> throttleServiceManager.captureThrottleUsage();
            default -> null;
        };
        // Both configuration files are sources of the configuration, so an update of either can change the writer
        // mode. Capture the writer mode in effect before it, so the block node streaming transition can be decided
        // against the committed mode once the transaction completes.
        final BlockStreamWriterMode priorWriterMode = switch (facility) {
            case NETWORK_PROPERTIES, HAPI_PERMISSIONS ->
                configuration.getConfigData(BlockStreamConfig.class).writerMode();
            default -> null;
        };
        // For a simple-fees update, capture the schedule active before it, so recovery can restore it if the committed
        // file turns out to be only a partial upload chunk that cannot be re-derived into a schedule.
        final FeeSchedule priorSimpleFees =
                facility == ResyncTarget.Facility.SIMPLE_FEES ? feeManager.currentSimpleFeesSchedule() : null;
        return Optional.of(new ResyncTarget(
                facility,
                fileID,
                FileUtilities.getFileContent(state, fileID),
                configContext,
                priorSimpleFees,
                priorThrottleUsage,
                priorWriterMode,
                false));
    }

    /**
     * Returns whether the given facility must be re-derived from the given state: because the dispatch that updated
     * it was rolled back, because the committed file contents differ from the contents the facility was updated from,
     * or (for exchange rates) because the in-memory midnight baseline no longer matches the committed singleton.
     *
     * @param state the state to compare against
     * @param target the facility, backing file and applied contents, as returned by
     * {@link #resyncTarget(State, TransactionBody)}
     * @return true if {@link #resyncFromState(State, ResyncTarget)} should run for the target
     */
    public boolean needsResync(@NonNull final State state, @NonNull final ResyncTarget target) {
        requireNonNull(state);
        requireNonNull(target);
        // The savepoint outcome is the primary signal: a rolled-back dispatch may have left the committed file bytes
        // unchanged (e.g. an update with identical contents) while still having changed in-memory state.
        if (target.rolledBack()) {
            return true;
        }
        final var fileChanged =
                !FileUtilities.getFileContent(state, target.fileId()).equals(target.appliedContents());
        // A system-admin (0.0.50) exchange-rate update moves the in-memory midnight baseline without changing the file,
        // so a rolled-back such update can leave the baseline out of step with committed state even when the file bytes
        // are unchanged. The move is persisted to the MIDNIGHT_RATES singleton in the same savepoint, so a committed
        // move keeps the singleton while a rolled-back one reverts it; if the in-memory baseline no longer matches the
        // committed singleton, the move was rolled back and the baseline must be re-derived even though the file did
        // not change.
        final var midnightBaselineDrifted =
                target.facility() == ResyncTarget.Facility.EXCHANGE_RATES && midnightBaselineDrifted(state);
        return fileChanged || midnightBaselineDrifted;
    }

    /**
     * Re-derives the given facility from the given state if {@link #needsResync(State, ResyncTarget)} says so. Must
     * be called on the handle thread once the dispatch's outcome is final.
     *
     * @param state the state to re-derive from
     * @param target the facility, backing file and applied contents, as returned by
     * {@link #resyncTarget(State, TransactionBody)}
     */
    public void resyncIfChanged(@NonNull final State state, @NonNull final ResyncTarget target) {
        if (needsResync(state, target)) {
            resyncFromState(state, target);
        }
    }

    /**
     * Keeps block node streaming on the configuration in effect now, which must be a committed one, until
     * {@link #releaseStreamingConfiguration()}. Called before a dispatch applies an update of a configuration file
     * (network properties or HAPI permissions), so the block buffer, which other threads drive, does not act on
     * settings the transaction may still roll back.
     */
    public void holdStreamingConfiguration() {
        blockNodeConnectionManager.holdConfiguration(configProvider.getConfiguration());
    }

    /**
     * Returns block node streaming to the current configuration. Called once the transaction's outcome is final.
     */
    public void releaseStreamingConfiguration() {
        blockNodeConnectionManager.releaseConfiguration();
    }

    /**
     * For a target that is a configuration file (network properties or HAPI permissions), queues the block node
     * streaming transition the committed writer mode calls for, if any; a no-op for other facilities. Must be called
     * once the transaction has committed, so the in-memory configuration is the committed one.
     *
     * @param target the facility target
     */
    public void reconcileBlockNodeStreaming(@NonNull final ResyncTarget target) {
        requireNonNull(target);
        switch (target.facility()) {
            case NETWORK_PROPERTIES, HAPI_PERMISSIONS -> reconcileBlockNodeStreaming(target.priorWriterMode());
            default -> {
                // No other facility can change the writer mode
            }
        }
    }

    /**
     * Queues a block node streaming transition if the committed writer mode differs from the one in effect before the
     * configuration update. Runs only once the transaction's outcome is final, so the in-memory configuration is the
     * committed one (re-derived by recovery if the update rolled back): a rolled-back writer-mode change therefore
     * queues nothing, instead of a transition followed by its reversal.
     *
     * @param priorWriterMode the writer mode in effect before the update, or null if unknown
     */
    private void reconcileBlockNodeStreaming(@Nullable final BlockStreamWriterMode priorWriterMode) {
        final var committedWriterMode = configProvider
                .getConfiguration()
                .getConfigData(BlockStreamConfig.class)
                .writerMode();
        if (priorWriterMode == null || priorWriterMode == committedWriterMode) {
            return;
        }
        if (committedWriterMode == BlockStreamWriterMode.FILE) {
            logger.info(
                    "Disabling gRPC Block Node streaming as the committed configuration changed writerMode from {} to FILE only",
                    priorWriterMode);
            applyBlockNodeStreaming(committedWriterMode);
        } else if (committedWriterMode == BlockStreamWriterMode.FILE_AND_GRPC) {
            logger.info(
                    "Enabling gRPC Block Node streaming as the committed configuration changed writerMode from {} to FILE_AND_GRPC",
                    priorWriterMode);
            applyBlockNodeStreaming(committedWriterMode);
        }
    }

    /**
     * Returns whether the in-memory exchange-rate midnight baseline differs from the committed {@code MIDNIGHT_RATES}
     * singleton, which signals that an admin update that moved the baseline was rolled back.
     *
     * @param state the committed state to read the singleton from
     * @return true if the in-memory baseline no longer matches the committed singleton
     */
    private boolean midnightBaselineDrifted(@NonNull final State state) {
        final var committedMidnightRates = state.getReadableStates(FeeService.NAME)
                .<ExchangeRateSet>getSingleton(V0490FeeSchema.MIDNIGHT_RATES_STATE_ID)
                .get();
        return !Objects.equals(committedMidnightRates, exchangeRateManager.midnightRates());
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
            case NETWORK_PROPERTIES -> recoverConfiguration(state, ConfigType.NETWORK_PROPERTIES, target);
            case HAPI_PERMISSIONS -> recoverConfiguration(state, ConfigType.API_PERMISSIONS, target);
            // Rebuild from the committed definitions when the rolled-back update changed them, and restore the usage
            // and congestion-level starts captured before the update into the buckets a rebuild discarded them from;
            // all nodes do this deterministically, so it does not cause divergence.
            case THROTTLE_DEFINITIONS -> {
                final var committedDefinitions = FileUtilities.getFileContent(state, fileID);
                final var priorUsage = target.priorThrottleUsage();
                if (priorUsage != null) {
                    throttleServiceManager.restoreThrottles(committedDefinitions, priorUsage);
                } else {
                    throttleServiceManager.recreateThrottles(committedDefinitions);
                }
            }
        }
    }

    /**
     * Re-derives the configuration from the committed configuration files, and brings the configuration-dependent
     * throttles back in step with it. Both configuration files are re-read, so either configuration facility can move
     * the settings those throttles are built from; they are rebuilt only if those settings differ between the
     * rolled-back and the committed configuration, so a recovery that leaves them alone costs no gas, bytes or
     * ops-duration usage, on the backend or at ingest.
     *
     * @param state the committed state to re-derive from
     * @param configType whether network properties or API permissions are being recovered
     * @param target the facility target
     */
    private void recoverConfiguration(
            @NonNull final State state, @NonNull final ConfigType configType, @NonNull final ResyncTarget target) {
        final var rolledBackConfiguration = configProvider.getConfiguration();
        final var context = target.configContext();
        updateConfig(
                rolledBackConfiguration,
                configType,
                state,
                networkPropertiesIdOf(context, rolledBackConfiguration),
                hapiPermissionsIdOf(context, rolledBackConfiguration));
        final var refresh = ThrottleServiceManager.throttleConfigurationChanged(
                rolledBackConfiguration, configProvider.getConfiguration());
        // A rolled-back network-properties update carries the usage captured before it, which is restored into the
        // buckets rebuilt since then (by the update itself, or just now); otherwise only a rebuild made just now
        // needs the current usage carried across it
        if (refresh || target.priorThrottleUsage() != null) {
            throttleServiceManager.recoverThrottleConfiguration(refresh, target.priorThrottleUsage());
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

    /**
     * Applies the given committed writer mode to block node streaming on a single-threaded executor. The handle thread
     * queues these tasks in commit order and the executor runs them in that same order, so the last task applies the
     * newest committed mode; the mode is passed in rather than read when the task runs, so a task never observes a
     * tentative configuration.
     *
     * @param committedWriterMode the committed writer mode to apply
     */
    private void applyBlockNodeStreaming(@NonNull final BlockStreamWriterMode committedWriterMode) {
        streamingReconciler.execute(() -> {
            if (committedWriterMode == BlockStreamWriterMode.FILE_AND_GRPC) {
                // Start for the committed mode, whatever a later, still tentative update makes the configuration say
                blockNodeConnectionManager.start(true);
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
