// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.throttle;

import static com.hedera.hapi.node.base.HederaFunctionality.CONTRACT_CALL;
import static com.hedera.hapi.node.base.HederaFunctionality.CONTRACT_CALL_LOCAL;
import static com.hedera.hapi.node.base.HederaFunctionality.CONTRACT_CREATE;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_CREATE;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_GET_ACCOUNT_BALANCE;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_TRANSFER;
import static com.hedera.hapi.node.base.HederaFunctionality.ETHEREUM_TRANSACTION;
import static com.hedera.hapi.node.base.HederaFunctionality.HOOK_DISPATCH;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_ASSOCIATE_TO_ACCOUNT;
import static com.hedera.hapi.util.HapiUtils.functionOf;
import static com.hedera.node.app.hapi.utils.CommonPbjConverters.fromPbj;
import static com.hedera.node.app.hapi.utils.ethereum.EthTxData.populateEthTxData;
import static com.hedera.node.app.hapi.utils.sysfiles.domain.throttling.ScaleFactor.ONE_TO_ONE;
import static com.hedera.node.app.hapi.utils.throttles.LeakyBucketThrottle.DEFAULT_BURST_SECONDS;
import static com.hedera.node.app.service.schedule.impl.handlers.HandlerUtility.childAsOrdinary;
import static com.hedera.node.app.service.token.AliasUtils.isAlias;
import static com.hedera.node.app.service.token.AliasUtils.isEntityNumAlias;
import static com.hedera.node.app.service.token.AliasUtils.isOfEvmAddressSize;
import static com.hedera.node.app.service.token.AliasUtils.isSerializedProtoKey;
import static com.hedera.node.app.throttle.ThrottleAccumulator.ThrottleType.FRONTEND_THROTTLE;
import static com.hedera.node.app.throttle.ThrottleAccumulator.ThrottleType.NOOP_THROTTLE;
import static java.util.Collections.emptyList;
import static java.util.Objects.requireNonNull;
import static org.hiero.hapi.fees.HighVolumePricingCalculator.HIGH_VOLUME_FUNCTIONS;

import com.google.common.annotations.VisibleForTesting;
import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.EvmHookCall;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.HookCall;
import com.hedera.hapi.node.base.NftTransfer;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.base.TransferList;
import com.hedera.hapi.node.contract.ContractCallLocalQuery;
import com.hedera.hapi.node.hooks.HookExecution;
import com.hedera.hapi.node.state.schedule.Schedule;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.hapi.node.token.TokenMintTransactionBody;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.hapi.node.transaction.ThrottleDefinitions;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.util.UnknownHederaFunctionality;
import com.hedera.node.app.hapi.utils.ethereum.EthTxData;
import com.hedera.node.app.hapi.utils.sysfiles.domain.throttling.ThrottleBucket;
import com.hedera.node.app.hapi.utils.sysfiles.domain.throttling.ThrottleGroup;
import com.hedera.node.app.hapi.utils.throttles.DeterministicThrottle;
import com.hedera.node.app.hapi.utils.throttles.LeakyBucketDeterministicThrottle;
import com.hedera.node.app.hapi.utils.throttles.OpsDurationDeterministicThrottle;
import com.hedera.node.app.service.entityid.EntityIdService;
import com.hedera.node.app.service.entityid.impl.ReadableEntityIdStoreImpl;
import com.hedera.node.app.service.schedule.ScheduleService;
import com.hedera.node.app.service.schedule.impl.ReadableScheduleStoreImpl;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.ReadableTokenRelationStore;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.store.ReadableStoreFactoryImpl;
import com.hedera.node.app.workflows.TransactionInfo;
import com.hedera.node.config.data.AccountsConfig;
import com.hedera.node.config.data.ContractsConfig;
import com.hedera.node.config.data.EntitiesConfig;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.node.config.data.JumboTransactionsConfig;
import com.hedera.node.config.data.LedgerConfig;
import com.hedera.node.config.data.SchedulingConfig;
import com.hedera.node.config.data.TokensConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.State;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Keeps track of the amount of usage of different TPS throttle categories and gas, and returns whether a given
 * transaction or query should be throttled based on that.
 * Meant to be used in single-threaded context only as part of the {@link com.hedera.node.app.workflows.handle.HandleWorkflow}.
 */
public class ThrottleAccumulator {
    private static final Logger log = LogManager.getLogger(ThrottleAccumulator.class);
    private static final Set<HederaFunctionality> CONTRACT_FUNCTIONS =
            EnumSet.of(CONTRACT_CALL_LOCAL, CONTRACT_CALL, CONTRACT_CREATE, ETHEREUM_TRANSACTION, HOOK_DISPATCH);
    private static final Set<HederaFunctionality> AUTO_CREATE_FUNCTIONS =
            EnumSet.of(CRYPTO_TRANSFER, ETHEREUM_TRANSACTION);
    private static final int UNKNOWN_NUM_IMPLICIT_CREATIONS = -1;

    private EnumMap<HederaFunctionality, ThrottleReqsManager> functionReqs = new EnumMap<>(HederaFunctionality.class);
    private EnumMap<HederaFunctionality, ThrottleReqsManager> highVolumeFunctionReqs =
            new EnumMap<>(HederaFunctionality.class);
    private boolean lastTxnWasGasThrottled;
    private LeakyBucketDeterministicThrottle bytesThrottle;
    private LeakyBucketDeterministicThrottle gasThrottle;
    private OpsDurationDeterministicThrottle contractOpsDurationThrottle;
    private List<DeterministicThrottle> activeThrottles = emptyList();
    private List<DeterministicThrottle> highVolumeActiveThrottles = emptyList();

    @Nullable
    private final ThrottleMetrics throttleMetrics;

    private final Supplier<Configuration> configSupplier;
    private final IntSupplier capacitySplitSource;
    private final ThrottleType throttleType;
    private final Verbose verbose;

    /**
     * Whether the accumulator should log verbose definitions.
     */
    public enum Verbose {
        YES,
        NO
    }

    public ThrottleAccumulator(
            @NonNull final Supplier<Configuration> configSupplier,
            @NonNull final IntSupplier capacitySplitSource,
            @NonNull final ThrottleType throttleType) {
        this(capacitySplitSource, configSupplier, throttleType, null, Verbose.NO);
    }

