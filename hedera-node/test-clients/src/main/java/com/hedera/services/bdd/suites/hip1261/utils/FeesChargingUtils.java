// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1261.utils;

import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.HapiTxnOp.serializedSignedTxFrom;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertionsHold;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doWithStartupConfig;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.getChargedUsedForInnerTxn;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsd;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsdWithChild;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsdWithin;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateInnerTxnChargedUsd;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.crypto.CryptoTransferSuite.sdec;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.ACCOUNTS_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.AIRDROP_CANCEL_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.AIRDROP_CLAIM_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.ATOMIC_BATCH_BASE_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.BATCH_BASE_FEE;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.CONS_CREATE_TOPIC_BASE_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.CONS_CREATE_TOPIC_INCLUDED_KEYS;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.CONS_CREATE_TOPIC_WITH_CUSTOM_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.CONS_DELETE_TOPIC_BASE_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.CONS_SUBMIT_MESSAGE_BASE_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.CONS_SUBMIT_MESSAGE_INCLUDED_BYTES;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.CONS_SUBMIT_MESSAGE_WITH_CUSTOM_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.CONS_UPDATE_TOPIC_BASE_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.CONS_UPDATE_TOPIC_INCLUDED_KEYS;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.CRYPTO_APPROVE_ALLOWANCE_BASE_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.CRYPTO_APPROVE_ALLOWANCE_EXTRA_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.CRYPTO_APPROVE_ALLOWANCE_INCLUDED_COUNT;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.CRYPTO_CREATE_BASE_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.CRYPTO_CREATE_INCLUDED_HOOKS;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.CRYPTO_CREATE_INCLUDED_KEYS;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.CRYPTO_DELETE_ALLOWANCE_BASE_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.CRYPTO_DELETE_ALLOWANCE_EXTRA_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.CRYPTO_DELETE_ALLOWANCE_INCLUDED_COUNT;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.CRYPTO_DELETE_BASE_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.CRYPTO_TRANSFER_BASE_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.CRYPTO_TRANSFER_INCLUDED_ACCOUNTS;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.CRYPTO_TRANSFER_INCLUDED_GAS;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.CRYPTO_TRANSFER_INCLUDED_HOOK_EXECUTION;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.CRYPTO_UPDATE_BASE_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.CRYPTO_UPDATE_INCLUDED_HOOKS;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.CRYPTO_UPDATE_INCLUDED_KEYS;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.FILE_APPEND_BASE_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.FILE_APPEND_INCLUDED_BYTES;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.FILE_CREATE_BASE_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.FILE_CREATE_INCLUDED_BYTES;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.FILE_CREATE_INCLUDED_KEYS;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.FILE_DELETE_BASE_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.GAS_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.HOOK_EXECUTION_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.HOOK_UPDATES_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.INCLUDED_TOKEN_TYPES;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.KEYS_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.NETWORK_MULTIPLIER;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.NODE_BASE_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.NODE_INCLUDED_BYTES;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.NODE_INCLUDED_SIGNATURES;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.PROCESSING_BYTES_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.SIGNATURE_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.STATE_BYTES_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.TOKEN_ASSOCIATE_BASE_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.TOKEN_ASSOCIATE_EXTRA_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.TOKEN_ASSOCIATE_INCLUDED_TOKENS;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.TOKEN_BURN_BASE_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.TOKEN_CREATE_BASE_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.TOKEN_CREATE_INCLUDED_KEYS;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.TOKEN_CREATE_WITH_CUSTOM_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.TOKEN_DELETE_BASE_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.TOKEN_DISSOCIATE_BASE_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.TOKEN_FEE_SCHEDULE_UPDATE_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.TOKEN_FREEZE_BASE_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.TOKEN_GRANT_KYC_BASE_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.TOKEN_MINT_BASE_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.TOKEN_MINT_INCLUDED_NFT;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.TOKEN_MINT_NFT_BASE_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.TOKEN_MINT_NFT_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.TOKEN_PAUSE_BASE_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.TOKEN_REJECT_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.TOKEN_REVOKE_KYC_BASE_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.TOKEN_TRANSFER_BASE_CUSTOM_FEES_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.TOKEN_TRANSFER_BASE_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.TOKEN_TYPES_FEE;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.TOKEN_UNFREEZE_BASE_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.TOKEN_UNPAUSE_BASE_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.TOKEN_UPDATE_BASE_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.TOKEN_UPDATE_INCLUDED_KEYS;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.TOKEN_UPDATE_INCLUDED_NFT_COUNT;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.TOKEN_UPDATE_NFT_FEE;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.TOKEN_WIPE_BASE_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.UTIL_PRNG_BASE_FEE_USD;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.protobuf.InvalidProtocolBufferException;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.utilops.CustomSpecAssert;
import com.hederahashgraph.api.proto.java.Transaction;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntToDoubleFunction;
import org.apache.logging.log4j.Logger;
import org.hiero.hapi.support.fees.Extra;

public class FeesChargingUtils {

    // ------ Fees calculation utils ------//

    /** tinycents -> USD */
    public static double tinycentsToUsd(long tinycents) {
        return tinycents / 100_000_000.0 / 100.0;
    }

    /**
     * SimpleFees formula for node fees only:
     * node = NODE_BASE + SIGNATURE_FEE * max(0, sigs - includedSigsNode)
     */
    private static double expectedNodeFeeUsd(long sigs, int txnSize) {
        final long sigExtrasNode = Math.max(0L, sigs - NODE_INCLUDED_SIGNATURES);
        final double nodeExtrasFee = sigExtrasNode * SIGNATURE_FEE_USD;
        return NODE_BASE_FEE_USD + nodeExtrasFee + nodeFeeFromBytesUsd(txnSize);
    }

    /**
     * SimpleFees formula for network fees only:
     * network = node * NETWORK_MULTIPLIER
     */
    private static double expectedNetworkFeeUsd(long sigs, int txnSize) {
        return expectedNodeFeeUsd(sigs, txnSize) * NETWORK_MULTIPLIER;
    }

    /**
     * SimpleFees formula for node + network fees:
     * node    = NODE_BASE + SIGNATURE_FEE * max(0, sigs - includedSigsNode)
     * network = node * NETWORK_MULTIPLIER
     * total   = node + network
     */
    private static double expectedNodeAndNetworkFeeUsd(long sigs, int txnSize) {
        // ----- node fees -----
        final long sigExtrasNode = Math.max(0L, sigs - NODE_INCLUDED_SIGNATURES);
        final double nodeExtrasFee = sigExtrasNode * SIGNATURE_FEE_USD;
        final double nodeFee = NODE_BASE_FEE_USD + nodeExtrasFee + nodeFeeFromBytesUsd(txnSize);

        // ----- network fees -----
        final double networkFee = nodeFee * NETWORK_MULTIPLIER;

        return nodeFee + networkFee;
    }

    // -------- Validation utils ---------//

    public static HapiSpecOperation validateChargedUsdWithinWithTxnSize(
            String txnId, IntToDoubleFunction expectedFeeUsd, double allowedPercentDifference) {
        return withOpContext((spec, log) -> {
            final int signedTxnSize = signedTxnSizeFor(spec, txnId);
            final double expected = expectedFeeUsd.applyAsDouble(signedTxnSize);
            allRunFor(spec, validateChargedUsdWithin(txnId, expected, allowedPercentDifference));
        });
    }

    public static CustomSpecAssert validateInnerChargedUsdWithinWithTxnSize(
            final String innerTxnId,
            final String parentTxnId,
            final IntToDoubleFunction expectedFeeUsd,
            final double allowedPercentDifference) {

        return assertionsHold((spec, assertLog) -> {
            final int signedInnerTxnSize = signedInnerTxnSizeFor(spec, innerTxnId);

            final double expectedUsd = expectedFeeUsd.applyAsDouble(signedInnerTxnSize);

            final double actualUsdCharged = getChargedUsedForInnerTxn(spec, parentTxnId, innerTxnId);

            assertLog.info(
                    "Inner txn '{}' (parent '{}') signed size={} bytes, expectedUsd={}, actualUsd={}",
                    innerTxnId,
                    parentTxnId,
                    signedInnerTxnSize,
                    expectedUsd,
                    actualUsdCharged);

            assertEquals(
                    expectedUsd,
                    actualUsdCharged,
                    (allowedPercentDifference / 100.0) * expectedUsd,
                    String.format(
                            "%s fee (%s) more than %.2f percent different than expected!",
                            sdec(actualUsdCharged, 4), innerTxnId, allowedPercentDifference));
        });
    }

    public static int signedInnerTxnSizeFor(final HapiSpec spec, final String innerTxnId)
            throws InvalidProtocolBufferException {
        final var txnBytes = spec.registry().getBytes(innerTxnId);
        final var transaction = Transaction.parseFrom(txnBytes);

        final var signedTxnBytes = serializedSignedTxFrom(transaction);
        return signedTxnBytes.length;
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
        return nodeBytesOverage * PROCESSING_BYTES_FEE_USD;
    }

    /**
     * Adds node+network byte overage to a precomputed SimpleFees expected total.
     *
     * <p>Bytes above {@code NODE_INCLUDED_BYTES} are charged in the node fee and then multiplied
     * into the network fee; so we add {@code bytesFee * (NETWORK_MULTIPLIER + 1)}.
     */
    private static double addNodeAndNetworkBytes(final double baseExpectedUsd, final int txnSize) {
        return baseExpectedUsd + nodeFeeFromBytesUsd(txnSize) * (NETWORK_MULTIPLIER + 1);
    }

    private static double extra(long actual, long included, double feePerUnit) {
        final long extras = Math.max(0L, actual - included);
        return extras * feePerUnit;
    }

