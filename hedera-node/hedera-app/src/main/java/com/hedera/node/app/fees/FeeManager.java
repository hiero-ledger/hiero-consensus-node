// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.fees;

import static com.hedera.hapi.node.base.HederaFunctionality.HOOK_DISPATCH;
import static com.hedera.hapi.node.base.ResponseCodeEnum.FEE_SCHEDULE_FILE_PART_UPLOADED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static java.util.Objects.requireNonNull;
import static org.hiero.hapi.fees.FeeScheduleUtils.isValid;
import static org.hiero.hapi.fees.FeeScheduleUtils.lookupExtraFee;
import static org.hiero.hapi.fees.FeeScheduleUtils.lookupServiceFee;

import com.google.common.annotations.VisibleForTesting;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.util.HapiUtils;
import com.hedera.hapi.util.UnknownHederaFunctionality;
import com.hedera.node.app.fees.congestion.CongestionMultipliers;
import com.hedera.node.app.spi.fees.QueryFeeCalculator;
import com.hedera.node.app.spi.fees.ServiceFeeCalculator;
import com.hedera.node.app.spi.fees.SimpleFeeCalculator;
import com.hedera.node.app.spi.store.ReadableStoreFactory;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.BufferUnderflowException;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.hapi.support.fees.Extra;
import org.hiero.hapi.support.fees.FeeSchedule;

/**
 * Manages the fee schedule used to calculate fees. Whenever the fee schedule is updated,
 * the {@link #updateSimpleFees(Bytes)} method should be called.
 */
@Singleton
public final class FeeManager {
    private static final Logger logger = LogManager.getLogger(FeeManager.class);

    /** Functions only dispatched by a handler as a step of another transaction, which pays for them. */
    private static final Set<HederaFunctionality> HANDLER_STEP_FUNCTIONS = EnumSet.of(HOOK_DISPATCH);

    private FeeSchedule simpleFeesSchedule;
    private volatile SimpleFeeCalculator simpleFeeCalculator;

    private final Set<ServiceFeeCalculator> serviceFeeCalculators;
    private final Set<QueryFeeCalculator> queryFeeCalculators;

    /**
     * The exchange rate manager to use for the current rate
     */
    private final ExchangeRateManager exchangeRateManager;

    private final CongestionMultipliers congestionMultipliers;

    @Inject
    public FeeManager(
            @NonNull final ExchangeRateManager exchangeRateManager,
            @NonNull CongestionMultipliers congestionMultipliers,
            @NonNull Set<ServiceFeeCalculator> serviceFeeCalculators,
            @NonNull Set<QueryFeeCalculator> queryFeeCalculators) {
        this.exchangeRateManager = requireNonNull(exchangeRateManager);
        this.congestionMultipliers = requireNonNull(congestionMultipliers);
        this.serviceFeeCalculators = requireNonNull(serviceFeeCalculators);
        this.queryFeeCalculators = requireNonNull(queryFeeCalculators);
    }

    /**
     * Updates the simple fee schedule based on the given file content. This is called on genesis and whenever
     * the simple fee schedule file is updated. The schedule must be well-formed and must price every transaction
     * and query type that has a registered fee calculator; otherwise it is rejected and the current schedule is kept.
     *
     * @param bytes The new simple fee schedule file content.
     * @return {@link ResponseCodeEnum#SUCCESS} if the schedule was installed, otherwise
     * {@link ResponseCodeEnum#FEE_SCHEDULE_FILE_PART_UPLOADED}
     */
    public ResponseCodeEnum updateSimpleFees(@NonNull final Bytes bytes) {
        return updateSimpleFees(bytes, true);
    }