    public ThrottleAccumulator(
            @NonNull final IntSupplier capacitySplitSource,
            @NonNull final Supplier<Configuration> configSupplier,
            @NonNull final ThrottleType throttleType,
            @Nullable final ThrottleMetrics throttleMetrics,
            @NonNull final Verbose verbose) {
        this.configSupplier = requireNonNull(configSupplier, "configProvider must not be null");
        this.capacitySplitSource = requireNonNull(capacitySplitSource, "capacitySplitSource must not be null");
        this.throttleType = requireNonNull(throttleType, "throttleType must not be null");
        this.verbose = requireNonNull(verbose);
        this.throttleMetrics = throttleMetrics;
    }

    // For testing purposes, in practice the gas throttle is
    // lazy-initialized based on the configuration before handling
    // any transactions
    @VisibleForTesting
    public ThrottleAccumulator(
            @NonNull final IntSupplier capacitySplitSource,
            @NonNull final Supplier<Configuration> configSupplier,
            @NonNull final ThrottleType throttleType,
            @NonNull final ThrottleMetrics throttleMetrics,
            @NonNull final LeakyBucketDeterministicThrottle gasThrottle,
            @NonNull final LeakyBucketDeterministicThrottle bytesThrottle,
            @NonNull final OpsDurationDeterministicThrottle contractOsDurationThrottle) {
        this.configSupplier = requireNonNull(configSupplier, "configProvider must not be null");
        this.capacitySplitSource = requireNonNull(capacitySplitSource, "capacitySplitSource must not be null");
        this.throttleType = requireNonNull(throttleType, "throttleType must not be null");
        this.gasThrottle = requireNonNull(gasThrottle, "gasThrottle must not be null");
        this.bytesThrottle = requireNonNull(bytesThrottle, "bytesThrottle must not be null");
        this.contractOpsDurationThrottle =
                requireNonNull(contractOsDurationThrottle, "contractOsDurationThrottle must not be null");

        this.throttleMetrics = throttleMetrics;
        this.throttleMetrics.setupGasThrottleMetric(gasThrottle, configSupplier.get());
        this.verbose = Verbose.YES;
    }

    /**
     * Tries to claim throttle capacity for the given transaction and returns whether the transaction
     * should be throttled if there is no capacity.
     *
     * @param txnInfo the transaction to update the throttle requirements for
     * @param now the instant of time the transaction throttling should be checked for
     * @param state the current state of the node
     * @param throttleUsages if not null, a list to accumulate throttle usages into
     * @param gasThrottleAlwaysEnabled if set, gas throttle is always enforced within this call,
     * even if the throttleByGas configuration flag is off
     * @return whether the transaction should be throttled
     */
    public boolean checkAndEnforceThrottle(
            @NonNull final TransactionInfo txnInfo,
            @NonNull final Instant now,
            @NonNull final State state,
            @Nullable final List<ThrottleUsage> throttleUsages,
            final boolean gasThrottleAlwaysEnabled) {
        if (throttleType == NOOP_THROTTLE) {
            return false;
        }
        final int initialThrottleUsagesSize = throttleUsages == null ? 0 : throttleUsages.size();
        resetLastAllowedUse();
        lastTxnWasGasThrottled = false;

        if (shouldThrottleTxn(txnInfo, now, state, throttleUsages, gasThrottleAlwaysEnabled)) {
            reclaimLastAllowedUse();
            if (throttleUsages != null) {
                // Remove only the usages added during this check to avoid discarding prior usage records
                final int currentSize = throttleUsages.size();
                if (currentSize > initialThrottleUsagesSize) {
                    throttleUsages
                            .subList(initialThrottleUsagesSize, currentSize)
                            .clear();
                }
            }
            return true;
        }

        return false;
    }

    /**
     * Returns the available ops duration capacity for the execution at a given time.
     * Takes into account the amount leaked from the bucket up to the provided time.
     * Returns Long.MAX_VALUE is the configured throttle type is NOOP_THROTTLE.
     */
    public long availableOpsDurationCapacity(@NonNull final Instant now) {
        if (throttleType == NOOP_THROTTLE) {
            // Effectively unlimited in case of a no-op throttle
            return Long.MAX_VALUE;
        }
        return contractOpsDurationThrottle.capacityFree(now);
    }

    /**
     * Consumes a given amount of ops duration units from the throttle's capacity.
     * Takes into account the amount leaked from the bucket up to the provided time.
     * If the amount to consume is greater than the available amount then overfills the bucket without an error.
     */
    public void consumeOpsDurationThrottleCapacity(final long opsDurationUnitsToConsume, @NonNull final Instant now) {
        if (throttleType == NOOP_THROTTLE) {
            return;
        }
        contractOpsDurationThrottle.useCapacity(now, opsDurationUnitsToConsume);
    }

    /**
     * Updates the throttle requirements for the given query and returns whether the query should be throttled.
     *
     * @param queryFunction the functionality of the query
     * @param now the time at which the query is being processed
     * @param query the query to update the throttle requirements for
     * @param state the current state of the node
     * @param queryPayerId the payer id of the query
     * @return whether the query should be throttled
     */
    public boolean checkAndEnforceThrottle(
            @NonNull final HederaFunctionality queryFunction,
            @NonNull final Instant now,
            @NonNull final Query query,
            @NonNull final State state,
            @Nullable final AccountID queryPayerId) {
        if (throttleType == NOOP_THROTTLE) {
            return false;
        }
        final var configuration = configSupplier.get();
        if (throttleExempt(queryPayerId, configuration)) {
            return false;
        }
        if (isGasThrottled(queryFunction)) {
            final var enforceGasThrottle =
                    configuration.getConfigData(ContractsConfig.class).throttleThrottleByGas();

            return enforceGasThrottle
                    && !gasThrottle.allow(
                            now,
                            query.contractCallLocalOrElse(ContractCallLocalQuery.DEFAULT)
                                    .gas());
        }
        resetLastAllowedUse();
        final var manager = functionReqs.get(queryFunction);
        if (manager == null) {
            return true;
        }

        final boolean allReqMet;
        if (queryFunction == CRYPTO_GET_ACCOUNT_BALANCE
                && configuration.getConfigData(TokensConfig.class).countingGetBalanceThrottleEnabled()) {
            final var accountStore = new ReadableStoreFactoryImpl(state).readableStore(ReadableAccountStore.class);
            final var tokenConfig = configuration.getConfigData(TokensConfig.class);
            final int associationCount =
                    Math.clamp(getAssociationCount(query, accountStore), 1, tokenConfig.maxRelsPerInfoQuery());
            allReqMet = manager.allReqsMetAt(now, associationCount, ONE_TO_ONE, null);
        } else {
            allReqMet = manager.allReqsMetAt(now, null);
        }

        if (!allReqMet) {
            reclaimLastAllowedUse();
            return true;
        }
        return false;
    }

