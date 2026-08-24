// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.fees;

import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static java.util.Objects.requireNonNull;
import static org.hiero.hapi.fees.FeeScheduleUtils.isValid;
import static org.hiero.hapi.fees.FeeScheduleUtils.lookupExtraFee;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.transaction.TransactionBody;
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
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.hapi.support.fees.Extra;

/**
 * Manages the fee schedule used to calculate fees. Whenever the fee schedule is updated,
 * the {@link #updateSimpleFees(Bytes)} method should be called.
 */
@Singleton
public final class FeeManager {
    private static final Logger logger = LogManager.getLogger(FeeManager.class);

    private org.hiero.hapi.support.fees.FeeSchedule simpleFeesSchedule;
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
     * the simple fee schedule file is updated.
     *
     * @param bytes The new simple fee schedule file content.
     */
    public synchronized ResponseCodeEnum updateSimpleFees(@NonNull final Bytes bytes) {
        // Parse the current and next fee schedules
        try {
            final org.hiero.hapi.support.fees.FeeSchedule schedule =
                    org.hiero.hapi.support.fees.FeeSchedule.PROTOBUF.parseStrict(bytes);
            if (isValid(schedule)) {
                logger.info("Successfully validated simple fee schedule.");
                this.simpleFeesSchedule = schedule;
                this.simpleFeeCalculator = new SimpleFeeCalculatorImpl(
                        schedule, serviceFeeCalculators, queryFeeCalculators, congestionMultipliers);
                return SUCCESS;
            } else {
                logger.error("Unable to validate simple fee schedule.");
                return ResponseCodeEnum.FEE_SCHEDULE_FILE_PART_UPLOADED;
            }
        } catch (final BufferUnderflowException | ParseException ex) {
            return ResponseCodeEnum.FEE_SCHEDULE_FILE_PART_UPLOADED;
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
    public org.hiero.hapi.support.fees.FeeSchedule getSimpleFeesSchedule() {
        return simpleFeesSchedule != null ? simpleFeesSchedule : org.hiero.hapi.support.fees.FeeSchedule.DEFAULT;
    }
}
