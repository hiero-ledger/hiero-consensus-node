// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.fees;

import static com.hedera.hapi.node.base.HederaFunctionality.CONSENSUS_CREATE_TOPIC;
import static com.hedera.hapi.node.base.HederaFunctionality.CONSENSUS_DELETE_TOPIC;
import static com.hedera.hapi.node.base.HederaFunctionality.CONSENSUS_SUBMIT_MESSAGE;
import static com.hedera.hapi.node.base.HederaFunctionality.CONSENSUS_UPDATE_TOPIC;
import static com.hedera.hapi.node.base.HederaFunctionality.FREEZE;
import static com.hedera.hapi.node.base.HederaFunctionality.GET_ACCOUNT_DETAILS;
import static com.hedera.hapi.node.base.HederaFunctionality.NETWORK_GET_EXECUTION_TIME;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_GET_ACCOUNT_NFT_INFOS;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_GET_NFT_INFOS;
import static com.hedera.hapi.node.base.HederaFunctionality.TRANSACTION_GET_FAST_RECORD;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static java.util.Objects.requireNonNull;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeExtraDef;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeExtraIncluded;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeService;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeServiceFee;

import com.hedera.hapi.node.base.CurrentAndNextFeeSchedule;
import com.hedera.hapi.node.base.FeeComponents;
import com.hedera.hapi.node.base.FeeData;
import com.hedera.hapi.node.base.FeeSchedule;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.SubType;
import com.hedera.hapi.node.base.TransactionFeeSchedule;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.fees.congestion.CongestionMultipliers;
import com.hedera.node.app.spi.fees.FeeCalculator;
import com.hedera.node.app.store.ReadableStoreFactory;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.nio.BufferUnderflowException;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.hapi.support.fees.Extra;
import org.hiero.hapi.support.fees.NetworkFee;
import org.hiero.hapi.support.fees.NodeFee;

/**
 * Creates {@link FeeCalculator} instances based on the current fee schedule. Whenever the fee schedule is updated,
 * the {@link #update(Bytes)} method should be called. Until updated, the fee schedule will be empty, which will
 * manifest as errors in attempting to execute a given transaction (a transaction without an entry in the fee schedule
 * cannot be executed).
 */
@Singleton
public final class FeeManager {
    private static final Logger logger = LogManager.getLogger(FeeManager.class);
    private org.hiero.hapi.support.fees.FeeSchedule simpleFeesSchedule;

    private record Entry(HederaFunctionality function, SubType subType) {}

    private static final long DEFAULT_FEE = 100_000L;
    /**
     * A set of operations that we do not expect to find the fee schedule. These include
     * privileged operations (which are either rejected at ingest or not charged fees at
     * consensus); and unsupported queries that are never answered.
     */
    private static final Set<HederaFunctionality> INAPPLICABLE_OPERATIONS = EnumSet.of(
            FREEZE,
            GET_ACCOUNT_DETAILS,
            NETWORK_GET_EXECUTION_TIME,
            TRANSACTION_GET_FAST_RECORD,
            TOKEN_GET_NFT_INFOS,
            TOKEN_GET_ACCOUNT_NFT_INFOS);

    private static final FeeComponents DEFAULT_FEE_COMPONENTS =
            FeeComponents.newBuilder().min(DEFAULT_FEE).max(DEFAULT_FEE).build();
    private static final FeeData DEFAULT_FEE_DATA = FeeData.newBuilder()
            .networkdata(DEFAULT_FEE_COMPONENTS)
            .nodedata(DEFAULT_FEE_COMPONENTS)
            .servicedata(DEFAULT_FEE_COMPONENTS)
            .build();

    /** The current fee schedule, cached for speed. */
    private Map<Entry, FeeData> currentFeeDataMap = Collections.emptyMap();
    /** The next fee schedule, cached for speed. */
    private Map<Entry, FeeData> nextFeeDataMap = Collections.emptyMap();
    /** The expiration time of the "current" fee schedule, in consensus seconds since the epoch, cached for speed. */
    private long currentScheduleExpirationSeconds;
    /** The exchange rate manager to use for the current rate */
    private final ExchangeRateManager exchangeRateManager;

    private final CongestionMultipliers congestionMultipliers;

    @Inject
    public FeeManager(
            @NonNull final ExchangeRateManager exchangeRateManager,
            @NonNull CongestionMultipliers congestionMultipliers) {
        this.exchangeRateManager = requireNonNull(exchangeRateManager);
        this.congestionMultipliers = requireNonNull(congestionMultipliers);
    }

