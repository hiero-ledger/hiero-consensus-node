// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.gas;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_GAS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_TX_FEE;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.node.app.service.contract.impl.hevm.HederaEvmContext;
import com.hedera.node.app.service.contract.impl.hevm.HederaEvmTransaction;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import com.hedera.node.app.service.contract.impl.state.HederaEvmAccount;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.ResourceExhaustedException;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;

/**
 * Implements the Hedera gas charging logic. The main difference from Besu here is that we can have a
 * "relayer" account from an {@code EthereumTransaction}, in addition to the standard EOA {@code sender}
 * account.
 *
 * <p>As with Besu, the sender offers a gas price for the transaction; and combined with the gas limit
 * for the transaction, this implies a total cost for gas the sender is willing to pay.
 *
 * <p>So the relayer may offer a <i>gas allowance</i> to help pay for gas, in addition to what
 * the sender has offered. The relayer's gas allowance is used only when the network's gas cost is
 * greater than what the sender has offered.
 */
@Singleton
public class CustomGasCharging {
    /** One HBAR denominated in tinybars */
    public static final long ONE_HBAR_IN_TINYBARS = 100_000_000L;

    private final GasCalculator gasCalculator;

    /**
     * @param gasCalculator the gas calculator to use
     */
    @Inject
    public CustomGasCharging(@NonNull final GasCalculator gasCalculator) {
        this.gasCalculator = gasCalculator;
    }

    /**
     * If the actual gas used by a transaction merits a refund, then do the refund using
     * relayer-aware logic. The relayer gets refund priority, so any charged allowance
     * is first refunded, with the sender only getting a refund if there is any left over.
     *
     * @param unusedGas the actual gas used by the transaction
     * @param allowanceUsed the amount of the relayer's gas allowance used
     * @param sender the sender account
     * @param relayer the relayer account, if present
     * @param context the context of the transaction, including the network gas price
     * @param worldUpdater the world updater for the transaction
     */
    public void maybeRefundGiven(
            final long unusedGas,
            final long allowanceUsed,
            @NonNull final HederaEvmAccount sender,
            @Nullable final HederaEvmAccount relayer,
            @NonNull final HederaEvmContext context,
            @NonNull final HederaWorldUpdater worldUpdater) {
        requireNonNull(sender);
        requireNonNull(context);
        requireNonNull(worldUpdater);
        if (context.isNoopGasContext() || unusedGas == 0) {
            return;
        }
        final var refund = unusedGas * context.gasPrice();
        if (allowanceUsed > 0) {
            requireNonNull(relayer);
            worldUpdater.refundGasFee(relayer.hederaId(), Math.min(allowanceUsed, refund));
            if (refund > allowanceUsed) {
                worldUpdater.refundGasFee(sender.hederaId(), refund - allowanceUsed);
            }
        } else {
            worldUpdater.refundGasFee(sender.hederaId(), refund);
        }
    }