    private int getAssociationCount(@NonNull final Query query, @NonNull final ReadableAccountStore accountStore) {
        final var accountID = query.cryptogetAccountBalanceOrThrow().accountID();
        if (accountID != null) {
            final var account = accountStore.getAliasedAccountById(accountID);
            if (account != null) {
                return account.numberAssociations();
            }
        }
        return 0;
    }

    /**
     * Updates the throttle requirements for given number of transactions of same functionality and returns whether they should be throttled.
     *
     * @param n the number of transactions to consider
     * @param function the functionality type of the transactions
     * @param consensusTime the consensus time of the transaction
     * @return whether the transaction should be throttled
     */
    public boolean shouldThrottleNOfUnscaled(
            final int n, @NonNull final HederaFunctionality function, @NonNull final Instant consensusTime) {
        if (throttleType == NOOP_THROTTLE) {
            return false;
        }
        resetLastAllowedUse();
        final var manager = functionReqs.get(function);
        if (manager == null) {
            return true;
        }
        if (!manager.allReqsMetAt(consensusTime, n, ONE_TO_ONE, null)) {
            reclaimLastAllowedUse();
            return true;
        }

        return false;
    }

    /**
     * Undoes the claimed capacity for a number of transactions of the same functionality.
     *
     * @param n the number of transactions to consider
     * @param function the functionality type of the transactions
     */
    public void leakCapacityForNOfUnscaled(final int n, @NonNull final HederaFunctionality function) {
        if (throttleType == NOOP_THROTTLE) {
            return;
        }
        final var manager = Objects.requireNonNull(functionReqs.get(function));
        manager.undoClaimedReqsFor(n);
    }

    /**
     * Leaks the gas amount previously reserved for the given transaction.
     *
     * @param txnInfo the transaction to leak the gas for
     * @param value the amount of gas to leak
     */
    public void leakUnusedGasPreviouslyReserved(@NonNull final TransactionInfo txnInfo, final long value) {
        if (throttleType == NOOP_THROTTLE) {
            return;
        }
        final var configuration = configSupplier.get();
        if (throttleExempt(txnInfo.payerID(), configuration)) {
            return;
        }

        gasThrottle.leakUnusedGasPreviouslyReserved(value);
    }

    /**
     * Gets the current list of active throttles.
     *
     * @return the current list of active throttles
     */
    @NonNull
    public List<DeterministicThrottle> allActiveThrottles() {
        return activeThrottles;
    }

    /**
     * Gets the current list of active high-volume throttles.
     *
     * @return the current list of active high-volume throttles
     */
    @NonNull
    public List<DeterministicThrottle> allActiveHighVolumeThrottles() {
        return highVolumeActiveThrottles;
    }

    /**
     * Gets the current list of all TPS throttles (normal and high-volume).
     *
     * @return the current list of all TPS throttles
     */
    @NonNull
    public List<DeterministicThrottle> allActiveThrottlesIncludingHighVolume() {
        if (highVolumeActiveThrottles.isEmpty()) {
            return activeThrottles;
        }
        final var combined =
                new ArrayList<DeterministicThrottle>(activeThrottles.size() + highVolumeActiveThrottles.size());
        combined.addAll(activeThrottles);
        combined.addAll(highVolumeActiveThrottles);
        return combined;
    }

    /**
     * Gets the current list of active throttles for the given functionality.This is used for the utilization scaling multiplier
     * o congestion pricing.
     *
     * @param function the functionality to get the active throttles for
     * @return the current list of active throttles for the given functionality
     */
    @NonNull
    public List<DeterministicThrottle> activeThrottlesFor(@NonNull final HederaFunctionality function) {
        final var manager = functionReqs.get(function);
        if (manager == null) {
            return emptyList();
        } else {
            return manager.managedThrottles();
        }
    }

    /**
     * Indicates whether the last transaction was throttled by gas.
     *
     * @return whether the last transaction was throttled by gas
     */
    public boolean wasLastTxnGasThrottled() {
        return lastTxnWasGasThrottled;
    }

    /**
     * Checks if the given functionality is a contract function.
     *
     * @param functionality the functionality to check
     * @return whether the given functionality is a contract function
     */
    public static boolean isGasThrottled(@NonNull final HederaFunctionality functionality) {
        return CONTRACT_FUNCTIONS.contains(functionality);
    }

    public static boolean canAutoCreate(@NonNull final HederaFunctionality function) {
        return AUTO_CREATE_FUNCTIONS.contains(function);
    }

    public static boolean canAutoAssociate(@NonNull final HederaFunctionality function) {
        return function == CRYPTO_TRANSFER;
    }

    /**
     * Updates all metrics for the active throttles and the gas throttle
     */
    public void updateAllMetrics() {
        if (throttleMetrics != null) {
            throttleMetrics.updateAllMetrics();
        }
    }