    /**
     * Updates the fee schedule based on the given file content.
     *
     * <p>IMPORTANT:</p> This can only be called when initializing a state or handling a transaction.
     *
     * @param bytes The new fee schedule file content.
     */
    public ResponseCodeEnum update(@NonNull final Bytes bytes) {
        // Parse the current and next fee schedules
        final CurrentAndNextFeeSchedule schedules;
        try {
            schedules = CurrentAndNextFeeSchedule.PROTOBUF.parse(bytes.toReadableSequentialData());
        } catch (final BufferUnderflowException | ParseException ex) {
            return ResponseCodeEnum.FEE_SCHEDULE_FILE_PART_UPLOADED;
        }

        // Get the current schedule
        var currentSchedule = schedules.currentFeeSchedule();
        if (currentSchedule == null) {
            // If there is no current schedule, then we default to the default schedule. Since the default
            // schedule is completely empty, this will effectively disable the handling of any transactions,
            // since we don't know what to charge for them.
            logger.warn("Unable to parse current fee schedule, will default to an empty schedule, effectively"
                    + "disabling all transactions.");
            currentSchedule = FeeSchedule.DEFAULT;
        }

        // Populate the map of HederaFunctionality -> FeeData for the current schedule, but avoid mutating
        // the active one in-place as other threads may be using it for ingest/query fee calculations
        final var newCurrentFeeDataMap = new HashMap<Entry, FeeData>();
        populateFeeDataMap(newCurrentFeeDataMap, currentSchedule.transactionFeeSchedule());
        this.currentFeeDataMap = newCurrentFeeDataMap;

        // Get the expiration time of the current schedule
        if (currentSchedule.hasExpiryTime()) {
            this.currentScheduleExpirationSeconds =
                    currentSchedule.expiryTimeOrThrow().seconds();
        } else {
            // If we don't have an expiration time, then we default to 0, which will effectively expire the
            // current schedule immediately. This is the safest option.
            logger.warn("The current fee schedule has no expiry time, defaulting to 0, effectively expiring it "
                    + "immediately");
            this.currentScheduleExpirationSeconds = 0;
        }

        // Get the next schedule
        var nextSchedule = schedules.nextFeeSchedule();
        if (nextSchedule == null) {
            // If there is no next schedule, then we default to the current schedule. If we didn't have a current
            // schedule either, then basically we have an empty schedule with an expiration time of 0, which will
            // still get used since we continue to use the next schedule even if the expiration time has passed.
            logger.warn("Unable to parse next fee schedule, will default to the current fee schedule.");
            nextFeeDataMap = new HashMap<>(currentFeeDataMap);
        } else {
            // Populate the map of HederaFunctionality -> FeeData for the next schedule, but avoid mutating
            // the active one in-place as other threads may be using it for ingest/query fee calculations
            final var newNextFeeDataMap = new HashMap<Entry, FeeData>();
            populateFeeDataMap(newNextFeeDataMap, nextSchedule.transactionFeeSchedule());
            this.nextFeeDataMap = newNextFeeDataMap;
        }

        return SUCCESS;
    }

    /**
     * Updates the fee schedule based on the given file content.
     *
     * <p>IMPORTANT:</p> This can only be called when initializing a state or handling a transaction.
     *
     * @param bytes The new fee schedule file content.
     */
    public ResponseCodeEnum updateSimpleFees(@NonNull final Bytes bytes) {
        // Parse the current and next fee schedules
        try {
            final org.hiero.hapi.support.fees.FeeSchedule schedule =
                    org.hiero.hapi.support.fees.FeeSchedule.PROTOBUF.parse(bytes);
            this.simpleFeesSchedule = schedule;
            return SUCCESS;
        } catch (final BufferUnderflowException | ParseException ex) {
            this.simpleFeesSchedule = org.hiero.hapi.support.fees.FeeSchedule.DEFAULT
                    .copyBuilder()
                    .extras(
                            makeExtraDef(Extra.BYTES, 1),
                            makeExtraDef(Extra.KEYS, 2),
                            makeExtraDef(Extra.SIGNATURES, 3),
                            makeExtraDef(Extra.CUSTOM_FEE, 500))
                    .node(NodeFee.DEFAULT
                            .copyBuilder()
                            .baseFee(1)
                            .extras(makeExtraIncluded(Extra.BYTES, 1024), makeExtraIncluded(Extra.SIGNATURES, 1))
                            .build())
                    .network(NetworkFee.DEFAULT.copyBuilder().multiplier(2).build())
                    .services(makeService(
                            "Consensus",
                            makeServiceFee(CONSENSUS_CREATE_TOPIC, 11, makeExtraIncluded(Extra.KEYS, 1)),
                            makeServiceFee(CONSENSUS_UPDATE_TOPIC, 12, makeExtraIncluded(Extra.KEYS, 1)),
                            makeServiceFee(CONSENSUS_DELETE_TOPIC, 13, makeExtraIncluded(Extra.KEYS, 1)),
                            makeServiceFee(
                                    CONSENSUS_SUBMIT_MESSAGE,
                                    14,
                                    makeExtraIncluded(Extra.SIGNATURES, 1),
                                    makeExtraIncluded(Extra.KEYS, 1),
                                    makeExtraIncluded(Extra.BYTES, 1024),
                                    makeExtraIncluded(Extra.CUSTOM_FEE, 0))))
                    .build();

            return ResponseCodeEnum.FEE_SCHEDULE_FILE_PART_UPLOADED;
        }
    }

