// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1261.utils;

import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.HapiTxnOp.serializedSignedTxFrom;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsdWithin;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.crypto.CryptoTransferSuite.sdec;
import static com.hedera.services.bdd.suites.fees.FileServiceSimpleFeesTest.SINGLE_BYTE_FEE;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.ACCOUNTS_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.CONS_CREATE_TOPIC_BASE_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.CONS_CREATE_TOPIC_INCLUDED_KEYS;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.CRYPTO_CREATE_BASE_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.CRYPTO_CREATE_INCLUDED_HOOKS;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.CRYPTO_CREATE_INCLUDED_KEYS;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.CRYPTO_TRANSFER_BASE_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.CRYPTO_TRANSFER_INCLUDED_ACCOUNTS;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.CRYPTO_TRANSFER_INCLUDED_FUNGIBLE_TOKENS;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.CRYPTO_TRANSFER_INCLUDED_GAS;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.CRYPTO_TRANSFER_INCLUDED_HOOK_EXECUTION;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.CRYPTO_TRANSFER_INCLUDED_NON_FUNGIBLE_TOKENS;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.FUNGIBLE_TOKENS_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.GAS_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.HOOK_EXECUTION_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.HOOK_UPDATES_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.KEYS_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.NETWORK_MULTIPLIER;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.NODE_BASE_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.NODE_INCLUDED_SIGNATURES;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.NON_FUNGIBLE_TOKENS_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.SIGNATURE_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.TOKEN_TRANSFER_BASE_CUSTOM_FEES_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.TOKEN_TRANSFER_BASE_FEE_USD;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.protobuf.InvalidProtocolBufferException;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hederahashgraph.api.proto.java.Transaction;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntToDoubleFunction;

public class FeesChargingUtils {
    private static final int NODE_INCLUDED_BYTES = 1024;

    /** tinycents -> USD */
    public static double tinycentsToUsd(long tinycents) {
        return tinycents / 100_000_000.0 / 100.0;
    }

    /**
     * SimpleFees formula for CryptoCreate:
     * node    = NODE_BASE + SIGNATURE_FEE * max(0, sigs - includedSigsNode)
     * network = node * NETWORK_MULTIPLIER
     * service = CRYPTO_CREATE_BASE
     *         + KEYS_FEE  * max(0, keys - includedKeysService)
     *         + HOOKS_FEE * max(0, hooks - includedHooksService)
     * total   = node + network + service
     */
    public static double expectedCryptoCreateFullFeeUsd(long sigs, long keys, long hooks) {
        return expectedCryptoCreateFullFeeUsd(sigs, keys, hooks, 0);
    }

    public static double expectedCryptoCreateFullFeeUsd(long sigs, long keys, long hooks, int txnSize) {
        // ----- node fees -----
        final long sigExtrasNode = Math.max(0L, sigs - NODE_INCLUDED_SIGNATURES);
        final double nodeExtrasFee = sigExtrasNode * SIGNATURE_FEE_USD;
        final double nodeFee = NODE_BASE_FEE_USD + nodeExtrasFee + nodeFeeFromBytesUsd(txnSize);

        // ----- network fees -----
        final double networkFee = nodeFee * NETWORK_MULTIPLIER;

        // ----- service fees -----
        final long keyExtrasService = Math.max(0L, keys - CRYPTO_CREATE_INCLUDED_KEYS);
        final long hookExtrasService = Math.max(0L, hooks - CRYPTO_CREATE_INCLUDED_HOOKS);
        final double serviceExtrasFee = keyExtrasService * KEYS_FEE_USD + hookExtrasService * HOOK_UPDATES_FEE_USD;
        final double serviceFee = CRYPTO_CREATE_BASE_FEE_USD + serviceExtrasFee;

        return nodeFee + networkFee + serviceFee;
    }

    /**
     * Overload when there are no hooks extras.
     */
    public static double expectedCryptoCreateFullFeeUsd(long sigs, long keys) {
        return expectedCryptoCreateFullFeeUsd(sigs, keys, 0L, 0);
    }

    public static double expectedCryptoCreateFullFeeUsd(long sigs, long keys, int txnSize) {
        return expectedCryptoCreateFullFeeUsd(sigs, keys, 0L, txnSize);
    }