    private boolean shouldThrottleTxn(
            @NonNull final TransactionInfo txnInfo,
            @NonNull final Instant now,
            @NonNull final State state,
            @Nullable final List<ThrottleUsage> throttleUsages,
            final boolean gasThrottleAlwaysEnabled) {
        final var function = txnInfo.functionality();
        final var txBody = txnInfo.txBody();
        final var configuration = configSupplier.get();
        final boolean isJumboTransactionsEnabled =
                configuration.getConfigData(JumboTransactionsConfig.class).isEnabled();

        // Note that by payer exempt from throttling we mean just that those transactions will not be throttled,
        // such payer accounts neither impact the throttles nor are they impacted by them
        // In the current mono-service implementation we have the same behavior, additionally it is
        // possible that transaction can also be exempt from affecting congestion levels separate from throttle
        // exemption
        // but this is only possible for the case of triggered transactions which is not yet implemented (see
        // MonoMultiplierSources.java)
        final boolean isPayerThrottleExempt = throttleExempt(txnInfo.payerID(), configuration);
        if (isPayerThrottleExempt) {
            return false;
        }

        final boolean throttleByGasFlag =
                configuration.getConfigData(ContractsConfig.class).throttleThrottleByGas();
        final boolean shouldThrottleByGas = throttleByGasFlag || gasThrottleAlwaysEnabled;
        if (shouldThrottleByGas && isGasExhausted(txnInfo, now, throttleUsages)) {
            lastTxnWasGasThrottled = true;
            return true;
        }

        if (isJumboTransactionsEnabled) {
            final var allowedHederaFunctionalities =
                    configuration.getConfigData(JumboTransactionsConfig.class).allowedHederaFunctionalities();
            if (allowedHederaFunctionalities.contains(fromPbj(txnInfo.functionality()))) {
                final var bytesUsage = txnInfo.signedTx().protobufSize();
                final var maxRegularTxnSize =
                        configuration.getConfigData(HederaConfig.class).transactionMaxBytes();

                final var excessBytes = bytesUsage > maxRegularTxnSize ? bytesUsage - maxRegularTxnSize : 0;
                if (shouldThrottleBasedExcessBytes(excessBytes, now, throttleUsages)) {
                    return true;
                }
            }
        }

        // Check if this is a high-volume transaction and use appropriate throttle bucket
        final boolean isHighVolumeTxn = txBody.highVolume();
        final boolean useHighVolumeBucket = isHighVolumeTxn && HIGH_VOLUME_FUNCTIONS.contains(function);
        final var targetFunctionReqs = useHighVolumeBucket ? highVolumeFunctionReqs : functionReqs;
        final var manager = targetFunctionReqs.get(function);

        // If high-volume flag is set but no high-volume bucket exists for this function,
        // fall back to normal throttle bucket
        final var effectiveManager = (manager == null && useHighVolumeBucket) ? functionReqs.get(function) : manager;

        if (effectiveManager == null) {
            return true;
        }

        return switch (function) {
            case SCHEDULE_CREATE -> shouldThrottleScheduleCreate(effectiveManager, txnInfo, now, state, throttleUsages);
            case TOKEN_MINT ->
                shouldThrottleMint(effectiveManager, txBody.tokenMintOrThrow(), now, configuration, throttleUsages);
            case CRYPTO_TRANSFER -> {
                final var accountStore = new ReadableStoreFactoryImpl(state).readableStore(ReadableAccountStore.class);
                final var relationStore =
                        new ReadableStoreFactoryImpl(state).readableStore(ReadableTokenRelationStore.class);
                yield shouldThrottleCryptoTransfer(
                        effectiveManager,
                        now,
                        configuration,
                        getImplicitCreationsCount(txBody, accountStore),
                        getAutoAssociationsCount(txBody, relationStore),
                        throttleUsages,
                        useHighVolumeBucket);
            }
            case ETHEREUM_TRANSACTION -> {
                final var accountStore = new ReadableStoreFactoryImpl(state).readableStore(ReadableAccountStore.class);
                yield shouldThrottleEthTxn(
                        effectiveManager,
                        now,
                        getImplicitCreationsCount(txBody, accountStore),
                        throttleUsages,
                        useHighVolumeBucket);
            }
            default -> !effectiveManager.allReqsMetAt(now, throttleUsages);
        };
    }

    private boolean shouldThrottleScheduleCreate(
            final ThrottleReqsManager manager,
            final TransactionInfo txnInfo,
            final Instant now,
            final State state,
            List<ThrottleUsage> throttleUsages) {
        final var txnBody = txnInfo.txBody();
        final var op = txnBody.scheduleCreateOrThrow();
        if (!op.hasScheduledTransactionBody()) {
            return true;
        }
        final var scheduled = op.scheduledTransactionBodyOrThrow();
        final var schedule = Schedule.newBuilder()
                .originalCreateTransaction(txnBody)
                .payerAccountId(txnInfo.payerID())
                .scheduledTransaction(scheduled)
                .build();
        final TransactionBody innerTxn;
        final HederaFunctionality scheduledFunction;
        try {
            innerTxn = childAsOrdinary(schedule);
            scheduledFunction = functionOf(innerTxn);
        } catch (HandleException | UnknownHederaFunctionality ex) {
            log.debug("ScheduleCreate was associated with an invalid txn.", ex);
            return true;
        }
        // maintain legacy behaviour
        final var config = configSupplier.get();
        final var schedulingConfig = config.getConfigData(SchedulingConfig.class);
        if (!schedulingConfig.longTermEnabled()) {
            if (scheduledFunction == CRYPTO_TRANSFER) {
                final var transfer = scheduled.cryptoTransferOrThrow();
                if (usesAliases(transfer)) {
                    final var accountStore =
                            new ReadableStoreFactoryImpl(state).readableStore(ReadableAccountStore.class);
                    final var transferTxnBody = TransactionBody.newBuilder()
                            .cryptoTransfer(transfer)
                            .build();
                    final int implicitCreationsCount = getImplicitCreationsCount(transferTxnBody, accountStore);
                    if (implicitCreationsCount > 0) {
                        return shouldThrottleImplicitCreations(implicitCreationsCount, now, throttleUsages, false);
                    }
                }
            }
            return !manager.allReqsMetAt(now, throttleUsages);
        } else {
            // We first enforce the limit on the ScheduleCreate TPS
            if (!manager.allReqsMetAt(now, throttleUsages)) {
                return true;
            }
            // And then at ingest, ensure that not too many schedules will expire in a given second
            if (throttleType == FRONTEND_THROTTLE) {
                final long expiry;
                if (op.waitForExpiry()) {
                    expiry = op.expirationTimeOrElse(Timestamp.DEFAULT).seconds();
                } else {
                    final var ledgerConfig = config.getConfigData(LedgerConfig.class);
                    expiry = Optional.ofNullable(txnInfo.transactionID())
                                    .orElse(TransactionID.DEFAULT)
                                    .transactionValidStartOrElse(Timestamp.DEFAULT)
                                    .seconds()
                            + ledgerConfig.scheduleTxExpiryTimeSecs();
                }
                final var entityIdStore = new ReadableEntityIdStoreImpl(state.getReadableStates(EntityIdService.NAME));
                final var scheduleStore =
                        new ReadableScheduleStoreImpl(state.getReadableStates(ScheduleService.NAME), entityIdStore);
                final var numScheduled = scheduleStore.numTransactionsScheduledAt(expiry);
                return numScheduled >= schedulingConfig.maxTxnPerSec();
            }
            return false;
        }
    }

    private static boolean throttleExempt(
            @Nullable final AccountID accountID, @NonNull final Configuration configuration) {
        final long maxThrottleExemptNum =
                configuration.getConfigData(AccountsConfig.class).lastThrottleExempt();
        if (accountID != null) {
            final var accountNum = accountID.accountNumOrElse(0L);
            return 1L <= accountNum && accountNum <= maxThrottleExemptNum;
        }
        return false;
    }