    /**
     * Tries to charge gas for the given transaction based on the pre-fetched sender and relayer accounts,
     * within the given context and world updater.
     *
     * <p><b>IMPORTANT:</b> Applies <i>any</i> charges only if <i>all</i> charges will succeed. This lets us
     * avoid reverting the root updater in the case of insufficient balances; which is nice since this
     * updater will contain non-gas fees we want to keep intact (for operations other than {@code ContractCall}).
     *
     * <p>Even if there are no gas charges, still returns the intrinsic gas cost of the transaction.
     *
     * @param sender  the sender account
     * @param relayer the relayer account
     * @param context the context of the transaction, including the network gas price
     * @param worldUpdater the world updater for the transaction
     * @param transaction the transaction to charge gas for
     * @return the result of the gas charging
     * @throws HandleException if the gas charging fails for any reason
     */
    public GasCharges chargeForGas(
            @NonNull final HederaEvmAccount sender,
            @Nullable final HederaEvmAccount relayer,
            @NonNull final HederaEvmContext context,
            @NonNull final HederaWorldUpdater worldUpdater,
            @NonNull final HederaEvmTransaction transaction) {
        requireNonNull(sender);
        requireNonNull(context);
        requireNonNull(worldUpdater);
        requireNonNull(transaction);

        final var intrinsicGas =
                gasCalculator.transactionIntrinsicGasCost(transaction.evmPayload(), transaction.isCreate());
        if (context.isNoopGasContext()) {
            return new GasCharges(intrinsicGas, 0L);
        }
        validateTrue(transaction.gasLimit() >= intrinsicGas, INSUFFICIENT_GAS);
        if (transaction.isEthereumTransaction()) {
            requireNonNull(relayer);
            final var gasCost = transaction.gasCostGiven(context.gasPrice());
            final var allowanceUsed = chargeWithRelayer(sender, relayer, context, worldUpdater, transaction, gasCost);

            // Increment nonce right after the gas is charged
            sender.incrementNonce();

            return new GasCharges(intrinsicGas, allowanceUsed);
        } else {
            chargeWithOnlySender(sender, context, worldUpdater, transaction);
            return new GasCharges(intrinsicGas, 0L);
        }
    }

    /**
     * If the transaction is aborted with a HandleException, adjust the gas charging events in the world updater
     * to reflect the correct gas charges for the transaction.
     *
     * @param sender  the sender accountID
     * @param context the context of the transaction, including the network gas price
     * @param worldUpdater the world updater for the transaction
     * @param transaction the transaction to charge gas for
     * @return intrinsic gas charged for the transaction or 0 if no gas was charged
     * @throws HandleException if the gas charging fails for any reason
     */
    public long possiblyChargeGasForAbortedTransaction(
            @NonNull final AccountID sender,
            @NonNull final HederaEvmContext context,
            @NonNull final HederaWorldUpdater worldUpdater,
            @NonNull final HederaEvmTransaction transaction) {
        requireNonNull(sender);
        requireNonNull(context);
        requireNonNull(worldUpdater);
        requireNonNull(transaction);

        /*
         * There are several possibilities to consider here:
         * 1. The transaction could have been aborted during parsing before any gas was charged. In this case charge intrinsic gas to payer if possible.
         * 2. The transaction could have been aborted with a ResourceExhaustedException, which means we need to remove all gas charging events and
         *     a. If the transaction is an Ethereum transaction, charge the relayer or sender for the intrinsic gas based on gas price.
         *     b. otherwise, charge the sender for the intrinsic gas.
         * In all other cases do nothing as the gas charging events are correct - even in the case of a non ResourceExhaustedException exception
         * in which case the refund is already handled in the {@link FrameRunner#runToCompletion} method.
         *
         */

        final var intrinsicGas =
                gasCalculator.transactionIntrinsicGasCost(transaction.evmPayload(), transaction.isCreate());
        if (!worldUpdater.hasGasChargingEvents()) {
            return chargeSenderIntrinsicGas(sender, context, worldUpdater, intrinsicGas);
        }
        if (transaction.exception() instanceof ResourceExhaustedException) {
            worldUpdater.resetGasChargingEvents();
            if (transaction.isEthereumTransaction()) {
                final var senderAccount = worldUpdater.getHederaAccount(sender);
                final var relayerAccount = worldUpdater.getHederaAccount(requireNonNull(transaction.relayerId()));
                if (senderAccount == null || relayerAccount == null) {
                    return chargeSenderIntrinsicGas(sender, context, worldUpdater, intrinsicGas);
                }
                chargeWithRelayer(
                        senderAccount,
                        relayerAccount,
                        context,
                        worldUpdater,
                        transaction,
                        gasCostGiven(intrinsicGas, context.gasPrice()));
                return intrinsicGas;
            } else {
                return chargeSenderIntrinsicGas(sender, context, worldUpdater, intrinsicGas);
            }
        }
        return 0;
    }