    /**
     *  Simple fees calculation for CryptoCreate with node fee only
     */
    public static double expectedCryptoCreateNetworkFeeOnlyUsd(long sigs) {
        return expectedCryptoCreateNetworkFeeOnlyUsd(sigs, 0);
    }

    public static double expectedCryptoCreateNetworkFeeOnlyUsd(long sigs, int txnSize) {
        // ----- node fees -----
        final long sigExtrasNode = Math.max(0L, sigs - NODE_INCLUDED_SIGNATURES);
        final double nodeExtrasFee = sigExtrasNode * SIGNATURE_FEE_USD;
        final double nodeFee = NODE_BASE_FEE_USD + nodeExtrasFee + nodeFeeFromBytesUsd(txnSize);

        // ----- network fees -----
        return nodeFee * NETWORK_MULTIPLIER;
    }

    /**
     * SimpleFees formula for ConsensusCreateTopic:
     * node    = NODE_BASE + SIGNATURE_FEE * max(0, sigs - includedSigsNode)
     * network = node * NETWORK_MULTIPLIER
     * service = CONSENSUS_CREATE_TOPIC_BASE
     *         + KEYS_FEE  * max(0, keys - includedKeysService)
     * total   = node + network + service
     */
    public static double expectedTopicCreateFullFeeUsd(long sigs, long keys) {
        return expectedTopicCreateFullFeeUsd(sigs, keys, 0);
    }

    public static double expectedTopicCreateFullFeeUsd(long sigs, long keys, int txnSize) {
        // ----- node fees -----
        final long sigExtrasNode = Math.max(0L, sigs - NODE_INCLUDED_SIGNATURES);
        final double nodeExtrasFee = sigExtrasNode * SIGNATURE_FEE_USD;
        final double nodeFee = NODE_BASE_FEE_USD + nodeExtrasFee + nodeFeeFromBytesUsd(txnSize);

        // ----- network fees -----
        final double networkFee = nodeFee * NETWORK_MULTIPLIER;

        // ----- service fees -----
        final long keyExtrasService = Math.max(0L, keys - CONS_CREATE_TOPIC_INCLUDED_KEYS);
        final double serviceExtrasFee = keyExtrasService * KEYS_FEE_USD;
        final double serviceFee = CONS_CREATE_TOPIC_BASE_FEE_USD + serviceExtrasFee;

        return nodeFee + networkFee + serviceFee;
    }

    public static double expectedTopicCreateNetworkFeeOnlyUsd(long sigs) {
        return expectedTopicCreateNetworkFeeOnlyUsd(sigs, 0);
    }

    public static double expectedTopicCreateNetworkFeeOnlyUsd(long sigs, final int txnSize) {
        // ----- node fees -----
        final long sigExtrasNode = Math.max(0L, sigs - NODE_INCLUDED_SIGNATURES);
        final double nodeExtrasFee = sigExtrasNode * SIGNATURE_FEE_USD;
        final double nodeFee = NODE_BASE_FEE_USD + nodeExtrasFee + nodeFeeFromBytesUsd(txnSize);

        // ----- network fees -----
        return nodeFee * NETWORK_MULTIPLIER;
    }

    public static HapiSpecOperation validateChargedUsdWithinWithTxnSize(
            String txnId, IntToDoubleFunction expectedFeeUsd, double allowedPercentDifference) {
        return withOpContext((spec, log) -> {
            final int signedTxnSize = signedTxnSizeFor(spec, txnId);
            final double expected = expectedFeeUsd.applyAsDouble(signedTxnSize);
            allRunFor(spec, validateChargedUsdWithin(txnId, expected, allowedPercentDifference));
        });
    }

    public static HapiSpecOperation validateChargedFeeToUsdWithTxnSize(
            String txnId,
            AtomicLong initialBalance,
            AtomicLong afterBalance,
            IntToDoubleFunction expectedFeeUsd,
            double allowedPercentDifference) {
        return withOpContext((spec, log) -> {
            final int signedTxnSize = signedTxnSizeFor(spec, txnId);
            final double expected = expectedFeeUsd.applyAsDouble(signedTxnSize);
            allRunFor(
                    spec,
                    validateChargedFeeToUsd(txnId, initialBalance, afterBalance, expected, allowedPercentDifference));
        });
    }