    private void reclaimLastAllowedUse() {
        activeThrottles.forEach(DeterministicThrottle::reclaimLastAllowedUse);
        highVolumeActiveThrottles.forEach(DeterministicThrottle::reclaimLastAllowedUse);
        gasThrottle.reclaimLastAllowedUse();
    }

    private void resetLastAllowedUse() {
        activeThrottles.forEach(DeterministicThrottle::resetLastAllowedUse);
        highVolumeActiveThrottles.forEach(DeterministicThrottle::resetLastAllowedUse);
        gasThrottle.resetLastAllowedUse();
    }

    /**
     * Returns the gas limit for a contract transaction.
     *
     * @param txnBody the transaction body
     * @param function the functionality
     * @return the gas limit for a contract transaction
     */
    private long getGasLimitForContractTx(
            @NonNull final TransactionBody txnBody, @NonNull final HederaFunctionality function) {
        final long nominalGas =
                switch (function) {
                    case CONTRACT_CREATE ->
                        txnBody.contractCreateInstanceOrThrow().gas();
                    case CONTRACT_CALL -> txnBody.contractCallOrThrow().gas();
                    case ETHEREUM_TRANSACTION ->
                        Optional.of(txnBody.ethereumTransactionOrThrow()
                                        .ethereumData()
                                        .toByteArray())
                                .map(EthTxData::populateEthTxData)
                                .map(EthTxData::gasLimit)
                                .orElse(0L);
                    case HOOK_DISPATCH ->
                        txnBody.hookDispatchOrThrow()
                                .executionOrElse(HookExecution.DEFAULT)
                                .callOrElse(HookCall.DEFAULT)
                                .evmHookCallOrElse(EvmHookCall.DEFAULT)
                                .gasLimit();
                    default -> 0L;
                };
        // Interpret negative gas as overflow
        return nominalGas < 0 ? Long.MAX_VALUE : nominalGas;
    }

    private boolean isGasExhausted(
            @NonNull final TransactionInfo txnInfo,
            @NonNull final Instant now,
            @Nullable final List<ThrottleUsage> throttleUsages) {
        if (isGasThrottled(txnInfo.functionality())) {
            final long amount = getGasLimitForContractTx(txnInfo.txBody(), txnInfo.functionality());
            final boolean answer = !gasThrottle.allow(now, amount);
            if (!answer && throttleUsages != null) {
                throttleUsages.add(new BucketThrottleUsage(gasThrottle, amount));
            }
            return answer;
        } else {
            gasThrottle.leakUntil(now);
            return false;
        }
    }

    private boolean shouldThrottleMint(
            @NonNull final ThrottleReqsManager manager,
            @NonNull final TokenMintTransactionBody op,
            @NonNull final Instant now,
            @NonNull final Configuration configuration,
            List<ThrottleUsage> throttleUsages) {
        final int numNfts = op.metadata().size();
        if (numNfts == 0) {
            return !manager.allReqsMetAt(now, throttleUsages);
        } else {
            final var nftsMintThrottleScaleFactor =
                    configuration.getConfigData(TokensConfig.class).nftsMintThrottleScaleFactor();
            return !manager.allReqsMetAt(now, numNfts, nftsMintThrottleScaleFactor, throttleUsages);
        }
    }

    private boolean shouldThrottleCryptoTransfer(
            @NonNull final ThrottleReqsManager manager,
            @NonNull final Instant now,
            @NonNull final Configuration configuration,
            final int implicitCreationsCount,
            final int autoAssociationsCount,
            List<ThrottleUsage> throttleUsages,
            final boolean useHighVolumeBucket) {
        final boolean unlimitedAutoAssociations =
                configuration.getConfigData(EntitiesConfig.class).unlimitedAutoAssociationsEnabled();
        if (implicitCreationsCount > 0) {
            return shouldThrottleBasedOnImplicitCreations(
                    manager, implicitCreationsCount, now, throttleUsages, useHighVolumeBucket);
        } else if (unlimitedAutoAssociations && autoAssociationsCount > 0) {
            return shouldThrottleBasedOnAutoAssociations(
                    manager, autoAssociationsCount, now, throttleUsages, useHighVolumeBucket);
        } else {
            return !manager.allReqsMetAt(now, throttleUsages);
        }
    }

    private boolean shouldThrottleEthTxn(
            @NonNull final ThrottleReqsManager manager,
            @NonNull final Instant now,
            final int implicitCreationsCount,
            @Nullable final List<ThrottleUsage> throttleUsages,
            final boolean useHighVolumeBucket) {
        return shouldThrottleBasedOnImplicitCreations(
                manager, implicitCreationsCount, now, throttleUsages, useHighVolumeBucket);
    }

    public int getImplicitCreationsCount(
            @NonNull final TransactionBody txnBody, @NonNull final ReadableAccountStore accountStore) {
        int implicitCreationsCount = 0;
        if (txnBody.hasEthereumTransaction()) {
            final var ethTxData = populateEthTxData(
                    txnBody.ethereumTransaction().ethereumData().toByteArray());
            if (ethTxData == null) {
                return UNKNOWN_NUM_IMPLICIT_CREATIONS;
            }
            final var config = configSupplier.get().getConfigData(HederaConfig.class);
            final boolean doesNotExist =
                    !accountStore.containsAlias(config.shard(), config.realm(), Bytes.wrap(ethTxData.to()));
            if (doesNotExist && ethTxData.value().compareTo(BigInteger.ZERO) > 0) {
                implicitCreationsCount++;
            }
        } else {
            final var cryptoTransferBody = txnBody.cryptoTransfer();
            if (cryptoTransferBody == null) {
                return 0;
            }

            implicitCreationsCount += hbarAdjustsImplicitCreationsCount(accountStore, cryptoTransferBody);
            implicitCreationsCount += tokenAdjustsImplicitCreationsCount(accountStore, cryptoTransferBody);
        }

        return implicitCreationsCount;
    }

    public int getAutoAssociationsCount(
            @NonNull final TransactionBody txnBody, @NonNull final ReadableTokenRelationStore relationStore) {
        int autoAssociationsCount = 0;
        final var cryptoTransferBody = txnBody.cryptoTransfer();
        if (cryptoTransferBody == null || cryptoTransferBody.tokenTransfers().isEmpty()) {
            return 0;
        }
        for (var transfer : cryptoTransferBody.tokenTransfers()) {
            final var tokenID = transfer.token();
            autoAssociationsCount += (int) transfer.transfers().stream()
                    .filter(accountAmount -> accountAmount.amount() > 0)
                    .map(AccountAmount::accountID)
                    .filter(accountID -> hasNoRelation(relationStore, accountID, tokenID))
                    .count();
            autoAssociationsCount += (int) transfer.nftTransfers().stream()
                    .map(NftTransfer::receiverAccountID)
                    .filter(receiverID -> hasNoRelation(relationStore, receiverID, tokenID))
                    .count();
        }
        return autoAssociationsCount;
    }

