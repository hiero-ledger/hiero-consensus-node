// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.gas;

import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.configOf;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.tinybarValuesFor;
import static com.swirlds.base.units.UnitConstants.HOURS_TO_MINUTES;
import static com.swirlds.base.units.UnitConstants.MINUTES_TO_SECONDS;
import static java.util.Objects.requireNonNull;

import com.hedera.node.config.data.CacheConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.CancunGasCalculator;

/**
 * FUTURE(#12991): GasCalculators for specific EVM versions should be injected based on
 * `evm.version` configuration setting, just like EVM modules themselves.  Right now (0.49-0.50
 * timeframe) it is ok to just fix our CustomGasCalculator at Besu's Cancun level because the only
 * changes it has over Besu's Shanghai level is that it has defaults for blob gas prices, and those
 * methods are never called at the Shanghai level anyway, nor at the Cancun level because we don't
 * support blobs.
 * too many parents
 */
@SuppressWarnings("java:S110")
@Singleton
public class CustomGasCalculator extends CancunGasCalculator {
    private static final long TX_DATA_ZERO_COST = 4L;
    private static final long ISTANBUL_TX_DATA_NON_ZERO_COST = 16L;
    private static final long TX_BASE_COST = 21_000L;
    private static final int LOG_CONTRACT_ID_SIZE = 24;
    private static final int LOG_TOPIC_SIZE = 32;
    private static final int LOG_BLOOM_SIZE = 256;

    /**
     * Default constructor for injection.
     */
    @Inject
    public CustomGasCalculator() {
        // Dagger2
    }

    // We won't use the baseline cost for now
    // should revisit with the Pectra support epic
    @Override
    public long transactionIntrinsicGasCost(
            final Bytes payload, final boolean isContractCreate, final long baselineCost) {
        int zeros = 0;
        for (int i = 0; i < payload.size(); i++) {
            if (payload.get(i) == 0) {
                ++zeros;
            }
        }
        final int nonZeros = payload.size() - zeros;

        long cost = TX_BASE_COST + TX_DATA_ZERO_COST * zeros + ISTANBUL_TX_DATA_NON_ZERO_COST * nonZeros;

        return isContractCreate ? (cost + txCreateExtraGasCost()) : cost;
    }

    @Override
    public long logOperationGasCost(
            @NonNull final MessageFrame frame, final long dataOffset, final long dataLength, final int numTopics) {
        requireNonNull(frame);
        final var evmGasCost = super.logOperationGasCost(frame, dataOffset, dataLength, numTopics);

        final var lifetime = configOf(frame).getConfigData(CacheConfig.class).recordsTtl();
        final var tinybarValues = tinybarValuesFor(frame);
        final var hevmGasCost = gasCostOfStoring(
                logSize(numTopics, dataLength),
                lifetime,
                tinybarValues.topLevelTinycentRbhPrice(),
                tinybarValues.topLevelTinycentGasPrice());

        return Math.max(evmGasCost, hevmGasCost);
    }

    /**
     * Gas charge to do a signature verification for an ED key.
     *
     * Based on the cost of system resources used.
     *
     * FUTURE: Gas for system contract method calls needs to be a) determined by measurement of
     * resources consumed, and b) incorporated into the fee schedule.
     * @return the hardcoded gas cost for ED verification
     */
    public long getEdSignatureVerificationSystemContractGasCost() {
        return 1_500_000L;
    }

    /**
     * Logically, would return the gas cost of storing the given number of bytes for the given number of seconds,
     * given the relative prices of a byte-hour and a gas unit in tinycent.
     *
     * <p>But for differential testing, ignores the {@code numBytes} and returns the gas cost of storing just a
     * single byte for the given number of seconds.
     *
     * @param numBytes ignored
     * @param lifetime the number of seconds to store a single byte
     * @param rbhPrice the price of a byte-hour in tinycent
     * @param gasPrice the price of a gas unit in tinycent
     * @return the gas cost of storing a single byte for the given number of seconds
     */
    private static long gasCostOfStoring(
            final long numBytes, final long lifetime, final long rbhPrice, final long gasPrice) {
        final var storagePrice = (lifetime * rbhPrice) / (HOURS_TO_MINUTES * MINUTES_TO_SECONDS);
        return Math.round((double) storagePrice / (double) gasPrice);
    }

    /**
     * Returns an idealized computation of the number of bytes needed to store a log with the given data size
     * and number of topics.
     *
     * @param numberOfTopics the number of topics in the log
     * @param dataSize the size of the data in the log
     * @return an idealized computation of the number of bytes needed to store a log with the given data size
     */
    private static long logSize(final int numberOfTopics, final long dataSize) {
        return LOG_CONTRACT_ID_SIZE + LOG_BLOOM_SIZE + LOG_TOPIC_SIZE * (long) numberOfTopics + dataSize;
    }
}