    public static int signedTxnSizeFor(final HapiSpec spec, final String txnId) throws InvalidProtocolBufferException {
        final var txnBytes = spec.registry().getBytes(txnId);
        final var transaction = Transaction.parseFrom(txnBytes);
        final var signedTxnBytes = serializedSignedTxFrom(transaction);
        return signedTxnBytes.length;
    }

    public static double nodeFeeFromBytesUsd(final int txnSize) {
        final var nodeBytesOverage = Math.max(0, txnSize - NODE_INCLUDED_BYTES);
        return nodeBytesOverage * SINGLE_BYTE_FEE;
    }

    /**
     * SimpleFees formula for CryptoTransfer:
     * node    = NODE_BASE_FEE_USD + SIGNATURE_FEE_USD * max(0, sigs - NODE_INCLUDED_SIGNATURES)
     * network = node * NETWORK_MULTIPLIER
     * service = TOKEN_TRANSFER_BASE_FEE_USD
     *         + HOOK_EXECUTION_FEE_USD * max(0, uniqueHooksExecuted - CRYPTO_TRANSFER_INCLUDED_HOOK_EXECUTION)
     *         + ACCOUNTS_FEE_USD * max(0, uniqueAccounts - CRYPTO_TRANSFER_INCLUDED_ACCOUNTS)
     *         + FUNGIBLE_TOKENS_FEE_USD * max(0, uniqueFungibleTokens - CRYPTO_TRANSFER_INCLUDED_FUNGIBLE_TOKENS)
     *         + NON_FUNGIBLE_TOKENS_FEE_USD * max(0, uniqueNonFungibleTokens - CRYPTO_TRANSFER_INCLUDED_NON_FUNGIBLE_TOKENS)
     * total   = node + network + service
     */
    private static double extra(long actual, long included, double feePerUnit) {
        final long extras = Math.max(0L, actual - included);
        return extras * feePerUnit;
    }

    public static double expectedCryptoTransferFullFeeUsd(
            long sigs,
            long uniqueHooksExecuted,
            long uniqueAccounts,
            long uniqueFungibleTokens,
            long uniqueNonFungibleTokens,
            long gasAmount,
            boolean includesHbarBaseFee,
            boolean includesTokenTransferBase,
            boolean includesTokenTransferWithCustomBase) {

        // ----- node fees -----
        final double nodeExtrasFee = extra(sigs, NODE_INCLUDED_SIGNATURES, SIGNATURE_FEE_USD);
        final double nodeFee = NODE_BASE_FEE_USD + nodeExtrasFee;

        // ----- network fees -----
        final double networkFee = nodeFee * NETWORK_MULTIPLIER;

        // ---- service base fees -----
        double serviceBaseFee = 0.0;
        if (includesHbarBaseFee) {
            serviceBaseFee += CRYPTO_TRANSFER_BASE_FEE_USD;
        }
        if (includesTokenTransferBase) {
            serviceBaseFee += TOKEN_TRANSFER_BASE_FEE_USD;
        }
        if (includesTokenTransferWithCustomBase) {
            serviceBaseFee += TOKEN_TRANSFER_BASE_CUSTOM_FEES_USD;
        }
        // ---- service extras fees -----
        final double hooksExtrasFee =
                extra(uniqueHooksExecuted, CRYPTO_TRANSFER_INCLUDED_HOOK_EXECUTION, HOOK_EXECUTION_FEE_USD);
        final double accountsExtrasFee = extra(uniqueAccounts, CRYPTO_TRANSFER_INCLUDED_ACCOUNTS, ACCOUNTS_FEE_USD);
        final double uniqueFungibleTokensExtrasFee =
                extra(uniqueFungibleTokens, CRYPTO_TRANSFER_INCLUDED_FUNGIBLE_TOKENS, FUNGIBLE_TOKENS_FEE_USD);
        final double uniqueNonFungibleTokensExtrasFee = extra(
                uniqueNonFungibleTokens, CRYPTO_TRANSFER_INCLUDED_NON_FUNGIBLE_TOKENS, NON_FUNGIBLE_TOKENS_FEE_USD);
        final double gasExtrasFee = extra(gasAmount, CRYPTO_TRANSFER_INCLUDED_GAS, GAS_FEE_USD);

        final double serviceFee = serviceBaseFee
                + hooksExtrasFee
                + accountsExtrasFee
                + uniqueFungibleTokensExtrasFee
                + uniqueNonFungibleTokensExtrasFee
                + gasExtrasFee;

        return nodeFee + networkFee + serviceFee;
    }