    private boolean hasNoRelation(
            @NonNull ReadableTokenRelationStore relationStore, @NonNull AccountID accountID, @NonNull TokenID tokenID) {
        return relationStore.get(accountID, tokenID) == null;
    }

    private int hbarAdjustsImplicitCreationsCount(
            @NonNull final ReadableAccountStore accountStore,
            @NonNull final CryptoTransferTransactionBody cryptoTransferBody) {
        if (cryptoTransferBody.transfers() == null) {
            return 0;
        }

        int implicitCreationsCount = 0;
        for (var adjust : cryptoTransferBody.transfers().accountAmounts()) {
            if (referencesAliasNotInUse(adjust.accountIDOrElse(AccountID.DEFAULT), accountStore)
                    && isPlausibleAutoCreate(adjust)) {
                implicitCreationsCount++;
            }
        }

        return implicitCreationsCount;
    }

    private int tokenAdjustsImplicitCreationsCount(
            @NonNull final ReadableAccountStore accountStore,
            @NonNull final CryptoTransferTransactionBody cryptoTransferBody) {
        if (cryptoTransferBody.tokenTransfers().isEmpty()) {
            return 0;
        }

        int implicitCreationsCount = 0;
        for (var tokenAdjust : cryptoTransferBody.tokenTransfers()) {
            for (final var adjust : tokenAdjust.transfers()) {
                if (adjust.hasAccountID()) {
                    if (referencesAliasNotInUse(adjust.accountIDOrThrow(), accountStore)
                            && isPlausibleAutoCreate(adjust)) {
                        implicitCreationsCount++;
                    }
                }
            }

            for (final var change : tokenAdjust.nftTransfers()) {
                if (change.hasReceiverAccountID()) {
                    if (referencesAliasNotInUse(change.receiverAccountIDOrThrow(), accountStore)
                            && isPlausibleAutoCreate(change)) {
                        implicitCreationsCount++;
                    }
                }
            }
        }

        return implicitCreationsCount;
    }

    private boolean usesAliases(final CryptoTransferTransactionBody transferBody) {
        for (var adjust : transferBody.transfersOrElse(TransferList.DEFAULT).accountAmounts()) {
            if (isAlias(adjust.accountIDOrElse(AccountID.DEFAULT))) {
                return true;
            }
        }

        for (var tokenAdjusts : transferBody.tokenTransfers()) {
            for (var ownershipChange : tokenAdjusts.nftTransfers()) {
                if (isAlias(ownershipChange.senderAccountIDOrElse(AccountID.DEFAULT))
                        || isAlias(ownershipChange.receiverAccountIDOrElse(AccountID.DEFAULT))) {
                    return true;
                }
            }
            for (var tokenAdjust : tokenAdjusts.transfers()) {
                if (isAlias(tokenAdjust.accountIDOrElse(AccountID.DEFAULT))) {
                    return true;
                }
            }
        }

        return false;
    }

    private boolean referencesAliasNotInUse(
            @NonNull final AccountID idOrAlias, @NonNull final ReadableAccountStore accountStore) {
        if (isAlias(idOrAlias)) {
            final var alias = idOrAlias.aliasOrElse(Bytes.EMPTY);
            if (isOfEvmAddressSize(alias) && isEntityNumAlias(alias)) {
                return false;
            }
            return accountStore.getAccountIDByAlias(idOrAlias.shardNum(), idOrAlias.realmNum(), alias) == null;
        }
        return false;
    }

    private boolean isPlausibleAutoCreate(@NonNull final AccountAmount adjust) {
        return isPlausibleAutoCreate(
                adjust.amount(), adjust.accountIDOrElse(AccountID.DEFAULT).aliasOrElse(Bytes.EMPTY));
    }

    private boolean isPlausibleAutoCreate(@NonNull final NftTransfer change) {
        return isPlausibleAutoCreate(
                change.serialNumber(),
                change.receiverAccountIDOrElse(AccountID.DEFAULT).aliasOrElse(Bytes.EMPTY));
    }

    private boolean isPlausibleAutoCreate(final long assetChange, @NonNull final Bytes alias) {
        if (assetChange > 0) {
            if (isSerializedProtoKey(alias)) {
                return true;
            } else {
                return isOfEvmAddressSize(alias);
            }
        }

        return false;
    }

    private boolean shouldThrottleBasedOnImplicitCreations(
            @NonNull final ThrottleReqsManager manager,
            final int implicitCreationsCount,
            @NonNull final Instant now,
            @Nullable final List<ThrottleUsage> throttleUsages,
            final boolean useHighVolumeBucket) {
        return (implicitCreationsCount == 0)
                ? !manager.allReqsMetAt(now, throttleUsages)
                : shouldThrottleImplicitCreations(implicitCreationsCount, now, throttleUsages, useHighVolumeBucket);
    }

    private boolean shouldThrottleBasedExcessBytes(
            final long bytesUsed, @NonNull final Instant now, @Nullable final List<ThrottleUsage> throttleUsages) {
        // If the bucket doesn't allow the txn enforce the throttle
        final boolean shouldThrottle = bytesThrottle != null && !bytesThrottle.allow(now, bytesUsed);
        // If the bucket allows the txn, record the usage
        if (!shouldThrottle && throttleUsages != null) {
            throttleUsages.add(new BucketThrottleUsage(bytesThrottle, bytesUsed));
        }
        return shouldThrottle;
    }

    private boolean shouldThrottleBasedOnAutoAssociations(
            @NonNull final ThrottleReqsManager manager,
            final int autoAssociations,
            @NonNull final Instant now,
            @Nullable final List<ThrottleUsage> throttleUsages,
            final boolean useHighVolumeBucket) {
        return (autoAssociations == 0)
                ? !manager.allReqsMetAt(now, throttleUsages)
                : shouldThrottleAutoAssociations(autoAssociations, now, throttleUsages, useHighVolumeBucket);
    }

