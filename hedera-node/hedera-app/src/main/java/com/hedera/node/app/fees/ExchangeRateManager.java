// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.fees;

import static java.math.BigInteger.valueOf;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.FileID;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.transaction.ExchangeRate;
import com.hedera.hapi.node.transaction.ExchangeRateSet;
import com.hedera.node.app.fees.schemas.V0490FeeSchema;
import com.hedera.node.app.spi.fees.ExchangeRateInfo;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.util.FileUtilities;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.AccountsConfig;
import com.hedera.node.config.data.FilesConfig;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.node.config.data.RatesConfig;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.State;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.math.BigInteger;
import java.time.Instant;
import java.util.stream.LongStream;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Parses the exchange rate information and makes it available to the workflows.
 *
 * <p>All fees in Hedera are based on the exchange rate between HBAR and USD. Fees are paid in HBAR, but based on the
 * current USD price of the HBAR. The "ERT", exchange rate tool, is responsible for tracking the exchange rate of
 * various exchanges, and updating the {@link ExchangeRateSet} on a periodic basis. Currently, this is in a special
 * file, but could be from any other source. The encoded {@link Bytes} are passed to the {@link #update(Bytes, AccountID)} method.
 * This <strong>MUST</strong> be done on the same thread that this manager is used by -- the manager is not threadsafe.
 *
 * <p>The {@link ExchangeRateSet} has two rates -- a "current" rate and the "next" rate. Each rate has an expiration
 * time, in <strong>consensus</strong> seconds since the epoch. During "handle", we know the consensus time. We will
 * ask the manager for the "active" rate, which is the "current" rate if the consensus time is before the expiration
 * time of the current rate, or the "next" rate if the consensus time is after the expiration time of the current rate.
 *
 * <p>If the consensus time is after the expiration time of the "next" rate, then we simply continue to use the "next"
 * rate, since we have nothing more recent to rely on.
 */
@Singleton
public final class ExchangeRateManager {
    private static final Logger log = LogManager.getLogger(ExchangeRateManager.class);

    private static final BigInteger ONE_HUNDRED = BigInteger.valueOf(100);

    private final ConfigProvider configProvider;

    private ExchangeRateInfo currentExchangeRateInfo;
    private ExchangeRateSet midnightRates;

    @Inject
    public ExchangeRateManager(@NonNull final ConfigProvider configProvider) {
        this.configProvider = requireNonNull(configProvider, "configProvider must not be null");
    }

    public void init(@NonNull final State state, @NonNull final Bytes bytes) {
        requireNonNull(state, "state must not be null");
        requireNonNull(bytes, "bytes must not be null");

        // Re-use the same code path to set the active rates as does the SystemFileUpdateFacility
        // IMPORTANT - if we first initialized the midnight rates, we couldn't reuse this code path
        // because it overwrites the midnight rates with the current rates
        systemUpdate(bytes);

        // Now fix the midnight rates to what is in state (note that all post-initialization
        // Services states must have a non-null midnight rates set, even at genesis, as
        // FeeService schema migrate() creates them in that case)
        midnightRates = state.getReadableStates(FeeService.NAME)
                .<ExchangeRateSet>getSingleton(V0490FeeSchema.MIDNIGHT_RATES_STATE_ID)
                .get();
        requireNonNull(midnightRates, "an initialized state must have a midnight rates set");
        log.info(
                "Initializing exchange rates with midnight rates {} and active rates {}",
                midnightRates,
                currentExchangeRateInfo.exchangeRates());
    }

    /**
     * Updates the exchange rate information. MUST BE CALLED on the handle thread!
     *
     * @param bytes The protobuf encoded {@link ExchangeRateSet}.
     * @param payerId The payer of the transaction that triggered this update.
     */
    public void update(@NonNull final Bytes bytes, @NonNull AccountID payerId) {
        requireNonNull(payerId, "payerId must not be null");
        internalUpdate(bytes, payerId);
    }

    public void systemUpdate(@NonNull final Bytes bytes) {
        internalUpdate(bytes, null);
    }

    private void internalUpdate(@NonNull final Bytes bytes, @Nullable AccountID payerId) {
        requireNonNull(bytes, "bytes must not be null");

        // Parse the exchange rate file. If we cannot parse it, we just continue with whatever our previous rate was.
        final ExchangeRateSet proposedRates;
        try {
            proposedRates = ExchangeRateSet.PROTOBUF.parse(bytes.toReadableSequentialData());
        } catch (final ParseException e) {
            throw new HandleException(ResponseCodeEnum.INVALID_EXCHANGE_RATE_FILE);
        }

        // Validate mandatory fields
        if (!(proposedRates.hasCurrentRate()
                && proposedRates.currentRateOrThrow().hasExpirationTime()
                && proposedRates.hasNextRate())) {
            throw new HandleException(ResponseCodeEnum.INVALID_EXCHANGE_RATE_FILE);
        }

        // Check bounds
        final var ratesConfig = configProvider.getConfiguration().getConfigData(RatesConfig.class);
        final var accountsConfig = configProvider.getConfiguration().getConfigData(AccountsConfig.class);
        final var isSuperUser = isSuperUser(payerId, accountsConfig);

        if (!isSuperUser) {
            final var limitPercent = ratesConfig.intradayChangeLimitPercent();
            if (!isNormalIntradayChange(midnightRates, proposedRates, limitPercent)) {
                throw new HandleException(ResponseCodeEnum.EXCHANGE_RATE_CHANGE_LIMIT_EXCEEDED);
            }
        }

        // Update the current ExchangeRateInfo and eventually the midnightRates
        this.currentExchangeRateInfo = new ExchangeRateInfoImpl(proposedRates);
        if (isAdminUser(payerId, accountsConfig)) {
            midnightRates = proposedRates;
        }
    }

    private boolean isSuperUser(@NonNull final AccountID accountID, AccountsConfig accountsConfig) {
        if (accountID == null) return true;
        if (!accountID.hasAccountNum()) return false;
        long num = accountID.accountNumOrThrow();
        return num == accountsConfig.treasury() || num == accountsConfig.systemAdmin();
    }

    private boolean isAdminUser(@NonNull final AccountID accountID, AccountsConfig accountsConfig) {
        if (accountID == null) return true;
        if (!accountID.hasAccountNum()) return false;
        long num = accountID.accountNumOrThrow();
        return num == accountsConfig.systemAdmin();
    }

    /**
     * Updates the midnight rates to the current exchange rates, both internally and in the given state.
     *
     * @param state the {@link State} to update the midnight rates in
     */
    public void updateMidnightRates(@NonNull final State state) {
        midnightRates = currentExchangeRateInfo.exchangeRates();
        final var singleton = state.getWritableStates(FeeService.NAME)
                .<ExchangeRateSet>getSingleton(V0490FeeSchema.MIDNIGHT_RATES_STATE_ID);
        singleton.put(midnightRates);
        log.info("Updated midnight rates to {}", midnightRates);
    }

    /**
     * Gets the current {@link ExchangeRateSet}. MUST BE CALLED ON THE HANDLE THREAD!!
     *
     * @return The current {@link ExchangeRateSet}.
     */
    @NonNull
    public ExchangeRateSet exchangeRates() {
        return currentExchangeRateInfo.exchangeRates();
    }

    /**
     * Gets the {@link ExchangeRate} that should be used as of the given consensus time. MUST BE CALLED ON THE HANDLE
     * THREAD!!
     *
     * @param consensusTime The consensus time. If after the expiration time of the current rate, the next rate will
     * be returned. Otherwise, the current rate will be returned.
     * @return The {@link ExchangeRate} that should be used as of the given consensus time.
     */
    @NonNull
    public ExchangeRate activeRate(@NonNull final Instant consensusTime) {
        return currentExchangeRateInfo.activeRate(consensusTime);
    }

    /**
     * Get the {@link ExchangeRateInfo} that is based on the given state.
     *
     * @param state The {@link State} to use.
     * @return The {@link ExchangeRateInfo}.
     */
    @NonNull
    public ExchangeRateInfo exchangeRateInfo(@NonNull final State state) {
        final var hederaConfig = configProvider.getConfiguration().getConfigData(HederaConfig.class);
        final var shardNum = hederaConfig.shard();
        final var realmNum = hederaConfig.realm();
        final var fileNum = configProvider
                .getConfiguration()
                .getConfigData(FilesConfig.class)
                .exchangeRates();
        final var fileID = FileID.newBuilder()
                .shardNum(shardNum)
                .realmNum(realmNum)
                .fileNum(fileNum)
                .build();
        final var bytes = FileUtilities.getFileContent(state, fileID);
        final ExchangeRateSet exchangeRates;
        try {
            exchangeRates = ExchangeRateSet.PROTOBUF.parse(bytes.toReadableSequentialData());
        } catch (ParseException e) {
            // This should never happen
            throw new IllegalStateException(e);
        }
        return new ExchangeRateInfoImpl(exchangeRates);
    }

    private static boolean isNormalIntradayChange(
            @NonNull final ExchangeRateSet midnightRates,
            @NonNull final ExchangeRateSet proposedRates,
            final int limitPercent) {
        return canonicalTest(
                        limitPercent,
                        midnightRates.currentRate().centEquiv(),
                        midnightRates.currentRate().hbarEquiv(),
                        proposedRates.currentRate().centEquiv(),
                        proposedRates.currentRate().hbarEquiv())
                && canonicalTest(
                        limitPercent,
                        midnightRates.nextRate().centEquiv(),
                        midnightRates.nextRate().hbarEquiv(),
                        proposedRates.nextRate().centEquiv(),
                        proposedRates.nextRate().hbarEquiv());
    }

    private static boolean canonicalTest(
            final long bound, final long oldC, final long oldH, final long newC, final long newH) {
        final var b100 = valueOf(bound).add(ONE_HUNDRED);

        final var oC = valueOf(oldC);
        final var oH = valueOf(oldH);
        final var nC = valueOf(newC);
        final var nH = valueOf(newH);

        return LongStream.of(bound, oldC, oldH, newC, newH).allMatch(i -> i > 0)
                && oC.multiply(nH)
                                .multiply(b100)
                                .subtract(nC.multiply(oH).multiply(ONE_HUNDRED))
                                .signum()
                        >= 0
                && oH.multiply(nC)
                                .multiply(b100)
                                .subtract(nH.multiply(oC).multiply(ONE_HUNDRED))
                                .signum()
                        >= 0;
    }

    /**
     * Converts tinybars to tiny cents using the exchange rate at a given time.
     *
     * @param amount The amount in tiny cents.
     * @param consensusTime The consensus time to use for the exchange rate.
     * @return The amount in tinybars.
     */
    public long getTinybarsFromTinycents(final long amount, @NonNull final Instant consensusTime) {
        final var rate = activeRate(consensusTime);
        return getAFromB(amount, rate.hbarEquiv(), rate.centEquiv());
    }

    private static long getAFromB(final long bAmount, final int aEquiv, final int bEquiv) {
        final var aMultiplier = BigInteger.valueOf(aEquiv);
        final var bDivisor = BigInteger.valueOf(bEquiv);
        return BigInteger.valueOf(bAmount)
                .multiply(aMultiplier)
                .divide(bDivisor)
                .longValueExact();
    }
}