    public static double expectedCryptoTransferHbarFullFeeUsd(
            long sigs,
            long uniqueHooksExecuted,
            long uniqueAccounts,
            long uniqueFungibleTokens,
            long uniqueNonFungibleTokens,
            long gasAmount) {

        return expectedCryptoTransferFullFeeUsd(
                sigs,
                uniqueHooksExecuted,
                uniqueAccounts,
                uniqueFungibleTokens,
                uniqueNonFungibleTokens,
                gasAmount,
                true,
                false,
                false);
    }

    public static double expectedCryptoTransferFTFullFeeUsd(
            long sigs,
            long uniqueHooksExecuted,
            long uniqueAccounts,
            long uniqueFungibleTokens,
            long uniqueNonFungibleTokens,
            long gasAmount) {

        return expectedCryptoTransferFullFeeUsd(
                sigs,
                uniqueHooksExecuted,
                uniqueAccounts,
                uniqueFungibleTokens,
                uniqueNonFungibleTokens,
                gasAmount,
                false,
                true,
                false);
    }

    public static double expectedCryptoTransferNFTFullFeeUsd(
            long sigs,
            long uniqueHooksExecuted,
            long uniqueAccounts,
            long uniqueFungibleTokens,
            long uniqueNonFungibleTokens,
            long gasAmount) {

        return expectedCryptoTransferFullFeeUsd(
                sigs,
                uniqueHooksExecuted,
                uniqueAccounts,
                uniqueFungibleTokens,
                uniqueNonFungibleTokens,
                gasAmount,
                false,
                true,
                false);
    }

    public static double expectedCryptoTransferFTAndNFTFullFeeUsd(
            long sigs,
            long uniqueHooksExecuted,
            long uniqueAccounts,
            long uniqueFungibleTokens,
            long uniqueNonFungibleTokens,
            long gasAmount) {

        return expectedCryptoTransferFullFeeUsd(
                sigs,
                uniqueHooksExecuted,
                uniqueAccounts,
                uniqueFungibleTokens,
                uniqueNonFungibleTokens,
                gasAmount,
                false,
                true,
                false);
    }

    public static double expectedCryptoTransferHBARAndFTFullFeeUsd(
            long sigs,
            long uniqueHooksExecuted,
            long uniqueAccounts,
            long uniqueFungibleTokens,
            long uniqueNonFungibleTokens,
            long gasAmount) {

        return expectedCryptoTransferFullFeeUsd(
                sigs,
                uniqueHooksExecuted,
                uniqueAccounts,
                uniqueFungibleTokens,
                uniqueNonFungibleTokens,
                gasAmount,
                true,
                true,
                false);
    }

    public static double expectedCryptoTransferHBARAndNFTFullFeeUsd(
            long sigs,
            long uniqueHooksExecuted,
            long uniqueAccounts,
            long uniqueFungibleTokens,
            long uniqueNonFungibleTokens,
            long gasAmount) {

        return expectedCryptoTransferFullFeeUsd(
                sigs,
                uniqueHooksExecuted,
                uniqueAccounts,
                uniqueFungibleTokens,
                uniqueNonFungibleTokens,
                gasAmount,
                true,
                true,
                false);
    }

    public static double expectedCryptoTransferHBARAndFTAndNFTFullFeeUsd(
            long sigs,
            long uniqueHooksExecuted,
            long uniqueAccounts,
            long uniqueFungibleTokens,
            long uniqueNonFungibleTokens,
            long gasAmount) {

        return expectedCryptoTransferFullFeeUsd(
                sigs,
                uniqueHooksExecuted,
                uniqueAccounts,
                uniqueFungibleTokens,
                uniqueNonFungibleTokens,
                gasAmount,
                true,
                true,
                false);
    }

    public static double expectedCryptoTransferTokenWithCustomFullFeeUsd(
            long sigs,
            long uniqueHooksExecuted,
            long uniqueAccounts,
            long uniqueFungibleTokens,
            long uniqueNonFungibleTokens,
            long gasAmount) {

        return expectedCryptoTransferFullFeeUsd(
                sigs,
                uniqueHooksExecuted,
                uniqueAccounts,
                uniqueFungibleTokens,
                uniqueNonFungibleTokens,
                gasAmount,
                false,
                false,
                true);
    }