    /**
     * Returns whether the given number of implicit creations should be throttled.
     *
     * @param n the number of implicit creations
     * @param now the current time
     * @param throttleUsages the list of throttle usages to update
     * @param useHighVolumeBucket whether to use the high-volume bucket
     * @return whether the given number of implicit creations should be throttled
     */
    private boolean shouldThrottleImplicitCreations(
            final int n,
            @NonNull final Instant now,
            @Nullable final List<ThrottleUsage> throttleUsages,
            final boolean useHighVolumeBucket) {
        final var manager = getReqsManager(CRYPTO_CREATE, useHighVolumeBucket);
        return manager == null || !manager.allReqsMetAt(now, n, ONE_TO_ONE, throttleUsages);
    }

    /**
     * Returns whether the given number of auto associations should be throttled.
     *
     * @param n the number of auto associations
     * @param now the current time
     * @param throttleUsages the list of throttle usages to update
     * @param useHighVolumeBucket whether to use the high-volume bucket
     * @return whether the given number of auto associations should be throttled
     */
    private boolean shouldThrottleAutoAssociations(
            final int n,
            @NonNull final Instant now,
            @Nullable final List<ThrottleUsage> throttleUsages,
            final boolean useHighVolumeBucket) {
        final var manager = getReqsManager(TOKEN_ASSOCIATE_TO_ACCOUNT, useHighVolumeBucket);
        return manager == null || !manager.allReqsMetAt(now, n, ONE_TO_ONE, throttleUsages);
    }

    /**
     * Returns the throttle requirements manager for the given functionality and high-volume flag.
     *
     * @param function the functionality to get the manager for
     * @param useHighVolumeBucket whether to use the high-volume bucket
     * @return the throttle requirements manager, or null if none exists
     */
    private ThrottleReqsManager getReqsManager(final HederaFunctionality function, final boolean useHighVolumeBucket) {
        return useHighVolumeBucket
                ? hasHighVolumeThrottleFor(function) ? highVolumeFunctionReqs.get(function) : functionReqs.get(function)
                : functionReqs.get(function);
    }

    /**
     * Returns the current instantaneous utilization percentage of the high-volume throttle
     * for the given functionality, without accounting for time-based capacity leakage.
     * The utilization is expressed in basis points (0 to 10,000), where 10,000 = 100%.
     *
     * @param function the functionality to get the utilization for
     * @return the utilization percentage in basis points (0 to 10,000),
     * or 0 if no high-volume throttle exists for the functionality
     */
    public int getHighVolumeThrottleInstantaneousUtilization(@NonNull final HederaFunctionality function) {
        requireNonNull(function);

        final var manager = highVolumeFunctionReqs.get(function);
        if (manager == null) {
            return 0;
        }

        // Get the maximum utilization across all throttles for this functionality
        double maxUtilization = 0.0;
        for (final var throttle : manager.managedThrottles()) {
            final double utilization = throttle.instantaneousPercentUsed();
            maxUtilization = Math.max(maxUtilization, utilization);
        }

        // instantaneousPercentUsed() returns percent in [0,100], convert to basis points [0,10_000]
        return (int) Math.min(10_000, Math.round(maxUtilization * 100));
    }

    /**
     * Rebuilds the throttle requirements based on the given throttle definitions.
     *
     * @param defs the throttle definitions to rebuild the throttle requirements based on
     */
    public void rebuildFor(@NonNull final ThrottleDefinitions defs) {
        List<DeterministicThrottle> newActiveThrottles = new ArrayList<>();
        List<DeterministicThrottle> newHighVolumeActiveThrottles = new ArrayList<>();
        EnumMap<HederaFunctionality, List<Pair<DeterministicThrottle, Integer>>> reqLists =
                new EnumMap<>(HederaFunctionality.class);
        EnumMap<HederaFunctionality, List<Pair<DeterministicThrottle, Integer>>> highVolumeReqLists =
                new EnumMap<>(HederaFunctionality.class);

        for (var bucket : defs.throttleBuckets()) {
            try {
                final var isHighVolume = bucket.highVolume();
                final var utilThrottleBucket = new ThrottleBucket<>(
                        bucket.burstPeriodMs(),
                        bucket.name(),
                        bucket.throttleGroups().stream()
                                .map(this::hapiGroupFromPbj)
                                .toList(),
                        isHighVolume);
                var mapping = utilThrottleBucket.asThrottleMapping(capacitySplitSource.getAsInt());
                var throttle = mapping.getLeft();
                var reqs = mapping.getRight();

                // Route to appropriate req lists based on high-volume flag
                final var targetReqLists = isHighVolume ? highVolumeReqLists : reqLists;
                final var targetThrottles = isHighVolume ? newHighVolumeActiveThrottles : newActiveThrottles;

                for (var req : reqs) {
                    targetReqLists
                            .computeIfAbsent(req.getLeft(), ignore -> new ArrayList<>())
                            .add(Pair.of(throttle, req.getRight()));
                }
                targetThrottles.add(throttle);
            } catch (IllegalStateException badBucket) {
                log.error("When constructing bucket '{}' from state: {}", bucket.name(), badBucket.getMessage());
            }
        }
        EnumMap<HederaFunctionality, ThrottleReqsManager> newFunctionReqs = new EnumMap<>(HederaFunctionality.class);
        reqLists.forEach((function, reqs) -> newFunctionReqs.put(function, new ThrottleReqsManager(reqs)));

        EnumMap<HederaFunctionality, ThrottleReqsManager> newHighVolumeFunctionReqs =
                new EnumMap<>(HederaFunctionality.class);
        highVolumeReqLists.forEach(
                (function, reqs) -> newHighVolumeFunctionReqs.put(function, new ThrottleReqsManager(reqs)));

        functionReqs = newFunctionReqs;
        highVolumeFunctionReqs = newHighVolumeFunctionReqs;
        activeThrottles = newActiveThrottles;
        highVolumeActiveThrottles = newHighVolumeActiveThrottles;

        if (throttleMetrics != null) {
            final var configuration = configSupplier.get();
            throttleMetrics.setupThrottleMetrics(activeThrottles, configuration);
            // Also setup metrics for high-volume throttles
            throttleMetrics.setupThrottleMetrics(highVolumeActiveThrottles, configuration);
        }

        logResolvedDefinitions(capacitySplitSource.getAsInt());
    }