    /**
     * Updates the simple fee schedule based on the given file content. A well-formed schedule that leaves some
     * registered transaction or query type unpriced is rejected when {@code requireFullCoverage} is true; otherwise
     * it is installed and the unpriced types are logged.
     *
     * @param bytes The new simple fee schedule file content.
     * @param requireFullCoverage whether to reject a schedule that leaves a registered type unpriced
     * @return {@link ResponseCodeEnum#SUCCESS} if the schedule was installed, otherwise
     * {@link ResponseCodeEnum#FEE_SCHEDULE_FILE_PART_UPLOADED}
     */
    public synchronized ResponseCodeEnum updateSimpleFees(
            @NonNull final Bytes bytes, final boolean requireFullCoverage) {
        try {
            final FeeSchedule schedule = FeeSchedule.PROTOBUF.parseStrict(bytes);
            if (!isValid(schedule)) {
                logger.error("Unable to validate simple fee schedule.");
                return FEE_SCHEDULE_FILE_PART_UPLOADED;
            }
            final var unpriced = unpricedFunctions(schedule);
            if (!unpriced.isEmpty()) {
                if (requireFullCoverage) {
                    logger.error("Rejecting simple fee schedule with no prices for {}", unpriced);
                    return FEE_SCHEDULE_FILE_PART_UPLOADED;
                }
                logger.error("Installing simple fee schedule with no prices for {}", unpriced);
            }
            logger.info("Successfully validated simple fee schedule.");
            this.simpleFeesSchedule = schedule;
            this.simpleFeeCalculator = new SimpleFeeCalculatorImpl(
                    schedule, serviceFeeCalculators, queryFeeCalculators, congestionMultipliers);
            return SUCCESS;
        } catch (final BufferUnderflowException | ParseException ex) {
            return FEE_SCHEDULE_FILE_PART_UPLOADED;
        }
    }

    /**
     * Returns the functions with a registered fee calculator that the given schedule does not price, other than
     * handler step functions, which are never priced on their own.
     *
     * @param schedule the schedule to check
     * @return the unpriced functions
     */
    @VisibleForTesting
    Set<HederaFunctionality> unpricedFunctions(@NonNull final FeeSchedule schedule) {
        final var unpriced = EnumSet.noneOf(HederaFunctionality.class);
        for (final var calculator : serviceFeeCalculators) {
            final var function = functionOf(calculator.getTransactionType());
            if (!HANDLER_STEP_FUNCTIONS.contains(function) && lookupServiceFee(schedule, function) == null) {
                unpriced.add(function);
            }
        }
        for (final var calculator : queryFeeCalculators) {
            final var function = functionOf(calculator.getQueryType());
            if (lookupServiceFee(schedule, function) == null) {
                unpriced.add(function);
            }
        }
        return unpriced;
    }

    private static HederaFunctionality functionOf(@NonNull final TransactionBody.DataOneOfType kind) {
        try {
            return HapiUtils.functionOf(kind);
        } catch (final UnknownHederaFunctionality e) {
            throw new IllegalStateException("Fee calculator registered for unknown transaction type " + kind, e);
        }
    }

    private static HederaFunctionality functionOf(@NonNull final Query.QueryOneOfType kind) {
        try {
            return HapiUtils.functionOf(kind);
        } catch (final UnknownHederaFunctionality e) {
            throw new IllegalStateException("Fee calculator registered for unknown query type " + kind, e);
        }
    }

    public long congestionMultiplierFor(
            @NonNull final TransactionBody body,
            @NonNull final HederaFunctionality functionality,
            @NonNull final ReadableStoreFactory storeFactory) {
        return congestionMultipliers.maxCurrentMultiplier(body, functionality, storeFactory);
    }

    /**
     * Returns the gas price in tiny cents, sourced from the GAS extra of the simple fee schedule.
     * The GAS extra is guaranteed by {@link org.hiero.hapi.fees.FeeScheduleUtils#isValid} for every
     * loaded schedule, so this can only throw if no schedule has been loaded at all.
     *
     * @param consensusTime the consensus time
     * @return the gas price in tiny cents
     * @throws IllegalStateException if no simple fee schedule with a GAS extra is loaded
     */
    public long getGasPriceInTinyCents(@NonNull final Instant consensusTime) {
        requireNonNull(consensusTime);
        final var gasExtra = lookupExtraFee(getSimpleFeesSchedule(), Extra.GAS);
        if (gasExtra == null) {
            throw new IllegalStateException("The simple fee schedule is missing the required GAS extra");
        }
        return gasExtra.fee();
    }

    /**
     * Gets the current exchange rate manager.
     */
    @NonNull
    public ExchangeRateManager getExchangeRateManager() {
        return exchangeRateManager;
    }

    @NonNull
    public SimpleFeeCalculator getSimpleFeeCalculator() {
        return simpleFeeCalculator;
    }

    @NonNull
    public FeeSchedule getSimpleFeesSchedule() {
        return simpleFeesSchedule != null ? simpleFeesSchedule : FeeSchedule.DEFAULT;
    }
}