    public static double expectedCryptoTransferNetworkFeeOnlyUsd(long sigs) {

        // ----- node fees -----
        final double nodeExtrasFee = extra(sigs, NODE_INCLUDED_SIGNATURES, SIGNATURE_FEE_USD);
        final double nodeFee = NODE_BASE_FEE_USD + nodeExtrasFee;

        // ----- network fees -----
        return nodeFee * NETWORK_MULTIPLIER;
    }

    public static double expectedCryptoTransferNetworkFeeOnlyUsd(long sigs, final int txnSize) {
        // ----- node fees -----
        final double nodeExtrasFee = extra(sigs, NODE_INCLUDED_SIGNATURES, SIGNATURE_FEE_USD);
        final double nodeFee = NODE_BASE_FEE_USD + nodeExtrasFee + nodeFeeFromBytesUsd(txnSize);

        // ----- network fees -----
        return nodeFee * NETWORK_MULTIPLIER;
    }
    /**
     * Validates that the charged fee for a transaction (in USD) is within an allowed percent difference
     * from the expected fee.
     *
     * @param txnId                     The transaction ID to validate.
     * @param initialBalance            The initial balance of the payer before the transaction.
     * @param afterBalance              The balance of the payer after the transaction.
     * @param expectedUsd               The expected fee in USD.
     * @param allowedPercentDifference  The allowed percent difference between the expected and actual fee.
     * @return A HapiSpecOperation that performs the validation.
     */
    public static HapiSpecOperation validateChargedFeeToUsd(
            String txnId,
            AtomicLong initialBalance,
            AtomicLong afterBalance,
            double expectedUsd,
            double allowedPercentDifference) {
        return withOpContext((spec, log) -> {

            // Calculate actual fee in tinybars (negative delta)
            final long initialBalanceTinybars = initialBalance.get();
            final long afterBalanceTinybars = afterBalance.get();
            final long deltaTinybars = initialBalanceTinybars - afterBalanceTinybars;

            log.info("---- Balance validation ----");
            log.info("Balance before (tinybars): {}", initialBalanceTinybars);
            log.info("Balance after (tinybars): {}", afterBalanceTinybars);
            log.info("Delta (tinybars): {}", deltaTinybars);

            if (deltaTinybars <= 0) {
                throw new AssertionError("Payer was not charged — delta: " + deltaTinybars);
            }

            // Fetch the inner record to get the exchange rate
            final var subOp = getTxnRecord(txnId).assertingNothingAboutHashes();
            allRunFor(spec, subOp);
            final var record = subOp.getResponseRecord();

            log.info("Inner txn status: {}", record.getReceipt().getStatus());

            final var rate = record.getReceipt().getExchangeRate().getCurrentRate();
            final long hbarEquiv = rate.getHbarEquiv();
            final long centEquiv = rate.getCentEquiv();

            // Convert tinybars to USD
            final double chargedUsd = (1.0 * deltaTinybars)
                    / ONE_HBAR // tinybars -> HBAR
                    / hbarEquiv // HBAR -> "rate HBAR"
                    * centEquiv // "rate HBAR" -> cents
                    / 100.0; // cents -> USD

            log.info("ExchangeRate current: hbarEquiv={}, centEquiv={}", hbarEquiv, centEquiv);
            log.info("Charged (approx) USD = {}", chargedUsd);
            log.info("Expected USD fee    = {}", expectedUsd);

            final double diff = Math.abs(chargedUsd - expectedUsd);
            final double pctDiff = (expectedUsd == 0.0)
                    ? (chargedUsd == 0.0 ? 0.0 : Double.POSITIVE_INFINITY)
                    : (diff / expectedUsd) * 100.0;

            log.info("Node fee difference: abs={} USD, pct={}%", diff, pctDiff);

            assertEquals(
                    expectedUsd,
                    chargedUsd,
                    (allowedPercentDifference / 100.0) * expectedUsd,
                    String.format(
                            "%s fee (%s) more than %.2f percent different than expected!",
                            sdec(chargedUsd, 4), txnId, allowedPercentDifference));
        });
    }
}
