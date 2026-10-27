// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl;

import static com.hedera.hapi.node.base.HederaFunctionality.CONTRACT_CALL;
import static com.hedera.hapi.node.base.SubType.DEFAULT;
import static com.hedera.node.app.hapi.utils.CommonPbjConverters.fromPbj;
import static com.hedera.node.app.hapi.utils.CommonUtils.clampedAdd;
import static com.hedera.node.app.hapi.utils.CommonUtils.clampedMultiply;
import static com.hedera.node.app.hapi.utils.fee.FeeConstants.tinycentsToTinybars;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.transaction.ExchangeRate;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.api.TokenServiceApi;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.config.data.ClprConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Bills the Connectors of one {@code ClprSubmitBundle} and pays its submitting endpoint, following the bundle
 * compensation model of clpr-spec PR #65 (§3.2).
 *
 * <p>Connectors pay in two phases (§3.2.4):
 * <ol>
 *     <li><b>Per message.</b> Each application callback's gas ({@code G_i}) is charged at the network gas price. The
 *     worst case is escrowed from the Connector to the submitter before the callback runs, so a reentrant callback
 *     cannot spend it; afterwards the unused part goes back to the Connector and the used part to the network fee
 *     accounts, since on Hedera the submitter does not pay for callback gas.</li>
 *     <li><b>Settlement.</b> Once every message is handled, each Connector {@code C_i} with {@code M_i} of the bundle's
 *     {@code M} billable messages pays the submitter {@code (M_i / M) * (Tm + X * Y)}. {@code X} counts the bundle
 *     items that made progress and {@code Y} is {@link ClprConfig#progressRewardTinycents()}. On Hedera the shared
 *     verification and maintenance cost {@code Tm} is the {@code ClprSubmitBundle} fee the submitter was charged,
 *     which prices the bundle's bytes.</li>
 * </ol>
 * A bundle with no billable messages is paid for entirely by its submitter, and a Connector that cannot cover its
 * share pays what it can.
 */
public final class ClprBundleBilling {

    private static final Logger log = LogManager.getLogger(ClprBundleBilling.class);

    private final HandleContext context;
    private final TokenServiceApi tokenServiceApi;
    private final ReadableAccountStore accountStore;
    private final AccountID submitterId;
    private final ExchangeRate exchangeRate;
    private final long gasPriceTinycents;
    private final long bundleFeeTinybars;
    private final long progressRewardTinycents;
    // Insertion-ordered so settlement bills Connectors in the same order on every node
    private final Map<AccountID, Long> billableMessages = new LinkedHashMap<>();

    /**
     * Creates the billing for one bundle.
     *
     * @param context the bundle's handle context; its payer is the submitting endpoint
     * @param bundleFeeTinybars the {@code ClprSubmitBundle} fee charged to the submitter ({@code Tm})
     */
    public ClprBundleBilling(@NonNull final HandleContext context, final long bundleFeeTinybars) {
        this.context = requireNonNull(context);
        final var storeFactory = context.storeFactory();
        this.tokenServiceApi = storeFactory.serviceApi(TokenServiceApi.class);
        this.accountStore = storeFactory.readableStore(ReadableAccountStore.class);
        this.submitterId = context.payer();
        this.exchangeRate = context.exchangeRateInfo().activeRate(context.consensusNow());
        // Application callbacks are CONTRACT_CALL dispatches, so apply the same congestion multiplier as the EVM.
        final var callbackPrices = context.resourcePriceCalculator().resourcePricesFor(CONTRACT_CALL, DEFAULT);
        this.gasPriceTinycents =
                clampedMultiply(context.getGasPriceInTinycents(), callbackPrices.congestionMultiplier());
        this.bundleFeeTinybars = bundleFeeTinybars;
        this.progressRewardTinycents =
                context.configuration().getConfigData(ClprConfig.class).progressRewardTinycents();
    }

    /**
     * Returns the cost in tinybars of the given gas at the network gas price.
     *
     * @param gas the gas
     * @return its cost in tinybars
     */
    public long gasCost(final long gas) {
        return toTinybars(clampedMultiply(gas, gasPriceTinycents));
    }

    /**
     * Moves up to {@code maxAmount} from a Connector's account to the submitter to cover a callback's gas before the
     * callback runs.
     *
     * @param connectorAccount the Connector's account
     * @param maxAmount the callback's worst-case gas cost
     * @return the amount escrowed, less than {@code maxAmount} only if the Connector cannot cover it
     */
    public long escrowGas(@NonNull final Account connectorAccount, final long maxAmount) {
        final var connectorAccountId = connectorAccount.accountIdOrThrow();
        if (connectorAccountId.equals(submitterId)) {
            return 0L;
        }
        final long escrowed = Math.max(0L, Math.min(maxAmount, connectorAccount.tinybarBalance()));
        if (escrowed > 0) {
            tokenServiceApi.transferFromTo(connectorAccountId, submitterId, escrowed);
        }
        return escrowed;
    }

    /**
     * Charges a callback's gas against its escrow: returns the unused part to the Connector and pays the network fee
     * accounts for the used part. Gas beyond the escrow goes unpaid.
     *
     * @param connectorAccountId the Connector's account
     * @param escrowed the amount escrowed by {@link #escrowGas}
     * @param gasUsed the gas the callback used
     * @return the amount charged
     */
    public long chargeGas(@NonNull final AccountID connectorAccountId, final long escrowed, final long gasUsed) {
        requireNonNull(connectorAccountId);
        final long charged = Math.min(escrowed, gasCost(gasUsed));
        if (escrowed > charged) {
            tokenServiceApi.transferFromTo(submitterId, connectorAccountId, escrowed - charged);
        }
        if (charged > 0) {
            context.tryToCharge(submitterId, charged);
        }
        return charged;
    }

    /**
     * Records a bundle message whose callback a Connector is billed for, counting toward its {@code M_i}.
     *
     * @param connectorAccountId the Connector's account
     */
    public void recordBillableMessage(@NonNull final AccountID connectorAccountId) {
        billableMessages.merge(requireNonNull(connectorAccountId), 1L, Long::sum);
    }

    /**
     * Bills each Connector its share of the bundle's shared cost, {@code (M_i / M) * (Tm + X * Y)}, and pays it to the
     * submitter.
     *
     * @param progressItems the number of bundle items that made progress ({@code X})
     * @return the total paid to the submitter
     */
    public long settle(final long progressItems) {
        if (billableMessages.isEmpty()) {
            log.debug(
                    "[ClprBundleBilling] no billable messages; submitter {} bears bundle fee {} for {} progress items",
                    submitterId,
                    bundleFeeTinybars,
                    progressItems);
            return 0L;
        }
        final long rewardTinybars = toTinybars(clampedMultiply(progressItems, progressRewardTinycents));
        final long sharedCost = clampedAdd(bundleFeeTinybars, rewardTinybars);
        final long totalMessages =
                billableMessages.values().stream().mapToLong(Long::longValue).sum();
        long messagesSoFar = 0L;
        long billedSoFar = 0L;
        long paid = 0L;
        for (final var entry : billableMessages.entrySet()) {
            final var connectorAccountId = entry.getKey();
            messagesSoFar += entry.getValue();
            // Each share is the difference of floored cumulative shares, so the shares sum to exactly sharedCost
            final long billedThrough = proportionOf(sharedCost, messagesSoFar, totalMessages);
            final long share = billedThrough - billedSoFar;
            billedSoFar = billedThrough;
            if (share <= 0 || connectorAccountId.equals(submitterId)) {
                continue;
            }
            final var connectorAccount = accountStore.getAccountById(connectorAccountId);
            final long payable = Math.min(share, connectorAccount == null ? 0L : connectorAccount.tinybarBalance());
            if (payable > 0) {
                tokenServiceApi.transferFromTo(connectorAccountId, submitterId, payable);
                paid += payable;
            }
            log.debug(
                    "[ClprBundleBilling] connector {} billed {} of shared cost for {}/{} messages (paid {})",
                    connectorAccountId,
                    share,
                    entry.getValue(),
                    totalMessages,
                    payable);
        }
        log.debug(
                "[ClprBundleBilling] settled bundle submitter={} bundleFee={} progressItems={} reward={} "
                        + "sharedCost={} paid={}",
                submitterId,
                bundleFeeTinybars,
                progressItems,
                rewardTinybars,
                sharedCost,
                paid);
        return paid;
    }

    private long toTinybars(final long tinycents) {
        return tinycentsToTinybars(tinycents, fromPbj(exchangeRate));
    }

    private static long proportionOf(final long amount, final long numerator, final long denominator) {
        return BigInteger.valueOf(amount)
                .multiply(BigInteger.valueOf(numerator))
                .divide(BigInteger.valueOf(denominator))
                .longValueExact();
    }
}