    /**
     * Rebuilds the gas throttle based on the current configuration.
     */
    public void applyGasConfig() {
        final var configuration = configSupplier.get();
        final var contractsConfig = configuration.getConfigData(ContractsConfig.class);
        final var maxGasPerSec = maxGasPerSecOf(contractsConfig);
        if (contractsConfig.throttleThrottleByGas() && maxGasPerSec == 0) {
            log.warn("{} gas throttling enabled, but limited to 0 gas/sec", throttleType.name());
        }

        gasThrottle = new LeakyBucketDeterministicThrottle(maxGasPerSec, "Gas", DEFAULT_BURST_SECONDS);
        if (throttleMetrics != null) {
            throttleMetrics.setupGasThrottleMetric(gasThrottle, configuration);
        }
        if (verbose == Verbose.YES) {
            log.info(
                    "Resolved {} gas throttle -\n {} gas/sec (throttling {})",
                    throttleType.name(),
                    gasThrottle.capacity(),
                    (contractsConfig.throttleThrottleByGas() ? "ON" : "OFF"));
        }
    }

    private long maxGasPerSecOf(@NonNull final ContractsConfig contractsConfig) {
        return throttleType.equals(ThrottleType.BACKEND_THROTTLE)
                ? contractsConfig.maxGasPerSecBackend()
                : contractsConfig.maxGasPerSec();
    }

    public void applyBytesConfig() {
        final var configuration = configSupplier.get();
        final var jumboConfig = configuration.getConfigData(JumboTransactionsConfig.class);
        final var bytesPerSec = jumboConfig.maxBytesPerSec();
        if (jumboConfig.isEnabled() && bytesPerSec == 0) {
            log.warn("{} jumbo transactions are enabled, but limited to 0 bytes/sec", throttleType.name());
        }
        bytesThrottle = new LeakyBucketDeterministicThrottle(bytesPerSec, "Bytes", DEFAULT_BURST_SECONDS);
        if (throttleMetrics != null) {
            throttleMetrics.setupBytesThrottleMetric(bytesThrottle, configuration);
        }
        if (verbose == Verbose.YES) {
            log.info(
                    "Resolved {} bytes throttle -\n {} bytes/sec (throttling {})",
                    throttleType.name(),
                    bytesThrottle.capacity(),
                    (jumboConfig.isEnabled() ? "ON" : "OFF"));
        }
    }

    public void applyDurationConfig() {
        final var configuration = configSupplier.get();
        final var contractConfig = configuration.getConfigData(ContractsConfig.class);
        final var opsDurationThrottleCapacity = contractConfig.opsDurationThrottleCapacity();
        final var opsDurationThrottleUnitsFreedPerSecond = contractConfig.opsDurationThrottleUnitsFreedPerSecond();
        if (contractConfig.throttleThrottleByOpsDuration()
                && (opsDurationThrottleCapacity == 0 || opsDurationThrottleUnitsFreedPerSecond == 0)) {
            log.info(
                    "{} ops duration throttles are enabled, but either capacity or leak rate is 0",
                    throttleType.name());
        }
        contractOpsDurationThrottle = new OpsDurationDeterministicThrottle(
                "OpsDuration", opsDurationThrottleCapacity, opsDurationThrottleUnitsFreedPerSecond);
        if (throttleMetrics != null) {
            throttleMetrics.setupOpsDurationMetric(contractOpsDurationThrottle, configuration);
        }
        if (verbose == Verbose.YES) {
            log.info(
                    "Resolved {} ops duration throttle -\n {} ops duration/sec (throttling {})",
                    throttleType.name(),
                    contractOpsDurationThrottle.capacity(),
                    (contractConfig.throttleThrottleByOpsDuration() ? "ON" : "OFF"));
        }
    }

    @NonNull
    private ThrottleGroup<HederaFunctionality> hapiGroupFromPbj(
            @NonNull final com.hedera.hapi.node.transaction.ThrottleGroup pbjThrottleGroup) {
        return new ThrottleGroup<>(pbjThrottleGroup.milliOpsPerSec(), pbjThrottleGroup.operations());
    }

    private void logResolvedDefinitions(final int capacitySplit) {
        if (verbose != Verbose.YES) {
            return;
        }
        var sb = new StringBuilder("Resolved ")
                .append(throttleType.name())
                .append(" ")
                .append("(after splitting capacity ")
                .append(capacitySplit)
                .append(" ways) - \n");
        functionReqs.entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getKey().toString()))
                .forEach(entry -> {
                    var function = entry.getKey();
                    var manager = entry.getValue();
                    sb.append("  ")
                            .append(function)
                            .append(": ")
                            .append(manager.asReadableRequirements())
                            .append("\n");
                });

        // Log high-volume throttle definitions if any exist
        if (!highVolumeFunctionReqs.isEmpty()) {
            sb.append("\nHigh-Volume Throttles:\n");
            highVolumeFunctionReqs.entrySet().stream()
                    .sorted(Comparator.comparing(entry -> entry.getKey().toString()))
                    .forEach(entry -> {
                        var function = entry.getKey();
                        var manager = entry.getValue();
                        sb.append("  ")
                                .append(function)
                                .append(" (high-volume): ")
                                .append(manager.asReadableRequirements())
                                .append("\n");
                    });
        }
        log.info("{}", () -> sb.toString().trim());
    }

    /**
     * Gets the gas throttle.
     */
    public @NonNull LeakyBucketDeterministicThrottle gasLimitThrottle() {
        return requireNonNull(gasThrottle, "");
    }

    /**
     * Gets the bytes throttle.
     */
    public @NonNull LeakyBucketDeterministicThrottle bytesLimitThrottle() {
        return requireNonNull(bytesThrottle, "");
    }

    /**
     * Gets the ops duration throttle.
     */
    public @NonNull OpsDurationDeterministicThrottle opsDurationThrottle() {
        return requireNonNull(contractOpsDurationThrottle, "");
    }

    /**
     * Gets the active throttles for normal (non-high-volume) transactions.
     *
     * @return the list of active throttles
     */
    @VisibleForTesting
    public List<DeterministicThrottle> activeThrottles() {
        return activeThrottles;
    }

    /**
     * Gets the active throttles for high-volume transactions.
     *
     * @return the list of high-volume active throttles
     */
    @VisibleForTesting
    public List<DeterministicThrottle> highVolumeActiveThrottles() {
        return highVolumeActiveThrottles;
    }

    /**
     * Returns whether a high-volume throttle bucket exists for the given functionality.
     *
     * @param function the functionality to check
     * @return true if a high-volume throttle bucket exists for the functionality
     */
    @VisibleForTesting
    public boolean hasHighVolumeThrottleFor(@NonNull final HederaFunctionality function) {
        return highVolumeFunctionReqs.containsKey(function);
    }

    public enum ThrottleType {
        FRONTEND_THROTTLE,
        BACKEND_THROTTLE,
        NOOP_THROTTLE,
    }
}