    private long chargeSenderIntrinsicGas(
            @NonNull AccountID sender,
            @NonNull HederaEvmContext context,
            @NonNull HederaWorldUpdater worldUpdater,
            long intrinsicGas) {

        if (worldUpdater.getHederaAccount(sender) != null) {
            final var fee =
                    feeForAborted(sender, context, worldUpdater, gasCostGiven(intrinsicGas, context.gasPrice()));
            worldUpdater.collectGasFee(sender, fee, false);
            return intrinsicGas;
        }
        return 0;
    }

    private long feeForAborted(
            @NonNull final AccountID accountID,
            @NonNull final HederaEvmContext context,
            @NonNull final HederaWorldUpdater worldUpdater,
            final long intrinsicGas) {
        requireNonNull(accountID);
        requireNonNull(context);
        requireNonNull(worldUpdater);

        final var hederaAccount = worldUpdater.getHederaAccount(accountID);
        requireNonNull(hederaAccount);
        final var fee = Math.min(
                gasCostGiven(intrinsicGas, context.gasPrice()),
                hederaAccount.getBalance().toLong());
        // protective check to ensure that the fee is not excessive
        final var protectedFee = Math.min(fee, ONE_HBAR_IN_TINYBARS);
        validateTrue(hederaAccount.getBalance().toLong() >= protectedFee, INSUFFICIENT_PAYER_BALANCE);
        return protectedFee;
    }

    private void chargeWithOnlySender(
            @NonNull final HederaEvmAccount sender,
            @NonNull final HederaEvmContext context,
            @NonNull final HederaWorldUpdater worldUpdater,
            @NonNull final HederaEvmTransaction transaction) {
        validateTrue(
                sender.getBalance().toLong() >= transaction.upfrontCostGiven(context.gasPrice()),
                INSUFFICIENT_PAYER_BALANCE);
        worldUpdater.collectGasFee(sender.hederaId(), transaction.gasCostGiven(context.gasPrice()), false);
    }

    public long chargeWithRelayer(
            @NonNull final HederaEvmAccount sender,
            @NonNull final HederaEvmAccount relayer,
            @NonNull final HederaEvmContext context,
            @NonNull final HederaWorldUpdater worldUpdater,
            @NonNull final HederaEvmTransaction transaction,
            final long gasCost) {
        final long senderGasCost;
        final long relayerGasCost;
        if (transaction.requiresFullRelayerAllowance()) {
            senderGasCost = 0L;
            relayerGasCost = gasCost;
        } else if (transaction.offeredGasPrice() >= context.gasPrice()) {
            senderGasCost = gasCost;
            relayerGasCost = 0L;
        } else {
            senderGasCost = transaction.offeredGasCost();
            relayerGasCost = gasCost - transaction.offeredGasCost();
        }
        // Ensure all up-front charges are payable (including any to-be-collected value sent with the initial frame)
        validateTrue(transaction.maxGasAllowance() >= relayerGasCost, INSUFFICIENT_TX_FEE);
        validateTrue(relayer.getBalance().toLong() >= relayerGasCost, INSUFFICIENT_PAYER_BALANCE);
        validateTrue(sender.getBalance().toLong() >= senderGasCost + transaction.value(), INSUFFICIENT_PAYER_BALANCE);
        worldUpdater.collectGasFee(relayer.hederaId(), relayerGasCost, false);
        worldUpdater.collectGasFee(sender.hederaId(), senderGasCost, true);
        return relayerGasCost;
    }

    /**
     * @param gasCharge gas to be charged
     * @param gasPrice the gas price
     * @return return th cost of the gas
     */
    public long gasCostGiven(final long gasCharge, final long gasPrice) {
        try {
            return Math.multiplyExact(gasCharge, gasPrice);
        } catch (ArithmeticException ignore) {
            return Long.MAX_VALUE;
        }
    }
}