    /**
     * Create a {@link FeeCalculator} for the given transaction and its details.
     */
    @NonNull
    public FeeCalculator createFeeCalculator(
            @Nullable final TransactionBody txBody,
            @Nullable final Key payerKey,
            @Nullable final HederaFunctionality functionality,
            final int numVerifications,
            final int signatureMapSize,
            @NonNull final Instant consensusTime,
            @NonNull final SubType subType,
            final boolean isInternalDispatch,
            final ReadableStoreFactory storeFactory) {

        if (txBody == null || payerKey == null || functionality == null) {
            return NoOpFeeCalculator.INSTANCE;
        }

        // Determine which fee schedule to use, based on the consensus time
        // If it is not known, that is, if we have no fee data for that transaction, then we MUST NOT execute that
        // transaction! We will not be able to charge appropriately for it.
        final var feeData = getFeeData(functionality, consensusTime, subType);

        // Create the fee calculator
        return new FeeCalculatorImpl(
                txBody,
                payerKey,
                numVerifications,
                signatureMapSize,
                feeData,
                exchangeRateManager.activeRate(consensusTime),
                isInternalDispatch,
                congestionMultipliers,
                storeFactory,
                this.simpleFeesSchedule);
    }

    public long congestionMultiplierFor(
            @NonNull final TransactionBody body,
            @NonNull final HederaFunctionality functionality,
            @NonNull final ReadableStoreFactory storeFactory) {

        return congestionMultipliers.maxCurrentMultiplier(body, functionality, storeFactory);
    }

    @NonNull
    public FeeCalculator createFeeCalculator(
            @NonNull final HederaFunctionality functionality,
            @NonNull final Instant consensusTime,
            @NonNull final ReadableStoreFactory storeFactory) {
        // Determine which fee schedule to use, based on the consensus time
        final var feeData = getFeeData(functionality, consensusTime, SubType.DEFAULT);

        // Create the fee calculator
        return new FeeCalculatorImpl(
                feeData,
                exchangeRateManager.activeRate(consensusTime),
                congestionMultipliers,
                storeFactory,
                functionality,
                this.simpleFeesSchedule);
    }

    /**
     * Looks up the fee data for the given transaction and its details.
     */
    @NonNull
    public FeeData getFeeData(
            @NonNull HederaFunctionality functionality, @NonNull Instant consensusTime, @NonNull SubType subType) {
        final var feeDataMap =
                consensusTime.getEpochSecond() > currentScheduleExpirationSeconds ? nextFeeDataMap : currentFeeDataMap;

        // Now, lookup the fee data for the transaction type.
        final var result = feeDataMap.get(new Entry(functionality, subType));
        if (result == null) {
            if (!INAPPLICABLE_OPERATIONS.contains(functionality)) {
                logger.warn("Using default usage prices to calculate fees for {}!", functionality);
            }
            return DEFAULT_FEE_DATA;
        }
        return result;
    }

    /** Gets the current exchange rate manager.
     */
    @NonNull
    public ExchangeRateManager getExchangeRateManager() {
        return exchangeRateManager;
    }

    /**
     * Used during {@link #update(Bytes)} to populate the fee data map based on the configuration.
     * @param feeDataMap The map to populate.
     * @param feeSchedule The fee schedule to use.
     */
    private void populateFeeDataMap(
            @NonNull final Map<Entry, FeeData> feeDataMap, @NonNull final List<TransactionFeeSchedule> feeSchedule) {
        feeSchedule.forEach(t -> {
            if (!t.fees().isEmpty()) {
                for (final var feeData : t.fees()) {
                    feeDataMap.put(new Entry(t.hederaFunctionality(), feeData.subType()), feeData);
                }
            } else if (t.hasFeeData()) {
                feeDataMap.put(new Entry(t.hederaFunctionality(), SubType.DEFAULT), t.feeDataOrThrow());
            } else {
                logger.warn(
                        "Neither `fees` nor `feeData` specified for transaction type {}, ignoring it.",
                        t.hederaFunctionality());
            }
        });
    }
}