    public static HapiSpecOperation validateChargedFeeToUsd(
            String txnId,
            AtomicLong initialBalance,
            AtomicLong afterBalance,
            double expectedUsd,
            double allowedPercentDifference) {
        return withOpContext((spec, log) -> {
            final var effectivePercentDiff = Math.max(allowedPercentDifference, 1.0);

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
                    (effectivePercentDiff / 100.0) * expectedUsd,
                    String.format(
                            "%s fee (%s) more than %.2f percent different than expected!",
                            sdec(chargedUsd, 4), txnId, effectivePercentDiff));
        });
    }

    /**
     * Calculates the <em>bytes-dependent portion</em> of the node fee for a transaction.
     *
     * <p>This method retrieves the transaction bytes from the spec registry using the provided
     * {@code txnName}, then computes only the byte-size component of the node fee as follows:</p>
     * <ul>
     *   <li>Node bytes overage = {@code max(0, txnSize - NODE_INCLUDED_BYTES)}</li>
     *   <li>Bytes fee = {@code nodeBytesOverage × PROCESSING_BYTES_FEE_USD × (1 + NETWORK_MULTIPLIER)}</li>
     * </ul>
     *
     * <p><strong>Note:</strong> This returns <em>only</em> the bytes-overage fee portion.
     * The complete node fee includes additional fixed components not calculated here.
     * The first {@code NODE_INCLUDED_BYTES} bytes incur no byte-based fee. Logs transaction
     * details including size, overage bytes, and this bytes-dependent fee.</p>
     *
     * @param spec the HapiSpec containing the transaction registry
     * @param opLog the logger for operation logging
     * @param txnName the transaction name key in the registry
     * @return the bytes-dependent portion of the node fee in USD
     *         (0.0 if transaction fits within included bytes)
     */
    public static double expectedFeeFromBytesFor(HapiSpec spec, Logger opLog, String txnName) {
        final var txnBytes = spec.registry().getBytes(txnName);
        final var txnSize = txnBytes.length;

        final var nodeBytesOverage = Math.max(0, txnSize - NODE_INCLUDED_BYTES);
        double expectedFee = nodeBytesOverage * PROCESSING_BYTES_FEE_USD * (1 + NETWORK_MULTIPLIER);

        opLog.info(
                "Transaction size: {} bytes, node bytes overage: {}, expected fee: {}",
                txnSize,
                nodeBytesOverage,
                expectedFee);
        return expectedFee;
    }

    // -------- CryptoCreate simple fees utils ---------//

    /**
     * SimpleFees formula for CryptoCreate:
     * node    = NODE_BASE + SIGNATURE_FEE * max(0, sigs - includedSigsNode)
     * network = node * NETWORK_MULTIPLIER
     * service = CRYPTO_CREATE_BASE
     *         + KEYS_FEE  * max(0, keys - includedKeysService)
     *         + HOOKS_FEE * max(0, hooks - includedHooksService)
     * total   = node + network + service
     */
    private static double expectedCryptoCreateFullFeeUsd(long sigs, long keys, long hooks, int txnSize) {
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
     * Overload when extras are provided in a map.
     */
    public static double expectedCryptoCreateFullFeeUsd(final Map<Extra, Long> extras) {
        return expectedCryptoCreateFullFeeUsd(
                extras.getOrDefault(Extra.SIGNATURES, 0L),
                extras.getOrDefault(Extra.KEYS, 0L),
                extras.getOrDefault(Extra.HOOK_EXECUTION, 0L),
                Math.toIntExact(extras.getOrDefault(Extra.PROCESSING_BYTES, 0L)));
    }

    /**
     * Overload when there are no hooks extras.
     */
    public static double expectedCryptoCreateFullFeeUsd(long sigs, long keys) {
        return expectedCryptoCreateFullFeeUsd(sigs, keys, 0L, 0);
    }

    /**
     *  * Overload when there are no hooks extras and no txn size extras.
     */
    public static double expectedCryptoCreateFullFeeUsd(long sigs, long keys, int txnSize) {
        return expectedCryptoCreateFullFeeUsd(sigs, keys, 0L, txnSize);
    }

    /**
     * Overload when there are no txn size extras.
     */
    public static double expectedCryptoCreateFullFeeUsd(long sigs, long keys, long hooks) {
        return expectedCryptoCreateFullFeeUsd(sigs, keys, hooks, 0);
    }

    /**
     *  Simple fees calculation for CryptoCreate with node fee only
     */
    public static double expectedCryptoCreateNetworkFeeOnlyUsd(long sigs) {
        return expectedCryptoCreateNetworkFeeOnlyUsd(sigs, 0);
    }

    /**
     *  Simple fees calculation for CryptoCreate with node fee only
     */
    public static double expectedCryptoCreateNetworkFeeOnlyUsd(long sigs, int txnSize) {
        // ----- node fees -----
        final long sigExtrasNode = Math.max(0L, sigs - NODE_INCLUDED_SIGNATURES);
        final double nodeExtrasFee = sigExtrasNode * SIGNATURE_FEE_USD;
        final double nodeFee = NODE_BASE_FEE_USD + nodeExtrasFee + nodeFeeFromBytesUsd(txnSize);

        // ----- network fees -----
        return nodeFee * NETWORK_MULTIPLIER;
    }

    // -------- CryptoDelete simple fees utils ---------//

    /**
     * SimpleFees formula for CryptoDelete:
     * node    = NODE_BASE + SIGNATURE_FEE * max(0, sigs - includedSigsNode)
     * network = node * NETWORK_MULTIPLIER
     * service = CRYPTO_DELETE_BASE
     * total   = node + network + service
     */
    private static double expectedCryptoDeleteFullFeeUsd(long sigs, int txnSize) {
        // ----- node fees -----
        final long sigExtrasNode = Math.max(0L, sigs - NODE_INCLUDED_SIGNATURES);
        final double nodeExtrasFee = sigExtrasNode * SIGNATURE_FEE_USD;
        final double nodeFee = NODE_BASE_FEE_USD + nodeExtrasFee + nodeFeeFromBytesUsd(txnSize);

        // ----- network fees -----
        final double networkFee = nodeFee * NETWORK_MULTIPLIER;

        return nodeFee + networkFee + CRYPTO_DELETE_BASE_FEE_USD;
    }

    /**
     * Overload when extras are provided in a map.
     */
    public static double expectedCryptoDeleteFullFeeUsd(final Map<Extra, Long> extras) {
        return expectedCryptoDeleteFullFeeUsd(
                extras.getOrDefault(Extra.SIGNATURES, 0L),
                Math.toIntExact(extras.getOrDefault(Extra.PROCESSING_BYTES, 0L)));
    }

    // -------- CryptoUpdate simple fees utils ---------//

    /**
     * SimpleFees formula for CryptoUpdate:
     * node    = NODE_BASE + SIGNATURE_FEE * max(0, sigs - includedSigsNode)
     * network = node * NETWORK_MULTIPLIER
     * service = CRYPTO_UPDATE_BASE
     *         + KEYS_FEE  * max(0, keys - includedKeysService)
     *         + HOOKS_FEE * max(0, hooks - includedHooksService)
     * total   = node + network + service
     */
    private static double expectedCryptoUpdateFullFeeUsd(long sigs, long keys, long hooks, int txnSize) {
        // ----- node fees -----
        final long sigExtrasNode = Math.max(0L, sigs - NODE_INCLUDED_SIGNATURES);
        final double nodeExtrasFee = sigExtrasNode * SIGNATURE_FEE_USD;
        final double nodeFee = NODE_BASE_FEE_USD + nodeExtrasFee + nodeFeeFromBytesUsd(txnSize);

        // ----- network fees -----
        final double networkFee = nodeFee * NETWORK_MULTIPLIER;

        // ----- service fees -----
        final long keyExtrasService = Math.max(0L, keys - CRYPTO_UPDATE_INCLUDED_KEYS);
        final double serviceExtrasKeysFee = keyExtrasService * KEYS_FEE_USD;

        final long hooksExtrasService = Math.max(0L, hooks - CRYPTO_UPDATE_INCLUDED_HOOKS);
        final double serviceExtrasHooksFee = hooksExtrasService * HOOK_UPDATES_FEE_USD;

        final double serviceFee = CRYPTO_UPDATE_BASE_FEE_USD + serviceExtrasKeysFee + serviceExtrasHooksFee;

        return nodeFee + networkFee + serviceFee;
    }

    /**
     * Overload when extras are provided in a map.
     */
    public static double expectedCryptoUpdateFullFeeUsd(final Map<Extra, Long> extras) {
        return expectedCryptoUpdateFullFeeUsd(
                extras.getOrDefault(Extra.SIGNATURES, 0L),
                extras.getOrDefault(Extra.KEYS, 0L),
                extras.getOrDefault(Extra.HOOK_EXECUTION, 0L),
                Math.toIntExact(extras.getOrDefault(Extra.PROCESSING_BYTES, 0L)));
    }

    // -------- ConsensusCreateTopic simple fees utils ---------//

    /**
     * SimpleFees formula for ConsensusCreateTopic:
     * node    = NODE_BASE + SIGNATURE_FEE * max(0, sigs - includedSigsNode)
     * network = node * NETWORK_MULTIPLIER
     * service = CONSENSUS_CREATE_TOPIC_BASE
     *         + KEYS_FEE  * max(0, keys - includedKeysService)
     * total   = node + network + service
     */
    private static double expectedTopicCreateFullFeeUsd(long sigs, long keys, int txnSize) {
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

    /**
     * Overload when extras are provided in a map.
     */
    public static double expectedTopicCreateFullFeeUsd(final Map<Extra, Long> extras) {
        return expectedTopicCreateFullFeeUsd(
                extras.getOrDefault(Extra.SIGNATURES, 0L),
                extras.getOrDefault(Extra.KEYS, 0L),
                Math.toIntExact(extras.getOrDefault(Extra.PROCESSING_BYTES, 0L)));
    }

    /**
     * Overload when there are no txn size extras.
     */
    public static double expectedTopicCreateFullFeeUsd(long sigs, long keys) {
        return expectedTopicCreateFullFeeUsd(sigs, keys, 0);
    }

    /**
     * Overload when there are no keys extras and no txn size extras.
     */
    public static double expectedTopicCreateNetworkFeeOnlyUsd(long sigs) {
        return expectedTopicCreateNetworkFeeOnlyUsd(sigs, 0);
    }

    /**
     * Simple fees calculation for ConsensusCreateTopic with node fee only
     */
    public static double expectedTopicCreateNetworkFeeOnlyUsd(long sigs, final int txnSize) {
        // ----- node fees -----
        final long sigExtrasNode = Math.max(0L, sigs - NODE_INCLUDED_SIGNATURES);
        final double nodeExtrasFee = sigExtrasNode * SIGNATURE_FEE_USD;
        final double nodeFee = NODE_BASE_FEE_USD + nodeExtrasFee + nodeFeeFromBytesUsd(txnSize);

        // ----- network fees -----
        return nodeFee * NETWORK_MULTIPLIER;
    }

    // -------- CryptoTransfer simple fees utils ---------//

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
    private static double expectedCryptoTransferFullFeeUsd(
            long sigs,
            long uniqueHooksExecuted,
            long uniqueAccounts,
            long tokenTypes,
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
        final double tokenTypesFee = extra(tokenTypes, INCLUDED_TOKEN_TYPES, TOKEN_TYPES_FEE);
        final double gasExtrasFee = extra(gasAmount, CRYPTO_TRANSFER_INCLUDED_GAS, GAS_FEE_USD);

        final double serviceFee = serviceBaseFee + hooksExtrasFee + accountsExtrasFee + tokenTypesFee + gasExtrasFee;

        return nodeFee + networkFee + serviceFee;
    }

    public static double expectedCryptoTransferFullFeeUsd(
            long sigs,
            long uniqueHooksExecuted,
            long uniqueAccounts,
            long tokenTypes,
            long gasAmount,
            int txnSize,
            boolean includesHbarBaseFee,
            boolean includesTokenTransferBase,
            boolean includesTokenTransferWithCustomBase) {

        // ----- node fees -----
        final double nodeExtrasFee = extra(sigs, NODE_INCLUDED_SIGNATURES, SIGNATURE_FEE_USD);
        final double nodeFee = NODE_BASE_FEE_USD + nodeExtrasFee + nodeFeeFromBytesUsd(txnSize);

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
        final double tokenTypesFee = extra(tokenTypes, INCLUDED_TOKEN_TYPES, TOKEN_TYPES_FEE);
        final double gasExtrasFee = extra(gasAmount, CRYPTO_TRANSFER_INCLUDED_GAS, GAS_FEE_USD);

        final double serviceFee = serviceBaseFee + hooksExtrasFee + accountsExtrasFee + tokenTypesFee + gasExtrasFee;

        return nodeFee + networkFee + serviceFee;
    }

    public static double expectedCryptoTransferHbarFullFeeUsd(
            long sigs, long uniqueHooksExecuted, long uniqueAccounts, long tokenTypes, long gasAmount) {

        return expectedCryptoTransferFullFeeUsd(
                sigs, uniqueHooksExecuted, uniqueAccounts, tokenTypes, gasAmount, true, false, false);
    }

    public static double expectedCryptoTransferHbarFullFeeUsd(
            long sigs, long uniqueHooksExecuted, long uniqueAccounts, long tokenTypes, long gasAmount, int txnSize) {

        return expectedCryptoTransferFullFeeUsd(
                sigs, uniqueHooksExecuted, uniqueAccounts, tokenTypes, gasAmount, txnSize, true, false, false);
    }

    /**
     * Overload when extras are provided in a map.
     */
    public static double expectedCryptoTransferHbarFullFeeUsd(final Map<Extra, Long> extras) {
        return expectedCryptoTransferHbarFullFeeUsd(
                extras.getOrDefault(Extra.SIGNATURES, 0L),
                extras.getOrDefault(Extra.HOOK_EXECUTION, 0L),
                extras.getOrDefault(Extra.ACCOUNTS, 0L),
                extras.getOrDefault(Extra.TOKEN_TYPES, 0L),
                extras.getOrDefault(Extra.GAS, 0L),
                Math.toIntExact(extras.getOrDefault(Extra.PROCESSING_BYTES, 0L)));
    }

    public static double expectedCryptoTransferFTFullFeeUsd(
            long sigs, long uniqueHooksExecuted, long uniqueAccounts, long tokenTypes, long gasAmount) {

        return expectedCryptoTransferFullFeeUsd(
                sigs, uniqueHooksExecuted, uniqueAccounts, tokenTypes, gasAmount, false, true, false);
    }

    public static double expectedCryptoTransferFTFullFeeUsd(
            long sigs, long uniqueHooksExecuted, long uniqueAccounts, long tokenTypes, long gasAmount, int txnSize) {

        return expectedCryptoTransferFullFeeUsd(
                sigs, uniqueHooksExecuted, uniqueAccounts, tokenTypes, gasAmount, txnSize, false, true, false);
    }

    /**
     * Overload when extras are provided in a map.
     */
    public static double expectedCryptoTransferFTFullFeeUsd(final Map<Extra, Long> extras) {

        return expectedCryptoTransferFTFullFeeUsd(
                extras.getOrDefault(Extra.SIGNATURES, 0L),
                extras.getOrDefault(Extra.HOOK_EXECUTION, 0L),
                extras.getOrDefault(Extra.ACCOUNTS, 0L),
                extras.getOrDefault(Extra.TOKEN_TYPES, 0L),
                extras.getOrDefault(Extra.GAS, 0L),
                Math.toIntExact(extras.getOrDefault(Extra.PROCESSING_BYTES, 0L)));
    }

    public static double expectedCryptoTransferNFTFullFeeUsd(
            long sigs, long uniqueHooksExecuted, long uniqueAccounts, long tokenTypes, long gasAmount) {

        return expectedCryptoTransferFullFeeUsd(
                sigs, uniqueHooksExecuted, uniqueAccounts, tokenTypes, gasAmount, false, true, false);
    }

    public static double expectedCryptoTransferNFTFullFeeUsd(
            long sigs, long uniqueHooksExecuted, long uniqueAccounts, long tokenTypes, long gasAmount, int txnSize) {

        return expectedCryptoTransferFullFeeUsd(
                sigs, uniqueHooksExecuted, uniqueAccounts, tokenTypes, gasAmount, txnSize, false, true, false);
    }

    /**
     * Overload when extras are provided in a map.
     */
    public static double expectedCryptoTransferNFTFullFeeUsd(final Map<Extra, Long> extras) {
        return expectedCryptoTransferNFTFullFeeUsd(
                extras.getOrDefault(Extra.SIGNATURES, 0L),
                extras.getOrDefault(Extra.HOOK_EXECUTION, 0L),
                extras.getOrDefault(Extra.ACCOUNTS, 0L),
                extras.getOrDefault(Extra.TOKEN_TYPES, 0L),
                extras.getOrDefault(Extra.GAS, 0L),
                Math.toIntExact(extras.getOrDefault(Extra.PROCESSING_BYTES, 0L)));
    }

    public static double expectedCryptoTransferFTAndNFTFullFeeUsd(
            long sigs, long uniqueHooksExecuted, long uniqueAccounts, long tokenTypes, long gasAmount) {

        return expectedCryptoTransferFullFeeUsd(
                sigs, uniqueHooksExecuted, uniqueAccounts, tokenTypes, gasAmount, false, true, false);
    }

    public static double expectedCryptoTransferFTAndNFTFullFeeUsd(
            long sigs, long uniqueHooksExecuted, long uniqueAccounts, long tokenTypes, long gasAmount, int txnSize) {

        return expectedCryptoTransferFullFeeUsd(
                sigs, uniqueHooksExecuted, uniqueAccounts, tokenTypes, gasAmount, txnSize, false, true, false);
    }

    /**
     * Overload when extras are provided in a map.
     */
    public static double expectedCryptoTransferFTAndNFTFullFeeUsd(final Map<Extra, Long> extras) {
        return expectedCryptoTransferFTAndNFTFullFeeUsd(
                extras.getOrDefault(Extra.SIGNATURES, 0L),
                extras.getOrDefault(Extra.HOOK_EXECUTION, 0L),
                extras.getOrDefault(Extra.ACCOUNTS, 0L),
                extras.getOrDefault(Extra.TOKEN_TYPES, 0L),
                extras.getOrDefault(Extra.GAS, 0L),
                Math.toIntExact(extras.getOrDefault(Extra.PROCESSING_BYTES, 0L)));
    }

    public static double expectedCryptoTransferHBARAndFTFullFeeUsd(
            long sigs, long uniqueHooksExecuted, long uniqueAccounts, long tokenTypes, long gasAmount) {

        return expectedCryptoTransferFullFeeUsd(
                sigs, uniqueHooksExecuted, uniqueAccounts, tokenTypes, gasAmount, true, true, false);
    }

    public static double expectedCryptoTransferHBARAndFTFullFeeUsd(
            long sigs, long uniqueHooksExecuted, long uniqueAccounts, long tokenTypes, long gasAmount, int txnSize) {

        return expectedCryptoTransferFullFeeUsd(
                sigs, uniqueHooksExecuted, uniqueAccounts, tokenTypes, gasAmount, txnSize, true, true, false);
    }

    /**
     * Overload when extras are provided in a map.
     */
    public static double expectedCryptoTransferHBARAndFTFullFeeUsd(final Map<Extra, Long> extras) {
        return expectedCryptoTransferHBARAndFTFullFeeUsd(
                extras.getOrDefault(Extra.SIGNATURES, 0L),
                extras.getOrDefault(Extra.HOOK_EXECUTION, 0L),
                extras.getOrDefault(Extra.ACCOUNTS, 0L),
                extras.getOrDefault(Extra.TOKEN_TYPES, 0L),
                extras.getOrDefault(Extra.GAS, 0L),
                Math.toIntExact(extras.getOrDefault(Extra.PROCESSING_BYTES, 0L)));
    }

    public static double expectedCryptoTransferHBARAndNFTFullFeeUsd(
            long sigs, long uniqueHooksExecuted, long uniqueAccounts, long tokenTypes, long gasAmount) {

        return expectedCryptoTransferFullFeeUsd(
                sigs, uniqueHooksExecuted, uniqueAccounts, tokenTypes, gasAmount, true, true, false);
    }

    public static double expectedCryptoTransferHBARAndNFTFullFeeUsd(
            long sigs, long uniqueHooksExecuted, long uniqueAccounts, long tokenTypes, long gasAmount, int txnSize) {

        return expectedCryptoTransferFullFeeUsd(
                sigs, uniqueHooksExecuted, uniqueAccounts, tokenTypes, gasAmount, txnSize, true, true, false);
    }

    /**
     * Overload when extras are provided in a map.
     */
    public static double expectedCryptoTransferHBARAndNFTFullFeeUsd(final Map<Extra, Long> extras) {
        return expectedCryptoTransferHBARAndNFTFullFeeUsd(
                extras.getOrDefault(Extra.SIGNATURES, 0L),
                extras.getOrDefault(Extra.HOOK_EXECUTION, 0L),
                extras.getOrDefault(Extra.ACCOUNTS, 0L),
                extras.getOrDefault(Extra.TOKEN_TYPES, 0L),
                extras.getOrDefault(Extra.GAS, 0L),
                Math.toIntExact(extras.getOrDefault(Extra.PROCESSING_BYTES, 0L)));
    }

    public static double expectedCryptoTransferHBARAndFTAndNFTFullFeeUsd(
            long sigs, long uniqueHooksExecuted, long uniqueAccounts, long tokenTypes, long gasAmount) {

        return expectedCryptoTransferFullFeeUsd(
                sigs, uniqueHooksExecuted, uniqueAccounts, tokenTypes, gasAmount, true, true, false);
    }

    public static double expectedCryptoTransferHBARAndFTAndNFTFullFeeUsd(
            long sigs, long uniqueHooksExecuted, long uniqueAccounts, long tokenTypes, long gasAmount, int txnSize) {
        return expectedCryptoTransferFullFeeUsd(
                sigs, uniqueHooksExecuted, uniqueAccounts, tokenTypes, gasAmount, txnSize, true, true, false);
    }

    /**
     * Overload when extras are provided in a map.
     */
    public static double expectedCryptoTransferHBARAndFTAndNFTFullFeeUsd(final Map<Extra, Long> extras) {
        return expectedCryptoTransferHBARAndFTAndNFTFullFeeUsd(
                extras.getOrDefault(Extra.SIGNATURES, 0L),
                extras.getOrDefault(Extra.HOOK_EXECUTION, 0L),
                extras.getOrDefault(Extra.ACCOUNTS, 0L),
                extras.getOrDefault(Extra.TOKEN_TYPES, 0L),
                extras.getOrDefault(Extra.GAS, 0L),
                Math.toIntExact(extras.getOrDefault(Extra.PROCESSING_BYTES, 0L)));
    }

    public static double expectedCryptoTransferTokenWithCustomFullFeeUsd(
            long sigs, long uniqueHooksExecuted, long uniqueAccounts, long tokenTypes, long gasAmount) {

        return expectedCryptoTransferFullFeeUsd(
                sigs, uniqueHooksExecuted, uniqueAccounts, tokenTypes, gasAmount, false, false, true);
    }

    /**
     * Overload with transaction size.
     */
    public static double expectedCryptoTransferTokenWithCustomFullFeeUsd(
            long sigs, long uniqueHooksExecuted, long uniqueAccounts, long tokenTypes, long gasAmount, int txnSize) {

        final double fullWithoutBytes = expectedCryptoTransferFullFeeUsd(
                sigs, uniqueHooksExecuted, uniqueAccounts, tokenTypes, gasAmount, false, false, true);

        final double nodeAndNetworkWithoutBytes = expectedNodeAndNetworkFeeUsd(sigs, 0);

        final double serviceOnly = fullWithoutBytes - nodeAndNetworkWithoutBytes;

        final double nodeAndNetworkWithBytes = expectedNodeAndNetworkFeeUsd(sigs, txnSize);

        return nodeAndNetworkWithBytes + serviceOnly;
    }

    /**
     * Overload when extras are provided in a map.
     */
    public static double expectedCryptoTransferTokenWithCustomFullFeeUsd(final Map<Extra, Long> extras) {
        return expectedCryptoTransferTokenWithCustomFullFeeUsd(
                extras.getOrDefault(Extra.SIGNATURES, 0L),
                extras.getOrDefault(Extra.HOOK_EXECUTION, 0L),
                extras.getOrDefault(Extra.ACCOUNTS, 0L),
                extras.getOrDefault(Extra.TOKEN_TYPES, 0L),
                extras.getOrDefault(Extra.GAS, 0L),
                Math.toIntExact(extras.getOrDefault(Extra.PROCESSING_BYTES, 0L)));
    }

    /**
     * Overloads for network fee only.
     */
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
     * Overload when extras are provided in a map.
     */
    public static double expectedCryptoTransferNetworkFeeOnlyUsd(final Map<Extra, Long> extras) {
        return expectedCryptoTransferNetworkFeeOnlyUsd(
                extras.getOrDefault(Extra.SIGNATURES, 0L),
                Math.toIntExact(extras.getOrDefault(Extra.PROCESSING_BYTES, 0L)));
    }

    // -------- ConsensusCreateTopic with custom fee simple fees utils ---------//

    /**
     * SimpleFees formula for ConsensusCreateTopic with custom fee:
     * node    = NODE_BASE + SIGNATURE_FEE * max(0, sigs - includedSigsNode)
     * network = node * NETWORK_MULTIPLIER
     * service = CONS_CREATE_TOPIC_WITH_CUSTOM_FEE
     *         + KEYS_FEE  * max(0, keys - includedKeysService)
     * total   = node + network + service
     */
    public static double expectedTopicCreateWithCustomFeeFullFeeUsd(long sigs, long keys) {
        // ----- node fees -----
        final long sigExtrasNode = Math.max(0L, sigs - NODE_INCLUDED_SIGNATURES);
        final double nodeExtrasFee = sigExtrasNode * SIGNATURE_FEE_USD;
        final double nodeFee = NODE_BASE_FEE_USD + nodeExtrasFee;

        // ----- network fees -----
        final double networkFee = nodeFee * NETWORK_MULTIPLIER;

        // ----- service fees -----
        final long keyExtrasService = Math.max(0L, keys - CONS_CREATE_TOPIC_INCLUDED_KEYS);
        final double serviceExtrasFee = keyExtrasService * KEYS_FEE_USD;
        final double serviceFee =
                CONS_CREATE_TOPIC_BASE_FEE_USD + CONS_CREATE_TOPIC_WITH_CUSTOM_FEE_USD + serviceExtrasFee;

        return nodeFee + networkFee + serviceFee;
    }

    public static double expectedTopicCreateWithCustomFeeFullFeeUsd(long sigs, long keys, int txnSize) {
        return addNodeAndNetworkBytes(expectedTopicCreateWithCustomFeeFullFeeUsd(sigs, keys), txnSize);
    }

    /**
     * Overload when extras are provided in a map.
     */
    public static double expectedTopicCreateWithCustomFeeFullFeeUsd(final Map<Extra, Long> extras) {
        return expectedTopicCreateWithCustomFeeFullFeeUsd(
                extras.getOrDefault(Extra.SIGNATURES, 0L),
                extras.getOrDefault(Extra.KEYS, 0L),
                Math.toIntExact(extras.getOrDefault(Extra.PROCESSING_BYTES, 0L)));
    }

    // -------- ConsensusUpdateTopic simple fees utils ---------//

    /**
     * SimpleFees formula for ConsensusUpdateTopic:
     * node    = NODE_BASE + SIGNATURE_FEE * max(0, sigs - includedSigsNode)
     * network = node * NETWORK_MULTIPLIER
     * service = CONS_UPDATE_TOPIC_BASE
     *         + KEYS_FEE  * max(0, keys - includedKeysService)
     * total   = node + network + service
     */
    public static double expectedTopicUpdateFullFeeUsd(long sigs, long keys) {
        return expectedTopicUpdateFullFeeUsd(sigs, keys, 0);
    }

    public static double expectedTopicUpdateFullFeeUsd(long sigs, long keys, int txnSize) {
        // ----- node fees -----
        final long sigExtrasNode = Math.max(0L, sigs - NODE_INCLUDED_SIGNATURES);
        final double nodeExtrasFee = sigExtrasNode * SIGNATURE_FEE_USD;
        final double nodeFee = NODE_BASE_FEE_USD + nodeExtrasFee + nodeFeeFromBytesUsd(txnSize);

        // ----- network fees -----
        final double networkFee = nodeFee * NETWORK_MULTIPLIER;

        // ----- service fees -----
        final long keyExtrasService = Math.max(0L, keys - CONS_UPDATE_TOPIC_INCLUDED_KEYS);
        final double serviceExtrasFee = keyExtrasService * KEYS_FEE_USD;
        final double serviceFee = CONS_UPDATE_TOPIC_BASE_FEE_USD + serviceExtrasFee;

        return nodeFee + networkFee + serviceFee;
    }

    /**
     * Overload when extras are provided in a map.
     */
    public static double expectedTopicUpdateFullFeeUsd(final Map<Extra, Long> extras) {
        return expectedTopicUpdateFullFeeUsd(
                extras.getOrDefault(Extra.SIGNATURES, 0L),
                extras.getOrDefault(Extra.KEYS, 0L),
                Math.toIntExact(extras.getOrDefault(Extra.PROCESSING_BYTES, 0L)));
    }

    /**
     * Overload when there are no key extras (no key change).
     */
    public static double expectedTopicUpdateFullFeeUsd(long sigs) {
        return expectedTopicUpdateFullFeeUsd(sigs, 0L);
    }

    /**
     * Simple fees calculation for ConsensusUpdateTopic with node fee only
     */
    public static double expectedTopicUpdateNetworkFeeOnlyUsd(long sigs) {
        // ----- node fees -----
        final long sigExtrasNode = Math.max(0L, sigs - NODE_INCLUDED_SIGNATURES);
        final double nodeExtrasFee = sigExtrasNode * SIGNATURE_FEE_USD;
        final double nodeFee = NODE_BASE_FEE_USD + nodeExtrasFee;

        // ----- network fees -----
        return nodeFee * NETWORK_MULTIPLIER;
    }

    // -------- ConsensusDeleteTopic simple fees utils ---------//

    /**
     * SimpleFees formula for ConsensusDeleteTopic:
     * node    = NODE_BASE + SIGNATURE_FEE * max(0, sigs - includedSigsNode)
     * network = node * NETWORK_MULTIPLIER
     * service = CONS_DELETE_TOPIC_BASE (no extras)
     * total   = node + network + service
     */
    public static double expectedTopicDeleteFullFeeUsd(long sigs) {
        // ----- node fees -----
        final long sigExtrasNode = Math.max(0L, sigs - NODE_INCLUDED_SIGNATURES);
        final double nodeExtrasFee = sigExtrasNode * SIGNATURE_FEE_USD;
        final double nodeFee = NODE_BASE_FEE_USD + nodeExtrasFee;

        // ----- network fees -----
        final double networkFee = nodeFee * NETWORK_MULTIPLIER;

        // ----- service fees -----
        final double serviceFee = CONS_DELETE_TOPIC_BASE_FEE_USD;

        return nodeFee + networkFee + serviceFee;
    }

    public static double expectedTopicDeleteFullFeeUsd(long sigs, int txnSize) {
        return addNodeAndNetworkBytes(expectedTopicDeleteFullFeeUsd(sigs), txnSize);
    }

    /**
     * Overload when extras are provided in a map.
     */
    public static double expectedTopicDeleteFullFeeUsd(final Map<Extra, Long> extras) {
        return expectedTopicDeleteFullFeeUsd(
                extras.getOrDefault(Extra.SIGNATURES, 0L),
                Math.toIntExact(extras.getOrDefault(Extra.PROCESSING_BYTES, 0L)));
    }

    /**
     * Simple fees calculation for ConsensusDeleteTopic with node fee only
     */
    public static double expectedTopicDeleteNetworkFeeOnlyUsd(long sigs) {
        // ----- node fees -----
        final long sigExtrasNode = Math.max(0L, sigs - NODE_INCLUDED_SIGNATURES);
        final double nodeExtrasFee = sigExtrasNode * SIGNATURE_FEE_USD;
        final double nodeFee = NODE_BASE_FEE_USD + nodeExtrasFee;

        // ----- network fees -----
        return nodeFee * NETWORK_MULTIPLIER;
    }

    // -------- ConsensusSubmitMessage simple fees utils ---------//

    /**
     * SimpleFees formula for ConsensusSubmitMessage:
     * node    = NODE_BASE + SIGNATURE_FEE * max(0, sigs - includedSigsNode)
     * network = node * NETWORK_MULTIPLIER
     * service = CONS_SUBMIT_MESSAGE_BASE
     *         + BYTES_FEE * max(0, bytes - includedBytesService)
     *         + (if includesCustomFee) CONS_SUBMIT_MESSAGE_WITH_CUSTOM_FEE
     * total   = node + network + service
     */
    public static double expectedTopicSubmitMessageFullFeeUsd(
            long sigs, long messageBytes, boolean includesCustomFee, int txnSize) {
        // ----- node fees -----
        final long sigExtrasNode = Math.max(0L, sigs - NODE_INCLUDED_SIGNATURES);
        final double nodeExtrasFee = sigExtrasNode * SIGNATURE_FEE_USD;
        final double nodeFee = NODE_BASE_FEE_USD + nodeExtrasFee + nodeFeeFromBytesUsd(txnSize);

        // ----- network fees -----
        final double networkFee = nodeFee * NETWORK_MULTIPLIER;

        // ----- service fees -----
        final long byteExtrasService = Math.max(0L, messageBytes - CONS_SUBMIT_MESSAGE_INCLUDED_BYTES);
        final double serviceBytesExtrasFee = byteExtrasService * STATE_BYTES_FEE_USD;

        double serviceBaseFee = CONS_SUBMIT_MESSAGE_BASE_FEE_USD;
        if (includesCustomFee) {
            serviceBaseFee += CONS_SUBMIT_MESSAGE_WITH_CUSTOM_FEE_USD;
        }
        final double serviceFee = serviceBaseFee + serviceBytesExtrasFee;

        return nodeFee + networkFee + serviceFee;
    }

    public static double expectedTopicSubmitMessageFullFeeUsd(long sigs, long messageBytes, int txnSize) {
        return expectedTopicSubmitMessageFullFeeUsd(sigs, messageBytes, false, txnSize);
    }

    /**
     * Overload when extras are provided in a map.
     */
    public static double expectedTopicSubmitMessageFullFeeUsd(final Map<Extra, Long> extras) {
        return expectedTopicSubmitMessageFullFeeUsd(
                extras.getOrDefault(Extra.SIGNATURES, 0L),
                extras.getOrDefault(Extra.STATE_BYTES, 0L),
                Math.toIntExact(extras.getOrDefault(Extra.PROCESSING_BYTES, 0L)));
    }

    /**
     * Overload for ConsensusSubmitMessage with custom fee.
     */
    public static double expectedTopicSubmitMessageWithCustomFeeFullFeeUsd(long sigs, long messageBytes, int txnSize) {
        return expectedTopicSubmitMessageFullFeeUsd(sigs, messageBytes, true, txnSize);
    }

    /**
     * Overload when extras are provided in a map.
     */
    public static double expectedTopicSubmitMessageWithCustomFeeFullFeeUsd(final Map<Extra, Long> extras) {
        return expectedTopicSubmitMessageWithCustomFeeFullFeeUsd(
                extras.getOrDefault(Extra.SIGNATURES, 0L),
                extras.getOrDefault(Extra.STATE_BYTES, 0L),
                Math.toIntExact(extras.getOrDefault(Extra.PROCESSING_BYTES, 0L)));
    }

    /**
     * Simple fees calculation for ConsensusSubmitMessage with node fee only
     */
    public static double expectedTopicSubmitMessageNetworkFeeOnlyUsd(long sigs) {
        // ----- node fees -----
        final long sigExtrasNode = Math.max(0L, sigs - NODE_INCLUDED_SIGNATURES);
        final double nodeExtrasFee = sigExtrasNode * SIGNATURE_FEE_USD;
        final double nodeFee = NODE_BASE_FEE_USD + nodeExtrasFee;

        // ----- network fees -----
        return nodeFee * NETWORK_MULTIPLIER;
    }

    // -------- TokenCreate simple fees utils ---------//

    /**
     * SimpleFees formula for TokenCreate (fungible or NFT without custom fees):
     * node    = NODE_BASE + SIGNATURE_FEE * max(0, sigs - includedSigsNode)
     * network = node * NETWORK_MULTIPLIER
     * service = TOKEN_CREATE_BASE + KEYS_FEE * max(0, keys - includedKeys)
     * total   = node + network + service
     */
    private static double expectedTokenCreateFungibleFullFeeUsd(long sigs, long keys, int txnSize) {
        // ----- node fees -----
        final long sigExtrasNode = Math.max(0L, sigs - NODE_INCLUDED_SIGNATURES);
        final double nodeExtrasFee = sigExtrasNode * SIGNATURE_FEE_USD;
        final double nodeFee = NODE_BASE_FEE_USD + nodeExtrasFee + nodeFeeFromBytesUsd(txnSize);
        // ----- network fees -----
        final double networkFee = nodeFee * NETWORK_MULTIPLIER;
        // ----- service fees -----
        final long keyExtrasService = Math.max(0L, keys - TOKEN_CREATE_INCLUDED_KEYS);
        final double serviceExtrasFee = keyExtrasService * KEYS_FEE_USD;
        final double serviceFee = TOKEN_CREATE_BASE_FEE_USD + serviceExtrasFee;
        return nodeFee + networkFee + serviceFee;
    }

    /**
     * SimpleFees formula for TokenCreate (NFT without custom fees):
     * Same as fungible - the base fee is the same for both token types.
     */
    public static double expectedTokenCreateNftFullFeeUsd(long sigs, long keys) {
        return expectedTokenCreateFungibleFullFeeUsd(sigs, keys, 0);
    }

    public static double expectedTokenCreateFungibleFullFeeUsd(long sigs, long keys) {
        return expectedTokenCreateFungibleFullFeeUsd(sigs, keys, 0);
    }

    /**
     * Overload when extras are provided in a map.
     */
    public static double expectedTokenCreateFullFeeUsd(final Map<Extra, Long> extras) {
        return expectedTokenCreateFungibleFullFeeUsd(
                extras.getOrDefault(Extra.SIGNATURES, 0L),
                extras.getOrDefault(Extra.KEYS, 0L),
                Math.toIntExact(extras.getOrDefault(Extra.PROCESSING_BYTES, 0L)));
    }

    /**
     * SimpleFees formula for TokenCreate (fungible with custom fees):
     * node    = NODE_BASE + SIGNATURE_FEE * max(0, sigs - includedSigsNode)
     * network = node * NETWORK_MULTIPLIER
     * service = TOKEN_CREATE_BASE + KEYS_FEE * extras + TOKEN_CREATE_WITH_CUSTOM_FEE
     * total   = node + network + service
     */
    private static double expectedTokenCreateFungibleWithCustomFeesFullFeeUsd(long sigs, long keys, int txnSize) {
        // ----- node fees -----
        final long sigExtrasNode = Math.max(0L, sigs - NODE_INCLUDED_SIGNATURES);
        final double nodeExtrasFee = sigExtrasNode * SIGNATURE_FEE_USD;
        final double nodeFee = NODE_BASE_FEE_USD + nodeExtrasFee + nodeFeeFromBytesUsd(txnSize);

        // ----- network fees -----
        final double networkFee = nodeFee * NETWORK_MULTIPLIER;

        // ----- service fees -----
        final long keyExtrasService = Math.max(0L, keys - TOKEN_CREATE_INCLUDED_KEYS);
        final double serviceExtrasFee = keyExtrasService * KEYS_FEE_USD + TOKEN_CREATE_WITH_CUSTOM_FEE_USD;
        final double serviceFee = TOKEN_CREATE_BASE_FEE_USD + serviceExtrasFee;

        return nodeFee + networkFee + serviceFee;
    }

    /**
     * SimpleFees formula for TokenCreate (NFT with custom fees):
     * Same as fungible with custom fees.
     */
    public static double expectedTokenCreateNftWithCustomFeesFullFeeUsd(long sigs, long keys) {
        return expectedTokenCreateFungibleWithCustomFeesFullFeeUsd(sigs, keys, 0);
    }

    public static double expectedTokenCreateFungibleWithCustomFeesFullFeeUsd(long sigs, long keys) {
        return expectedTokenCreateFungibleWithCustomFeesFullFeeUsd(sigs, keys, 0);
    }

    /**
     * Overload when extras are provided in a map.
     */
    public static double expectedTokenCreateWithCustomFullFeeUsd(final Map<Extra, Long> extras) {
        return expectedTokenCreateFungibleWithCustomFeesFullFeeUsd(
                extras.getOrDefault(Extra.SIGNATURES, 0L),
                extras.getOrDefault(Extra.KEYS, 0L),
                Math.toIntExact(extras.getOrDefault(Extra.PROCESSING_BYTES, 0L)));
    }

    /**
     * Network-only fee for TokenCreate failures in pre-handle.
     */
    public static double expectedTokenCreateNetworkFeeOnlyUsd(long sigs) {
        // ----- node fees -----
        final long sigExtrasNode = Math.max(0L, sigs - NODE_INCLUDED_SIGNATURES);
        final double nodeExtrasFee = sigExtrasNode * SIGNATURE_FEE_USD;
        final double nodeFee = NODE_BASE_FEE_USD + nodeExtrasFee;

        // ----- network fees -----
        return nodeFee * NETWORK_MULTIPLIER;
    }

    // -------- TokenUpdate simple fees utils ---------//

    /**
     * SimpleFees formula for TokenUpdate:
     * node    = NODE_BASE + SIGNATURE_FEE * max(0, sigs - includedSigsNode)
     * network = node * NETWORK_MULTIPLIER
     * service = TOKEN_UPDATE_BASE + KEYS_FEE * max(0, keys - includedKeys)
     * total   = node + network + service
     */
    public static double expectedTokenUpdateFullFeeUsd(long sigs, long keys, int txnSize) {
        // ----- node fees -----
        final long sigExtrasNode = Math.max(0L, sigs - NODE_INCLUDED_SIGNATURES);
        final double nodeExtrasFee = sigExtrasNode * SIGNATURE_FEE_USD;
        final double nodeFee = NODE_BASE_FEE_USD + nodeExtrasFee + nodeFeeFromBytesUsd(txnSize);

        // ----- network fees -----
        final double networkFee = nodeFee * NETWORK_MULTIPLIER;

        // ----- service fees -----
        final long keyExtrasService = Math.max(0L, keys - TOKEN_UPDATE_INCLUDED_KEYS);
        final double serviceExtrasFee = keyExtrasService * KEYS_FEE_USD;
        final double serviceFee = TOKEN_UPDATE_BASE_FEE_USD + serviceExtrasFee;

        return nodeFee + networkFee + serviceFee;
    }

    /**
     * Overload for TokenUpdate with no extra keys.
     */
    public static double expectedTokenUpdateFullFeeUsd(long sigs) {
        return expectedTokenUpdateFullFeeUsd(sigs, 0L, 0);
    }

    /**
     * Overload for TokenUpdate with no extra keys and no bytes.
     */
    public static double expectedTokenUpdateFullFeeUsd(long sigs, long keys) {
        return expectedTokenUpdateFullFeeUsd(sigs, keys, 0);
    }

    /**
     * Overload when extras are provided in a map.
     */
    public static double expectedTokenUpdateFullFeeUsd(final Map<Extra, Long> extras) {
        return expectedTokenUpdateFullFeeUsd(
                extras.getOrDefault(Extra.SIGNATURES, 0L),
                extras.getOrDefault(Extra.KEYS, 0L),
                Math.toIntExact(extras.getOrDefault(Extra.PROCESSING_BYTES, 0L)));
    }

    /**
     * SimpleFees formula for TokenUpdate with NFT updates:
     * node    = NODE_BASE + SIGNATURE_FEE * max(0, sigs - includedSigsNode)
     * network = node * NETWORK_MULTIPLIER
     * service = TOKEN_UPDATE_BASE + TOKEN_UPDATE_NFT_FEE * max(0, nftSerials - includedNftSerials)
     * total   = node + network + service
     */
    public static double expectedTokenNftUpdateFullFeeUsd(long sigs, long nftSerials, int txnSize) {
        // ----- node fees -----
        final long sigExtrasNode = Math.max(0L, sigs - NODE_INCLUDED_SIGNATURES);
        final double nodeExtrasFee = sigExtrasNode * SIGNATURE_FEE_USD;
        final double nodeFee = NODE_BASE_FEE_USD + nodeExtrasFee + nodeFeeFromBytesUsd(txnSize);

        // ----- network fees -----
        final double networkFee = nodeFee * NETWORK_MULTIPLIER;

        // ----- service fees -----
        final long nftExtrasService = Math.max(0L, nftSerials - TOKEN_UPDATE_INCLUDED_NFT_COUNT);
        final double serviceExtrasFee = nftExtrasService * TOKEN_UPDATE_NFT_FEE;
        final double serviceFee = TOKEN_UPDATE_BASE_FEE_USD + serviceExtrasFee;

        return nodeFee + networkFee + serviceFee;
    }

    /**
     * Overload for TokenUpdate with NFT updates when extras are provided in a map.
     */
    public static double expectedTokenNftUpdateFullFeeUsd(final Map<Extra, Long> extras) {
        return expectedTokenNftUpdateFullFeeUsd(
                extras.getOrDefault(Extra.SIGNATURES, 0L),
                extras.getOrDefault(Extra.TOKEN_TYPES, 0L),
                Math.toIntExact(extras.getOrDefault(Extra.PROCESSING_BYTES, 0L)));
    }

    /**
     * Network-only fee for TokenUpdate failures in pre-handle.
     */
    public static double expectedTokenUpdateNetworkFeeOnlyUsd(long sigs) {
        // ----- node fees -----
        final long sigExtrasNode = Math.max(0L, sigs - NODE_INCLUDED_SIGNATURES);
        final double nodeExtrasFee = sigExtrasNode * SIGNATURE_FEE_USD;
        final double nodeFee = NODE_BASE_FEE_USD + nodeExtrasFee;

        // ----- network fees -----
        return nodeFee * NETWORK_MULTIPLIER;
    }

    // -------- TokenDelete simple fees utils ---------//
    /**
     * SimpleFees formula for TokenDelete:
     * node    = NODE_BASE + SIGNATURE_FEE * max(0, sigs - includedSigsNode)
     * network = node * NETWORK_MULTIPLIER
     * service = TOKEN_DELETE_BASE
     * total   = node + network + service
     */
    private static double expectedTokenDeleteFullFeeUsd(long sigs, int txnSize) {
        // ----- node fees -----
        final long sigExtrasNode = Math.max(0L, sigs - NODE_INCLUDED_SIGNATURES);
        final double nodeExtrasFee = sigExtrasNode * SIGNATURE_FEE_USD;
        final double nodeFee = NODE_BASE_FEE_USD + nodeExtrasFee + nodeFeeFromBytesUsd(txnSize);

        // ----- network fees -----
        final double networkFee = nodeFee * NETWORK_MULTIPLIER;

        // ----- service fees -----
        final double serviceFee = TOKEN_DELETE_BASE_FEE_USD;

        return nodeFee + networkFee + serviceFee;
    }

    /**
     * Overload for TokenDelete with no bytes.
     */
    public static double expectedTokenDeleteFullFeeUsd(long sigs) {
        return expectedTokenDeleteFullFeeUsd(sigs, 0);
    }

    /**
     * Overload when extras are provided in a map.
     */
    public static double expectedTokenDeleteFullFeeUsd(final Map<Extra, Long> extras) {
        return expectedTokenDeleteFullFeeUsd(
                extras.getOrDefault(Extra.SIGNATURES, 0L),
                Math.toIntExact(extras.getOrDefault(Extra.PROCESSING_BYTES, 0L)));
    }

    /**
     * Network-only fee for TokenDelete failures in pre-handle.
     */
    public static double expectedTokenDeleteNetworkFeeOnlyUsd(long sigs) {
        // ----- node fees -----
        final long sigExtrasNode = Math.max(0L, sigs - NODE_INCLUDED_SIGNATURES);
        final double nodeExtrasFee = sigExtrasNode * SIGNATURE_FEE_USD;
        final double nodeFee = NODE_BASE_FEE_USD + nodeExtrasFee;

        // ----- network fees -----
        return nodeFee * NETWORK_MULTIPLIER;
    }

    // -------- TokenMint simple fees utils ---------//

    /**
     * SimpleFees formula for TokenMint (fungible):
     * node    = NODE_BASE + SIGNATURE_FEE * max(0, sigs - includedSigsNode)
     * network = node * NETWORK_MULTIPLIER
     * service = TOKEN_MINT_BASE
     * total   = node + network + service
     */
    private static double expectedTokenMintFungibleFullFeeUsd(long sigs, int txnSize) {
        // ----- node fees -----
        final long sigExtrasNode = Math.max(0L, sigs - NODE_INCLUDED_SIGNATURES);
        final double nodeExtrasFee = sigExtrasNode * SIGNATURE_FEE_USD;
        final double nodeFee = NODE_BASE_FEE_USD + nodeExtrasFee + nodeFeeFromBytesUsd(txnSize);

        // ----- network fees -----
        final double networkFee = nodeFee * NETWORK_MULTIPLIER;

        // ----- service fees -----
        final double serviceFee = TOKEN_MINT_BASE_FEE_USD;

        return nodeFee + networkFee + serviceFee;
    }

    /**
     * Overload for TokenMint (fungible) with no bytes.
     */
    public static double expectedTokenMintFungibleFullFeeUsd(long sigs) {
        return expectedTokenMintFungibleFullFeeUsd(sigs, 0);
    }

    /**
     * Overload when extras are provided in a map.
     */
    public static double expectedTokenMintFungibleFullFeeUsd(final Map<Extra, Long> extras) {
        return expectedTokenMintFungibleFullFeeUsd(
                extras.getOrDefault(Extra.SIGNATURES, 0L),
                Math.toIntExact(extras.getOrDefault(Extra.PROCESSING_BYTES, 0L)));
    }

    /**
     * SimpleFees formula for TokenMint (NFT):
     * node    = NODE_BASE + SIGNATURE_FEE * max(0, sigs - includedSigsNode)
     * network = node * NETWORK_MULTIPLIER
     * service = TOKEN_MINT_BASE + TOKEN_MINT_NFT_FEE * max(0, nftSerials - includedNft)
     * total   = node + network + service
     */
    public static double expectedTokenMintNftFullFeeUsd(long sigs, long nftSerials, int txnSize) {
        // ----- node fees -----
        final long sigExtrasNode = Math.max(0L, sigs - NODE_INCLUDED_SIGNATURES);
        final double nodeExtrasFee = sigExtrasNode * SIGNATURE_FEE_USD;
        final double nodeFee = NODE_BASE_FEE_USD + nodeExtrasFee + nodeFeeFromBytesUsd(txnSize);

        // ----- network fees -----
        final double networkFee = nodeFee * NETWORK_MULTIPLIER;

        // ----- service fees -----
        final long serialExtrasService = Math.max(0L, nftSerials - TOKEN_MINT_INCLUDED_NFT);
        final double serviceExtrasFee = serialExtrasService * TOKEN_MINT_NFT_FEE_USD;
        final double serviceFee = TOKEN_MINT_BASE_FEE_USD + TOKEN_MINT_NFT_BASE_FEE_USD + serviceExtrasFee;

        return nodeFee + networkFee + serviceFee;
    }

    /**
     * Overload for TokenMint (NFT) when extras are provided in a map.
     */
    public static double expectedTokenMintNftFullFeeUsd(final Map<Extra, Long> extras) {
        return expectedTokenMintNftFullFeeUsd(
                extras.getOrDefault(Extra.SIGNATURES, 0L),
                extras.getOrDefault(Extra.TOKEN_TYPES, 0L),
                Math.toIntExact(extras.getOrDefault(Extra.PROCESSING_BYTES, 0L)));
    }

    /**
     * Network-only fee for TokenMint failures in pre-handle.
     */
    public static double expectedTokenMintNetworkFeeOnlyUsd(long sigs) {
        // ----- node fees -----
        final long sigExtrasNode = Math.max(0L, sigs - NODE_INCLUDED_SIGNATURES);
        final double nodeExtrasFee = sigExtrasNode * SIGNATURE_FEE_USD;
        final double nodeFee = NODE_BASE_FEE_USD + nodeExtrasFee;

        // ----- network fees -----
        return nodeFee * NETWORK_MULTIPLIER;
    }

    // -------- TokenBurn simple fees utils ---------//

    /**
     * SimpleFees formula for TokenBurn (fungible):
     * node    = NODE_BASE + SIGNATURE_FEE * max(0, sigs - includedSigsNode)
     * network = node * NETWORK_MULTIPLIER
     * service = TOKEN_BURN_BASE
     * total   = node + network + service
     */
    private static double expectedTokenBurnFungibleFullFeeUsd(long sigs, int txnSize) {
        // ----- node fees -----
        final long sigExtrasNode = Math.max(0L, sigs - NODE_INCLUDED_SIGNATURES);
        final double nodeExtrasFee = sigExtrasNode * SIGNATURE_FEE_USD;
        final double nodeFee = NODE_BASE_FEE_USD + nodeExtrasFee + nodeFeeFromBytesUsd(txnSize);

        // ----- network fees -----
        final double networkFee = nodeFee * NETWORK_MULTIPLIER;

        // ----- service fees -----
        final double serviceFee = TOKEN_BURN_BASE_FEE_USD;

        return nodeFee + networkFee + serviceFee;
    }

    public static double expectedTokenBurnFungibleFullFeeUsd(long sigs) {
        return expectedTokenBurnFungibleFullFeeUsd(sigs, 0);
    }

    /**
     * Overload when extras are provided in a map.
     */
    public static double expectedTokenBurnFungibleFullFeeUsd(final Map<Extra, Long> extras) {
        return expectedTokenBurnFungibleFullFeeUsd(
                extras.getOrDefault(Extra.SIGNATURES, 0L),
                Math.toIntExact(extras.getOrDefault(Extra.PROCESSING_BYTES, 0L)));
    }

    /**
     * SimpleFees formula for TokenBurn (NFT):
     * node    = NODE_BASE + SIGNATURE_FEE * max(0, sigs - includedSigsNode)
     * network = node * NETWORK_MULTIPLIER
     * service = TOKEN_BURN_BASE (no extras for NFT serials in burn)
     * total   = node + network + service
     */
    public static double expectedTokenBurnNftFullFeeUsd(long sigs, long nftSerials) {
        return expectedTokenBurnFungibleFullFeeUsd(sigs);
    }

    /**
     * Network-only fee for TokenBurn failures in pre-handle.
     */
    public static double expectedTokenBurnNetworkFeeOnlyUsd(long sigs) {
        // ----- node fees -----
        final long sigExtrasNode = Math.max(0L, sigs - NODE_INCLUDED_SIGNATURES);
        final double nodeExtrasFee = sigExtrasNode * SIGNATURE_FEE_USD;
        final double nodeFee = NODE_BASE_FEE_USD + nodeExtrasFee;

        // ----- network fees -----
        return nodeFee * NETWORK_MULTIPLIER;
    }

    // -------- TokenAssociate simple fees utils ---------//

    /**
     * SimpleFees formula for TokenAssociate:
     * node    = NODE_BASE + SIGNATURE_FEE * max(0, sigs - includedSigsNode)
     * network = node * NETWORK_MULTIPLIER
     * service = TOKEN_ASSOCIATE_BASE * tokens count
     * total   = node + network + service
     */
    private static double expectedTokenAssociateFullFeeUsd(long sigs, long tokens, int txnSize) {
        // ----- node fees -----
        final long sigExtrasNode = Math.max(0L, sigs - NODE_INCLUDED_SIGNATURES);
        final double nodeExtrasFee = sigExtrasNode * SIGNATURE_FEE_USD;
        final double nodeFee = NODE_BASE_FEE_USD + nodeExtrasFee + nodeFeeFromBytesUsd(txnSize);
        // ----- network fees -----
        final double networkFee = nodeFee * NETWORK_MULTIPLIER;
        // ----- service fees -----
        final long extraTokens = Math.max(0L, tokens - TOKEN_ASSOCIATE_INCLUDED_TOKENS);
        final double serviceFee = TOKEN_ASSOCIATE_BASE_FEE_USD + extraTokens * TOKEN_ASSOCIATE_EXTRA_FEE_USD;
        return nodeFee + networkFee + serviceFee;
    }

    /**
     * Overload for TokenAssociate with no bytes.
     */
    public static double expectedTokenAssociateFullFeeUsd(long sigs, long tokens) {
        return expectedTokenAssociateFullFeeUsd(sigs, tokens, 0);
    }

    /**
     * Overload when extras are provided in a map.
     */
    public static double expectedTokenAssociateFullFeeUsd(final Map<Extra, Long> extras) {
        return expectedTokenAssociateFullFeeUsd(
                extras.getOrDefault(Extra.SIGNATURES, 0L),
                extras.getOrDefault(Extra.TOKEN_ASSOCIATE, 0L),
                Math.toIntExact(extras.getOrDefault(Extra.PROCESSING_BYTES, 0L)));
    }

    /**
     * Overload for single token association.
     */
    public static double expectedTokenAssociateFullFeeUsd(long sigs) {
        return expectedTokenAssociateFullFeeUsd(sigs, 1L);
    }

    /**
     * Network-only fee for TokenAssociate failures in pre-handle.
     */
    public static double expectedTokenAssociateNetworkFeeOnlyUsd(long sigs) {
        // ----- node fees -----
        final long sigExtrasNode = Math.max(0L, sigs - NODE_INCLUDED_SIGNATURES);
        final double nodeExtrasFee = sigExtrasNode * SIGNATURE_FEE_USD;
        final double nodeFee = NODE_BASE_FEE_USD + nodeExtrasFee;

        // ----- network fees -----
        return nodeFee * NETWORK_MULTIPLIER;
    }

    // -------- TokenDissociate simple fees utils ---------//

    /**
     * SimpleFees formula for TokenDissociate:
     * node    = NODE_BASE + SIGNATURE_FEE * max(0, sigs - includedSigsNode)
     * network = node * NETWORK_MULTIPLIER
     * service = TOKEN_DISSOCIATE_BASE
     * total   = node + network + service
     */
    private static double expectedTokenDissociateFullFeeUsd(long sigs, long tokens, int txnSize) {
        // ----- node fees -----
        final long sigExtrasNode = Math.max(0L, sigs - NODE_INCLUDED_SIGNATURES);
        final double nodeExtrasFee = sigExtrasNode * SIGNATURE_FEE_USD;
        final double nodeFee = NODE_BASE_FEE_USD + nodeExtrasFee + nodeFeeFromBytesUsd(txnSize);

        // ----- network fees -----
        final double networkFee = nodeFee * NETWORK_MULTIPLIER;

        // ----- service fees -----
        final double serviceFee = TOKEN_DISSOCIATE_BASE_FEE_USD;

        return nodeFee + networkFee + serviceFee;
    }

    /**
     * Overload for single token dissociation.
     */
    public static double expectedTokenDissociateFullFeeUsd(long sigs) {
        return expectedTokenDissociateFullFeeUsd(sigs, 1L);
    }

    /**
     * Overload for TokenDissociate with no bytes.
     */
    public static double expectedTokenDissociateFullFeeUsd(long sigs, long tokens) {
        return expectedTokenDissociateFullFeeUsd(sigs, tokens, 0);
    }

    /**
     * Overload when extras are provided in a map.
     */
    public static double expectedTokenDissociateFullFeeUsd(final Map<Extra, Long> extras) {
        return expectedTokenDissociateFullFeeUsd(
                extras.getOrDefault(Extra.SIGNATURES, 0L),
                Math.toIntExact(extras.getOrDefault(Extra.PROCESSING_BYTES, 0L)));
    }

    /**
     * Network-only fee for TokenDissociate failures in pre-handle.
     */
    public static double expectedTokenDissociateNetworkFeeOnlyUsd(long sigs) {
        final long sigExtrasNode = Math.max(0L, sigs - NODE_INCLUDED_SIGNATURES);
        final double nodeExtrasFee = sigExtrasNode * SIGNATURE_FEE_USD;
        final double nodeFee = NODE_BASE_FEE_USD + nodeExtrasFee;
        return nodeFee * NETWORK_MULTIPLIER;
    }

    // -------- TokenGrantKyc simple fees utils ---------//

    /**
     * SimpleFees formula for TokenGrantKyc:
     * node    = NODE_BASE + SIGNATURE_FEE * max(0, sigs - includedSigsNode)
     * network = node * NETWORK_MULTIPLIER
     * service = TOKEN_GRANT_KYC_BASE
     * total   = node + network + service
     */
    private static double expectedTokenGrantKycFullFeeUsd(long sigs, int txnSize) {
        // ----- node fees -----
        final long sigExtrasNode = Math.max(0L, sigs - NODE_INCLUDED_SIGNATURES);
        final double nodeExtrasFee = sigExtrasNode * SIGNATURE_FEE_USD;
        final double nodeFee = NODE_BASE_FEE_USD + nodeExtrasFee + nodeFeeFromBytesUsd(txnSize);

        // ----- network fees -----
        final double networkFee = nodeFee * NETWORK_MULTIPLIER;

        // ----- service fees -----
        final double serviceFee = TOKEN_GRANT_KYC_BASE_FEE_USD;

        return nodeFee + networkFee + serviceFee;
    }

    /**
     * Overload for TokenGrantKyc with no bytes.
     */
    public static double expectedTokenGrantKycFullFeeUsd(long sigs) {
        return expectedTokenGrantKycFullFeeUsd(sigs, 0);
    }

    /**
     * Overload when extras are provided in a map.
     */
    public static double expectedTokenGrantKycFullFeeUsd(final Map<Extra, Long> extras) {
        return expectedTokenGrantKycFullFeeUsd(
                extras.getOrDefault(Extra.SIGNATURES, 0L),
                Math.toIntExact(extras.getOrDefault(Extra.PROCESSING_BYTES, 0L)));
    }

    /**
     * Network-only fee for TokenGrantKyc failures in pre-handle.
     */
    public static double expectedTokenGrantKycNetworkFeeOnlyUsd(long sigs) {
        // ----- node fees -----
        final long sigExtrasNode = Math.max(0L, sigs - NODE_INCLUDED_SIGNATURES);
        final double nodeExtrasFee = sigExtrasNode * SIGNATURE_FEE_USD;
        final double nodeFee = NODE_BASE_FEE_USD + nodeExtrasFee;

        // ----- network fees -----
        return nodeFee * NETWORK_MULTIPLIER;
    }

    // -------- TokenRevokeKyc simple fees utils ---------//

    /**
     * SimpleFees formula for TokenRevokeKyc:
     * node    = NODE_BASE + SIGNATURE_FEE * max(0, sigs - includedSigsNode)
     * network = node * NETWORK_MULTIPLIER
     * service = TOKEN_REVOKE_KYC_BASE
     * total   = node + network + service
     */
    private static double expectedTokenRevokeKycFullFeeUsd(long sigs, int txnSize) {
        // ----- node fees -----
        final long sigExtrasNode = Math.max(0L, sigs - NODE_INCLUDED_SIGNATURES);
        final double nodeExtrasFee = sigExtrasNode * SIGNATURE_FEE_USD;
        final double nodeFee = NODE_BASE_FEE_USD + nodeExtrasFee + nodeFeeFromBytesUsd(txnSize);

        // ----- network fees -----
        final double networkFee = nodeFee * NETWORK_MULTIPLIER;

        // ----- service fees -----
        final double serviceFee = TOKEN_REVOKE_KYC_BASE_FEE_USD;

        return nodeFee + networkFee + serviceFee;
    }

    /**
     * Overload for TokenRevokeKyc with no bytes.
     */
    public static double expectedTokenRevokeKycFullFeeUsd(long sigs) {
        return expectedTokenRevokeKycFullFeeUsd(sigs, 0);
    }

    /**
     * Overload when extras are provided in a map.
     */
    public static double expectedTokenRevokeKycFullFeeUsd(final Map<Extra, Long> extras) {
        return expectedTokenRevokeKycFullFeeUsd(
                extras.getOrDefault(Extra.SIGNATURES, 0L),
                Math.toIntExact(extras.getOrDefault(Extra.PROCESSING_BYTES, 0L)));
    }

    /**
     * Network-only fee for TokenRevokeKyc failures in pre-handle.
     */
    public static double expectedTokenRevokeKycNetworkFeeOnlyUsd(long sigs) {
        // ----- node fees -----
        final long sigExtrasNode = Math.max(0L, sigs - NODE_INCLUDED_SIGNATURES);
        final double nodeExtrasFee = sigExtrasNode * SIGNATURE_FEE_USD;
        final double nodeFee = NODE_BASE_FEE_USD + nodeExtrasFee;

        // ----- network fees -----
        return nodeFee * NETWORK_MULTIPLIER;
    }

    // -------- TokenFreeze simple fees utils ---------//

    /**
     * SimpleFees formula for TokenFreeze:
     * node    = NODE_BASE + SIGNATURE_FEE * max(0, sigs - includedSigsNode)
     * network = node * NETWORK_MULTIPLIER
     * service = TOKEN_FREEZE_BASE
     * total   = node + network + service
     */
    private static double expectedTokenFreezeFullFeeUsd(long sigs, int txnSize) {
        // ----- node fees -----
        final long sigExtrasNode = Math.max(0L, sigs - NODE_INCLUDED_SIGNATURES);
        final double nodeExtrasFee = sigExtrasNode * SIGNATURE_FEE_USD;
        final double nodeFee = NODE_BASE_FEE_USD + nodeExtrasFee + nodeFeeFromBytesUsd(txnSize);

        // ----- network fees -----
        final double networkFee = nodeFee * NETWORK_MULTIPLIER;

        // ----- service fees -----
        final double serviceFee = TOKEN_FREEZE_BASE_FEE_USD;

        return nodeFee + networkFee + serviceFee;
    }

    /**
     * Overload for TokenFreeze with no bytes.
     */
    public static double expectedTokenFreezeFullFeeUsd(long sigs) {
        return expectedTokenFreezeFullFeeUsd(sigs, 0);
    }

    /**
     * Overload when extras are provided in a map.
     */
    public static double expectedTokenFreezeFullFeeUsd(final Map<Extra, Long> extras) {
        return expectedTokenFreezeFullFeeUsd(
                extras.getOrDefault(Extra.SIGNATURES, 0L),
                Math.toIntExact(extras.getOrDefault(Extra.PROCESSING_BYTES, 0L)));
    }

    /**
     * Network-only fee for TokenFreeze failures in pre-handle.
     */
    public static double expectedTokenFreezeNetworkFeeOnlyUsd(long sigs) {
        final long sigExtrasNode = Math.max(0L, sigs - NODE_INCLUDED_SIGNATURES);
        final double nodeExtrasFee = sigExtrasNode * SIGNATURE_FEE_USD;
        final double nodeFee = NODE_BASE_FEE_USD + nodeExtrasFee;
        return nodeFee * NETWORK_MULTIPLIER;
    }

    // -------- TokenUnfreeze simple fees utils ---------//

    /**
     * SimpleFees formula for TokenUnfreeze:
     * node    = NODE_BASE + SIGNATURE_FEE * max(0, sigs - includedSigsNode)
     * network = node * NETWORK_MULTIPLIER
     * service = TOKEN_UNFREEZE_BASE
     * total   = node + network + service
     */
    private static double expectedTokenUnfreezeFullFeeUsd(long sigs, int txnSize) {
        // ----- node fees -----
        final long sigExtrasNode = Math.max(0L, sigs - NODE_INCLUDED_SIGNATURES);
        final double nodeExtrasFee = sigExtrasNode * SIGNATURE_FEE_USD;
        final double nodeFee = NODE_BASE_FEE_USD + nodeExtrasFee + nodeFeeFromBytesUsd(txnSize);

        // ----- network fees -----
        final double networkFee = nodeFee * NETWORK_MULTIPLIER;

        // ----- service fees -----
        final double serviceFee = TOKEN_UNFREEZE_BASE_FEE_USD;

        return nodeFee + networkFee + serviceFee;
    }

    /**
     * Overload for TokenUnfreeze with no bytes.
     */
    public static double expectedTokenUnfreezeFullFeeUsd(long sigs) {
        return expectedTokenUnfreezeFullFeeUsd(sigs, 0);
    }

    /**
     * Overload when extras are provided in a map.
     */
    public static double expectedTokenUnfreezeFullFeeUsd(final Map<Extra, Long> extras) {
        return expectedTokenUnfreezeFullFeeUsd(
                extras.getOrDefault(Extra.SIGNATURES, 0L),
                Math.toIntExact(extras.getOrDefault(Extra.PROCESSING_BYTES, 0L)));
    }

    /**
     * Network-only fee for TokenUnfreeze failures in pre-handle.
     */
    public static double expectedTokenUnfreezeNetworkFeeOnlyUsd(long sigs) {
        final long sigExtrasNode = Math.max(0L, sigs - NODE_INCLUDED_SIGNATURES);
        final double nodeExtrasFee = sigExtrasNode * SIGNATURE_FEE_USD;
        final double nodeFee = NODE_BASE_FEE_USD + nodeExtrasFee;
        return nodeFee * NETWORK_MULTIPLIER;
    }

    // -------- TokenUnpause simple fees utils ---------//

    /**
     * SimpleFees formula for TokenPause:
     * node    = NODE_BASE + SIGNATURE_FEE * max(0, sigs - includedSigsNode)
     * network = node * NETWORK_MULTIPLIER
     * service = TOKEN_PAUSE_BASE
     * total   = node + network + service
     */
    private static double expectedTokenPauseFullFeeUsd(long sigs, int txnSize) {
        // ----- node fees -----
        final long sigExtrasNode = Math.max(0L, sigs - NODE_INCLUDED_SIGNATURES);
        final double nodeExtrasFee = sigExtrasNode * SIGNATURE_FEE_USD;
        final double nodeFee = NODE_BASE_FEE_USD + nodeExtrasFee + nodeFeeFromBytesUsd(txnSize);

        // ----- network fees -----
        final double networkFee = nodeFee * NETWORK_MULTIPLIER;

        // ----- service fees -----
        final double serviceFee = TOKEN_PAUSE_BASE_FEE_USD;

        return nodeFee + networkFee + serviceFee;
    }

    /**
     * Overload for TokenPause with no bytes.
     */
    public static double expectedTokenPauseFullFeeUsd(long sigs) {
        return expectedTokenPauseFullFeeUsd(sigs, 0);
    }

    /**
     * Overload when extras are provided in a map.
     */
    public static double expectedTokenPauseFullFeeUsd(final Map<Extra, Long> extras) {
        return expectedTokenPauseFullFeeUsd(
                extras.getOrDefault(Extra.SIGNATURES, 0L),
                Math.toIntExact(extras.getOrDefault(Extra.PROCESSING_BYTES, 0L)));
    }

    /**
     * Network-only fee for TokenPause failures in pre-handle.
     */
    public static double expectedTokenPauseNetworkFeeOnlyUsd(long sigs) {
        final long sigExtrasNode = Math.max(0L, sigs - NODE_INCLUDED_SIGNATURES);
        final double nodeExtrasFee = sigExtrasNode * SIGNATURE_FEE_USD;
        final double nodeFee = NODE_BASE_FEE_USD + nodeExtrasFee;
        return nodeFee * NETWORK_MULTIPLIER;
    }

    // -------- TokenUnpause simple fees utils ---------//

    /**
     * SimpleFees formula for TokenUnpause:
     * node    = NODE_BASE + SIGNATURE_FEE * max(0, sigs - includedSigsNode)
     * network = node * NETWORK_MULTIPLIER
     * service = TOKEN_UNPAUSE_BASE
     * total   = node + network + service
     */
    private static double expectedTokenUnpauseFullFeeUsd(long sigs, int txnSize) {
        // ----- node fees -----
        final long sigExtrasNode = Math.max(0L, sigs - NODE_INCLUDED_SIGNATURES);
        final double nodeExtrasFee = sigExtrasNode * SIGNATURE_FEE_USD;
        final double nodeFee = NODE_BASE_FEE_USD + nodeExtrasFee + nodeFeeFromBytesUsd(txnSize);

        // ----- network fees -----
        final double networkFee = nodeFee * NETWORK_MULTIPLIER;

        // ----- service fees -----
        final double serviceFee = TOKEN_UNPAUSE_BASE_FEE_USD;

        return nodeFee + networkFee + serviceFee;
    }

    /**
     * Overload for TokenUnpause with no bytes.
     */
    public static double expectedTokenUnpauseFullFeeUsd(long sigs) {
        return expectedTokenUnpauseFullFeeUsd(sigs, 0);
    }

    /**
     * Overload when extras are provided in a map.
     */
    public static double expectedTokenUnpauseFullFeeUsd(final Map<Extra, Long> extras) {
        return expectedTokenUnpauseFullFeeUsd(
                extras.getOrDefault(Extra.SIGNATURES, 0L),
                Math.toIntExact(extras.getOrDefault(Extra.PROCESSING_BYTES, 0L)));
    }

    /**
     * Network-only fee for TokenUnpause failures in pre-handle.
     */
    public static double expectedTokenUnpauseNetworkFeeOnlyUsd(long sigs) {
        final long sigExtrasNode = Math.max(0L, sigs - NODE_INCLUDED_SIGNATURES);
        final double nodeExtrasFee = sigExtrasNode * SIGNATURE_FEE_USD;
        final double nodeFee = NODE_BASE_FEE_USD + nodeExtrasFee;
        return nodeFee * NETWORK_MULTIPLIER;
    }

    // -------- TokenWipe simple fees utils ---------//

    /**
     * SimpleFees formula for TokenWipe (fungible):
     * node    = NODE_BASE + SIGNATURE_FEE * max(0, sigs - includedSigsNode)
     * network = node * NETWORK_MULTIPLIER
     * service = TOKEN_WIPE_BASE
     * total   = node + network + service
     */
    public static double expectedTokenWipeFungibleFullFeeUsd(long sigs) {
        // ----- node fees -----
        final long sigExtrasNode = Math.max(0L, sigs - NODE_INCLUDED_SIGNATURES);
        final double nodeExtrasFee = sigExtrasNode * SIGNATURE_FEE_USD;
        final double nodeFee = NODE_BASE_FEE_USD + nodeExtrasFee;

        // ----- network fees -----
        final double networkFee = nodeFee * NETWORK_MULTIPLIER;

        // ----- service fees -----
        final double serviceFee = TOKEN_WIPE_BASE_FEE_USD;

        return nodeFee + networkFee + serviceFee;
    }

    /**
     * Network-only fee for TokenWipe failures in pre-handle.
     */
    public static double expectedTokenWipeNetworkFeeOnlyUsd(long sigs) {
        final long sigExtrasNode = Math.max(0L, sigs - NODE_INCLUDED_SIGNATURES);
        final double nodeExtrasFee = sigExtrasNode * SIGNATURE_FEE_USD;
        final double nodeFee = NODE_BASE_FEE_USD + nodeExtrasFee;
        return nodeFee * NETWORK_MULTIPLIER;
    }

    // -------- AtomicBatch simple fees utils ---------//

    /**
     * SimpleFees formula for AtomicBatch:
     * node    = NODE_BASE + SIGNATURE_FEE * max(0, sigs - includedSigsNode + nodeFeeFromBytesUsd(txnSize))
     * network = node * NETWORK_MULTIPLIER
     * service = ATOMIC_BATCH_BASE
     * total   = node + network + service
     */
    private static double expectedAtomicBatchFullFeeUsd(long sigs, int txnSize) {
        // ----- node fees -----
        final long sigExtrasNode = Math.max(0L, sigs - NODE_INCLUDED_SIGNATURES);
        final double nodeExtrasFee = sigExtrasNode * SIGNATURE_FEE_USD;
        final double nodeFee = NODE_BASE_FEE_USD + nodeExtrasFee + nodeFeeFromBytesUsd(txnSize);

        // ----- network fees -----
        final double networkFee = nodeFee * NETWORK_MULTIPLIER;

        // ----- service fees -----
        final double serviceFee = ATOMIC_BATCH_BASE_FEE_USD;

        return nodeFee + networkFee + serviceFee;
    }

    /**
     * Overload for AtomicBatch with no bytes.
     */
    public static double expectedAtomicBatchFullFeeUsd(final Map<Extra, Long> extras) {
        return expectedAtomicBatchFullFeeUsd(
                extras.getOrDefault(Extra.SIGNATURES, 0L),
                Math.toIntExact(extras.getOrDefault(Extra.PROCESSING_BYTES, 0L)));
    }

    // -------- CryptoApproveAllowance simple fees utils ---------//
    /**
     * SimpleFees formula for CryptoApproveAllowance:
     * node    = NODE_BASE + SIGNATURE_FEE * max(0, sigs - includedSigsNode + nodeFeeFromBytesUsd(txnSize))
     * network = node * NETWORK_MULTIPLIER
     * service = CRYPTO_APPROVE_ALLOWANCE_BASE + CRYPTO_APPROVE_ALLOWANCE_EXTRA * max(0, allowances - includedAllowances)
     * total   = node + network + service
     */
    public static double expectedCryptoApproveAllowanceFullFeeUsd(long sigs, long allowances, int txnSize) {
        // ----- node fees -----
        final long sigExtrasNode = Math.max(0L, sigs - NODE_INCLUDED_SIGNATURES);
        final double nodeExtrasFee = sigExtrasNode * SIGNATURE_FEE_USD;
        final double nodeFee = NODE_BASE_FEE_USD + nodeExtrasFee + nodeFeeFromBytesUsd(txnSize);

        // ----- network fees -----
        final double networkFee = nodeFee * NETWORK_MULTIPLIER;

        // ----- service fees -----
        final long allowanceExtras = Math.max(0L, allowances - CRYPTO_APPROVE_ALLOWANCE_INCLUDED_COUNT);
        final double serviceFee =
                CRYPTO_APPROVE_ALLOWANCE_BASE_FEE_USD + allowanceExtras * CRYPTO_APPROVE_ALLOWANCE_EXTRA_FEE_USD;

        return nodeFee + networkFee + serviceFee;
    }

    /**
     * Overload when extras are provided in a map.
     */
    public static double expectedCryptoApproveAllowanceFullFeeUsd(final Map<Extra, Long> extras) {
        return expectedCryptoApproveAllowanceFullFeeUsd(
                extras.getOrDefault(Extra.SIGNATURES, 0L),
                extras.getOrDefault(Extra.ALLOWANCES, 0L),
                Math.toIntExact(extras.getOrDefault(Extra.PROCESSING_BYTES, 0L)));
    }

    // -------- CryptoDeleteAllowance simple fees utils ---------//

    /**
     * SimpleFees formula for CryptoDeleteAllowance:
     * node    = NODE_BASE + SIGNATURE_FEE * max(0, sigs - includedSigsNode + nodeFeeFromBytesUsd(txnSize))
     * network = node * NETWORK_MULTIPLIER
     * service = CRYPTO_DELETE_ALLOWANCE_BASE + CRYPTO_DELETE_ALLOWANCE_EXTRA * max(0, allowances - includedAllowances)
     * total   = node + network + service
     */
    public static double expectedCryptoDeleteAllowanceFullFeeUsd(long sigs, long allowances, int txnSize) {
        // ----- node fees -----
        final long sigExtrasNode = Math.max(0L, sigs - NODE_INCLUDED_SIGNATURES);
        final double nodeExtrasFee = sigExtrasNode * SIGNATURE_FEE_USD;
        final double nodeFee = NODE_BASE_FEE_USD + nodeExtrasFee + nodeFeeFromBytesUsd(txnSize);

        // ----- network fees -----
        final double networkFee = nodeFee * NETWORK_MULTIPLIER;

        // ----- service fees -----
        final long allowanceExtras = Math.max(0L, allowances - CRYPTO_DELETE_ALLOWANCE_INCLUDED_COUNT);
        final double serviceFee =
                CRYPTO_DELETE_ALLOWANCE_BASE_FEE_USD + allowanceExtras * CRYPTO_DELETE_ALLOWANCE_EXTRA_FEE_USD;

        return nodeFee + networkFee + serviceFee;
    }

    /**
     * Overload when extras are provided in a map.
     */
    public static double expectedCryptoDeleteAllowanceFullFeeUsd(final Map<Extra, Long> extras) {
        return expectedCryptoDeleteAllowanceFullFeeUsd(
                extras.getOrDefault(Extra.SIGNATURES, 0L),
                extras.getOrDefault(Extra.ALLOWANCES, 0L),
                Math.toIntExact(extras.getOrDefault(Extra.PROCESSING_BYTES, 0L)));
    }

    // -------- FileCreate simple fees utils ---------//
    /**
     * SimpleFees formula for FileCreate:
     * node    = NODE_BASE + SIGNATURE_FEE * max(0, sigs - includedSigsNode + nodeFeeFromBytesUsd(txnSize))
     * network = node * NETWORK_MULTIPLIER
     * service = FILE_CREATE_BASE + STATE_BYTES_FEE * max(0, messageBytes - includedBytesFileCreate) + KEYS_FEE * max(0, keys - includedKeysFileCreate)
     * total   = node + network + service
     */
    public static double expectedFileCreateFullFeeUsd(long sigs, long keys, long messageBytes, int txnSize) {
        // ----- node fees -----
        final long sigExtrasNode = Math.max(0L, sigs - NODE_INCLUDED_SIGNATURES);
        final double nodeExtrasFee = sigExtrasNode * SIGNATURE_FEE_USD;
        final double nodeFee = NODE_BASE_FEE_USD + nodeExtrasFee + nodeFeeFromBytesUsd(txnSize);

        // ----- network fees -----
        final double networkFee = nodeFee * NETWORK_MULTIPLIER;

        // ----- service fees -----
        final long byteExtrasService = Math.max(0L, messageBytes - FILE_CREATE_INCLUDED_BYTES);
        final double serviceBytesExtrasFee = byteExtrasService * STATE_BYTES_FEE_USD;

        final long keysExtrasService = Math.max(0L, keys - FILE_CREATE_INCLUDED_KEYS);
        final double serviceKeysExtrasFee = keysExtrasService * KEYS_FEE_USD;

        final double serviceFee = FILE_CREATE_BASE_FEE_USD + serviceBytesExtrasFee + serviceKeysExtrasFee;

        return nodeFee + networkFee + serviceFee;
    }

    /**
     * Overload when extras are provided in a map.
     */
    public static double expectedFileCreateFullFeeUsd(final Map<Extra, Long> extras) {
        return expectedFileCreateFullFeeUsd(
                extras.getOrDefault(Extra.SIGNATURES, 0L),
                extras.getOrDefault(Extra.KEYS, 0L),
                extras.getOrDefault(Extra.STATE_BYTES, 0L),
                Math.toIntExact(extras.getOrDefault(Extra.PROCESSING_BYTES, 0L)));
    }

    // -------- FileDelete simple fees utils ---------//

    /**
     * SimpleFees formula for FileDelete:
     * node    = NODE_BASE + SIGNATURE_FEE * max(0, sigs - includedSigsNode + nodeFeeFromBytesUsd(txnSize))
     * network = node * NETWORK_MULTIPLIER
     * service = FILE_DELETE_BASE
     * total   = node + network + service
     */
    private static double expectedFileDeleteFullFeeUsd(long sigs, int txnSize) {
        // ----- node fees -----
        final long sigExtrasNode = Math.max(0L, sigs - NODE_INCLUDED_SIGNATURES);
        final double nodeExtrasFee = sigExtrasNode * SIGNATURE_FEE_USD;
        final double nodeFee = NODE_BASE_FEE_USD + nodeExtrasFee + nodeFeeFromBytesUsd(txnSize);

        // ----- network fees -----
        final double networkFee = nodeFee * NETWORK_MULTIPLIER;

        return nodeFee + networkFee + FILE_DELETE_BASE_FEE_USD;
    }

    /**
     * Overload for FileDelete with no bytes.
     */
    public static double expectedFileDeleteFullFeeUsd(final Map<Extra, Long> extras) {
        return expectedFileDeleteFullFeeUsd(
                extras.getOrDefault(Extra.SIGNATURES, 0L),
                Math.toIntExact(extras.getOrDefault(Extra.PROCESSING_BYTES, 0L)));
    }

    // -------- FileAppend simple fees utils ---------//

    /**
     * SimpleFees formula for FileAppend:
     * node    = NODE_BASE + SIGNATURE_FEE * max(0, sigs - includedSigsNode + nodeFeeFromBytesUsd(txnSize))
     * network = node * NETWORK_MULTIPLIER
     * service = FILE_APPEND_BASE + STATE_BYTES_FEE * max(0, messageBytes - includedBytesFileAppend)
     * total   = node + network + service
     */
    private static double expectedFileAppendFullFeeUsd(long sigs, long messageBytes, int txnSize) {
        // ----- node fees -----
        final long sigExtrasNode = Math.max(0L, sigs - NODE_INCLUDED_SIGNATURES);
        final double nodeExtrasFee = sigExtrasNode * SIGNATURE_FEE_USD;
        final double nodeFee = NODE_BASE_FEE_USD + nodeExtrasFee + nodeFeeFromBytesUsd(txnSize);

        // ----- network fees -----
        final double networkFee = nodeFee * NETWORK_MULTIPLIER;

        // ----- service fees -----
        final long byteExtrasService = Math.max(0L, messageBytes - FILE_APPEND_INCLUDED_BYTES);
        final double serviceBytesExtrasFee = byteExtrasService * STATE_BYTES_FEE_USD;

        final double serviceFee = FILE_APPEND_BASE_FEE_USD + serviceBytesExtrasFee;

        return nodeFee + networkFee + serviceFee;
    }

    /**
     * Overload for FileAppend with no bytes.
     */
    public static double expectedFileAppendFullFeeUsd(final Map<Extra, Long> extras) {
        return expectedFileAppendFullFeeUsd(
                extras.getOrDefault(Extra.SIGNATURES, 0L),
                extras.getOrDefault(Extra.STATE_BYTES, 0L),
                Math.toIntExact(extras.getOrDefault(Extra.PROCESSING_BYTES, 0L)));
    }

    // -------- Prng simple fees utils ---------//

    /**
     * SimpleFees formula for Prng:
     * node    = NODE_BASE + SIGNATURE_FEE * max(0, sigs - includedSigsNode + nodeFeeFromBytesUsd(txnSize))
     * network = node * NETWORK_MULTIPLIER
     * service = UTIL_PRNG_BASE
     * total   = node + network + service
     */
    private static double expectedPrngFullFeeUsd(long sigs, int txnSize) {
        // ----- node fees -----
        final long sigExtrasNode = Math.max(0L, sigs - NODE_INCLUDED_SIGNATURES);
        final double nodeExtrasFee = sigExtrasNode * SIGNATURE_FEE_USD;
        final double nodeFee = NODE_BASE_FEE_USD + nodeExtrasFee + nodeFeeFromBytesUsd(txnSize);

        // ----- network fees -----
        final double networkFee = nodeFee * NETWORK_MULTIPLIER;

        return nodeFee + networkFee + UTIL_PRNG_BASE_FEE_USD;
    }

    /**
     * Overload for Prng with no bytes.
     */
    public static double expectedPrngFullFeeUsd(final Map<Extra, Long> extras) {
        return expectedPrngFullFeeUsd(
                extras.getOrDefault(Extra.SIGNATURES, 0L),
                Math.toIntExact(extras.getOrDefault(Extra.PROCESSING_BYTES, 0L)));
    }

    // -------- TokenClaimAirdrop simple fees utils ---------//

    /**
     * SimpleFees formula for TokenClaimAirdrop:
     * node    = NODE_BASE + SIGNATURE_FEE * max(0, sigs - includedSigsNode + nodeFeeFromBytesUsd(txnSize))
     * network = node * NETWORK_MULTIPLIER
     * service = AIRDROP_CLAIM_FEE
     * total   = node + network + service
     */
    private static double expectedTokenClaimAirdropFullFeeUsd(long sigs, int txnSize) {
        // ----- node fees -----
        final long sigExtrasNode = Math.max(0L, sigs - NODE_INCLUDED_SIGNATURES);
        final double nodeExtrasFee = sigExtrasNode * SIGNATURE_FEE_USD;
        final double nodeFee = NODE_BASE_FEE_USD + nodeExtrasFee + nodeFeeFromBytesUsd(txnSize);

        // ----- network fees -----
        final double networkFee = nodeFee * NETWORK_MULTIPLIER;

        return nodeFee + networkFee + AIRDROP_CLAIM_FEE_USD;
    }

    /**
     * Overload for TokenClaimAirdrop with no bytes.
     */
    public static double expectedTokenClaimAirdropFullFeeUsd(final Map<Extra, Long> extras) {
        return expectedTokenClaimAirdropFullFeeUsd(
                extras.getOrDefault(Extra.SIGNATURES, 0L),
                Math.toIntExact(extras.getOrDefault(Extra.PROCESSING_BYTES, 0L)));
    }

    // -------- TokenCancelAirdrop simple fees utils ---------//
    /**
     * SimpleFees formula for TokenCancelAirdrop:
     * node    = NODE_BASE + SIGNATURE_FEE * max(0, sigs - includedSigsNode + nodeFeeFromBytesUsd(txnSize))
     * network = node * NETWORK_MULTIPLIER
     * service = AIRDROP_CANCEL_FEE
     * total   = node + network + service
     */
    private static double expectedTokenCancelAirdropFullFeeUsd(long sigs, int txnSize) {
        // ----- node fees -----
        final long sigExtrasNode = Math.max(0L, sigs - NODE_INCLUDED_SIGNATURES);
        final double nodeExtrasFee = sigExtrasNode * SIGNATURE_FEE_USD;
        final double nodeFee = NODE_BASE_FEE_USD + nodeExtrasFee + nodeFeeFromBytesUsd(txnSize);

        // ----- network fees -----
        final double networkFee = nodeFee * NETWORK_MULTIPLIER;

        return nodeFee + networkFee + AIRDROP_CANCEL_FEE_USD;
    }

    /**
     * Overload for TokenCancelAirdrop with no bytes.
     */
    public static double expectedTokenCancelAirdropFullFeeUsd(final Map<Extra, Long> extras) {
        return expectedTokenCancelAirdropFullFeeUsd(
                extras.getOrDefault(Extra.SIGNATURES, 0L),
                Math.toIntExact(extras.getOrDefault(Extra.PROCESSING_BYTES, 0L)));
    }

    // -------- TokenReject simple fees utils ---------//
    /**
     * SimpleFees formula for TokenReject:
     * node    = NODE_BASE + SIGNATURE_FEE * max(0, sigs - includedSigsNode + nodeFeeFromBytesUsd(txnSize))
     * network = node * NETWORK_MULTIPLIER
     * service = TOKEN_REJECT_BASE
     * total   = node + network + service
     */
    private static double expectedTokenRejectFullFeeUsd(long sigs, int txnSize) {
        // ----- node fees -----
        final long sigExtrasNode = Math.max(0L, sigs - NODE_INCLUDED_SIGNATURES);
        final double nodeExtrasFee = sigExtrasNode * SIGNATURE_FEE_USD;
        final double nodeFee = NODE_BASE_FEE_USD + nodeExtrasFee + nodeFeeFromBytesUsd(txnSize);

        // ----- network fees -----
        final double networkFee = nodeFee * NETWORK_MULTIPLIER;

        return nodeFee + networkFee + TOKEN_REJECT_FEE_USD;
    }

    /**
     * Overload for TokenReject with no bytes.
     */
    public static double expectedTokenRejectFullFeeUsd(final Map<Extra, Long> extras) {
        return expectedTokenRejectFullFeeUsd(
                extras.getOrDefault(Extra.SIGNATURES, 0L),
                Math.toIntExact(extras.getOrDefault(Extra.PROCESSING_BYTES, 0L)));
    }

    // -------- TokenFeeScheduleUpdate simple fees utils ---------//

    /**
     * SimpleFees formula for TokenFeeScheduleUpdate:
     * node    = NODE_BASE + SIGNATURE_FEE * max(0, sigs - includedSigsNode + nodeFeeFromBytesUsd(txnSize))
     * network = node * NETWORK_MULTIPLIER
     * service = TOKEN_FEE_SCHEDULE_UPDATE_BASE
     * total   = node + network + service
     */
    private static double expectedTokenFeeScheduleUpdateFullFeeUsd(long sigs, int txnSize) {
        // ----- node fees -----
        final long sigExtrasNode = Math.max(0L, sigs - NODE_INCLUDED_SIGNATURES);
        final double nodeExtrasFee = sigExtrasNode * SIGNATURE_FEE_USD;
        final double nodeFee = NODE_BASE_FEE_USD + nodeExtrasFee + nodeFeeFromBytesUsd(txnSize);

        // ----- network fees -----
        final double networkFee = nodeFee * NETWORK_MULTIPLIER;

        return nodeFee + networkFee + TOKEN_FEE_SCHEDULE_UPDATE_FEE_USD;
    }

    /**
     * Overload for TokenFeeScheduleUpdate with no bytes.
     */
    public static double expectedTokenFeeScheduleUpdateFullFeeUsd(final Map<Extra, Long> extras) {
        return expectedTokenFeeScheduleUpdateFullFeeUsd(
                extras.getOrDefault(Extra.SIGNATURES, 0L),
                Math.toIntExact(extras.getOrDefault(Extra.PROCESSING_BYTES, 0L)));
    }

    /*
     * Dual-mode fee validation that branches on {@code fees.simpleFeesEnabled} at runtime.
     * When simple fees are enabled, validates against {@code simpleFee};
     * otherwise validates against {@code legacyFee}.
     */
    public static SpecOperation validateFees(final String txn, final double legacyFee, final double simpleFee) {
        return doWithStartupConfig("fees.simpleFeesEnabled", flag -> {
            if ("true".equals(flag)) {
                return validateChargedUsdWithin(txn, simpleFee, 0.1);
            } else {
                return validateChargedUsdWithin(txn, legacyFee, 1);
            }
        });
    }

    public static SpecOperation validateInnerTxnFees(String txn, String parent, double legacyFee, double simpleFee) {
        return validateInnerTxnFees(txn, parent, legacyFee, simpleFee, 0.1);
    }

    /**
     * Dual-mode fee validation for inner atomic batch transactions that branches on {@code fees.simpleFeesEnabled} at runtime.
     * When simple fees are enabled, validates against {@code simpleFee};
     * otherwise validates against {@code legacyFee}.
     * @param allowedDiff the allowed percent difference.
     */
    public static SpecOperation validateInnerTxnFees(
            String txn, String parent, double legacyFee, double simpleFee, double allowedDiff) {
        return doWithStartupConfig("fees.simpleFeesEnabled", flag -> {
            if ("true".equals(flag)) {
                return validateInnerTxnChargedUsd(txn, parent, simpleFee, allowedDiff);
            } else {
                return validateInnerTxnChargedUsd(txn, parent, legacyFee, allowedDiff);
            }
        });
    }

    /**
     * Dual-mode fee validation with child records that branches on {@code fees.simpleFeesEnabled} at runtime.
     * When simple fees are enabled, validates against {@code simpleFee};
     * otherwise validates against {@code legacyFee}.
     * Uses {@code validateChargedUsdWithChild} which includes child dispatch fees.
     */
    public static SpecOperation validateFeesWithChild(
            final String txn, final double legacyFee, final double simpleFee, final double tolerance) {
        return doWithStartupConfig("fees.simpleFeesEnabled", flag -> {
            if ("true".equals(flag)) {
                return validateChargedUsdWithChild(txn, simpleFee, tolerance);
            } else {
                return validateChargedUsdWithChild(txn, legacyFee, tolerance);
            }
        });
    }

    /** SimpleFees formula for Atomic Batch:
     * node = NODE_BASE + bytes over 1024
     * network = node * NETWORK_MULTIPLIER
     * service = BATCH_BASE_FEE
     * total   = node + network + service
     */
    public static double expectedBatchFullFeeUsd(long extraBytes) {
        return BATCH_BASE_FEE + extraBytes * PROCESSING_BYTES_FEE_USD * 10;
    }

    public static CustomSpecAssert validateBatchChargedCorrectly(String batchTxn) {
        return withOpContext((spec, log) ->
                validateChargedUsd(batchTxn, BATCH_BASE_FEE + expectedFeeFromBytesFor(spec, log, batchTxn)));
    }

    // --------- Utils for dual-mode validation ---------//

    /** Enum for legacy fee parameters to improve readability when passing parameters in a map. */
    public enum LegacyFeeParam {
        LEGACY_EXPECTED_USD,
        LEGACY_ALLOWED_PERCENT_DIFF
    }

    /** Functional interface for computing expected USD from extras in dual-mode validation. */
    @FunctionalInterface
    public interface ExpectedUsdFromExtras {
        double compute(Map<Extra, Long> extras);
    }

    public static SpecOperation validateFeeModeAwareWithTxnSize(
            final String txnId,
            final Map<Extra, Long> simpleFeesExtras,
            final Map<LegacyFeeParam, Double> legacyParams,
            final double simpleFeesAllowedPercentDiff,
            final ExpectedUsdFromExtras expectedUsdSimpleFees) {
        return doWithStartupConfig("fees.simpleFeesEnabled", flag -> {
            if ("true".equals(flag)) {
                return validateChargedUsdWithinWithTxnSize(
                        txnId,
                        txnSize -> {
                            final var extrasWithProcessingBytes = new HashMap<>(simpleFeesExtras);
                            extrasWithProcessingBytes.put(Extra.PROCESSING_BYTES, (long) txnSize);
                            return expectedUsdSimpleFees.compute(extrasWithProcessingBytes);
                        },
                        simpleFeesAllowedPercentDiff);
            } else {
                final double expectedUsd = legacyParams.getOrDefault(LegacyFeeParam.LEGACY_EXPECTED_USD, 0.0);
                final double allowedPercentDiff =
                        legacyParams.getOrDefault(LegacyFeeParam.LEGACY_ALLOWED_PERCENT_DIFF, 1.0);
                return validateChargedUsdWithin(txnId, expectedUsd, allowedPercentDiff);
            }
        });
    }
}
