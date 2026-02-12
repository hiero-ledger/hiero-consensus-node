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
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntToDoubleFunction;
import org.apache.logging.log4j.Logger;

public class FeesChargingUtils {
    private static final int NODE_INCLUDED_BYTES = 1024;

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

    /**
     * SimpleFees formula for CryptoCreate:
     * node    = NODE_BASE + SIGNATURE_FEE * max(0, sigs - includedSigsNode)
     * network = node * NETWORK_MULTIPLIER
     * service = CRYPTO_CREATE_BASE
     *         + KEYS_FEE  * max(0, keys - includedKeysService)
     *         + HOOKS_FEE * max(0, hooks - includedHooksService)
     * total   = node + network + service
     */
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

    public static double expectedCryptoCreateFullFeeUsd(final Map<FeeParam, Object> extras) {

        return expectedCryptoCreateFullFeeUsd(
                longValue(extras, FeeParam.SIGNATURES, 0),
                longValue(extras, FeeParam.KEYS, 0),
                longValue(extras, FeeParam.HOOKS_EXECUTED, 0),
                intValue(extras, FeeParam.TXN_SIZE, 0));
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

    public static double expectedCryptoCreateNetworkFeeOnlyUsd(long sigs, int txnSize) {
        // ----- node fees -----
        final long sigExtrasNode = Math.max(0L, sigs - NODE_INCLUDED_SIGNATURES);
        final double nodeExtrasFee = sigExtrasNode * SIGNATURE_FEE_USD;
        final double nodeFee = NODE_BASE_FEE_USD + nodeExtrasFee + nodeFeeFromBytesUsd(txnSize);

        // ----- network fees -----
        return nodeFee * NETWORK_MULTIPLIER;
    }

    public static double expectedCryptoDeleteFullFeeUsd(long sigs, int txnSize) {
        // ----- node fees -----
        final long sigExtrasNode = Math.max(0L, sigs - NODE_INCLUDED_SIGNATURES);
        final double nodeExtrasFee = sigExtrasNode * SIGNATURE_FEE_USD;
        final double nodeFee = NODE_BASE_FEE_USD + nodeExtrasFee + nodeFeeFromBytesUsd(txnSize);

        // ----- network fees -----
        final double networkFee = nodeFee * NETWORK_MULTIPLIER;

        return nodeFee + networkFee + CRYPTO_DELETE_BASE_FEE_USD;
    }

    public static double expectedCryptoDeleteFullFeeUsd(final Map<FeeParam, Object> extras) {

        return expectedCryptoDeleteFullFeeUsd(
                longValue(extras, FeeParam.SIGNATURES, 0), intValue(extras, FeeParam.TXN_SIZE, 0));
    }

    public static double expectedCryptoUpdateFullFeeUsd(long sigs, int keys, int hooks, int txnSize) {
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

    public static double expectedCryptoUpdateFullFeeUsd(final Map<FeeParam, Object> extras) {

        return expectedCryptoUpdateFullFeeUsd(
                longValue(extras, FeeParam.SIGNATURES, 0),
                intValue(extras, FeeParam.KEYS, 0),
                intValue(extras, FeeParam.HOOKS_EXECUTED, 0),
                intValue(extras, FeeParam.TXN_SIZE, 0));
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

    public static double expectedTopicCreateFullFeeUsd(final Map<FeeParam, Object> extras) {
        return expectedTopicCreateFullFeeUsd(
                longValue(extras, FeeParam.SIGNATURES, 0),
                longValue(extras, FeeParam.KEYS, 0),
                intValue(extras, FeeParam.TXN_SIZE, 0));
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

    // Enum for extras parameters in CryptoTransfer
    public enum FeeParam {
        SIGNATURES,
        KEYS,
        TOKENS,
        HOOKS_EXECUTED,
        HOOKS_UPDATES,
        ACCOUNTS,
        FUNGIBLE_TOKENS,
        NON_FUNGIBLE_TOKENS,
        TOKEN_TYPES,
        TOKEN_AIRDROPS,
        GAS,
        BYTES,
        ALLOWANCES,
        TXN_SIZE
    }

    // Helper method to get long value from map with default
    private static long longValue(final Map<FeeParam, Object> m, final FeeParam k, final long defaultValue) {
        final var v = m.get(k);
        return v == null ? defaultValue : ((Number) v).longValue();
    }

    // Helper method to get int value from map with default
    private static int intValue(final Map<FeeParam, Object> m, final FeeParam k, final int defaultValue) {
        final var v = m.get(k);
        return v == null ? defaultValue : ((Number) v).intValue();
    }

    private static double extra(long actual, long included, double feePerUnit) {
        final long extras = Math.max(0L, actual - included);
        return extras * feePerUnit;
    }

    public static double expectedCryptoTransferFullFeeUsd(
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

    public static double expectedCryptoTransferHbarFullFeeUsd(
            long sigs, long uniqueHooksExecuted, long uniqueAccounts, long tokenTypes, long gasAmount) {

        return expectedCryptoTransferFullFeeUsd(
                sigs, uniqueHooksExecuted, uniqueAccounts, tokenTypes, gasAmount, true, false, false);
    }

    public static double expectedCryptoTransferHbarFullFeeUsd(
            long sigs, long uniqueHooksExecuted, long uniqueAccounts, long tokenTypes, long gasAmount, int txnSize) {
        return addNodeAndNetworkBytes(
                expectedCryptoTransferHbarFullFeeUsd(sigs, uniqueHooksExecuted, uniqueAccounts, tokenTypes, gasAmount),
                txnSize);
    }

    public static double expectedCryptoTransferHbarFullFeeUsd(final Map<FeeParam, Object> extras) {

        return expectedCryptoTransferHbarFullFeeUsd(
                longValue(extras, FeeParam.SIGNATURES, 0),
                longValue(extras, FeeParam.HOOKS_EXECUTED, 0),
                longValue(extras, FeeParam.ACCOUNTS, 0),
                longValue(extras, FeeParam.TOKEN_TYPES, 0),
                longValue(extras, FeeParam.GAS, 0),
                intValue(extras, FeeParam.TXN_SIZE, 0));
    }

    public static double expectedCryptoTransferFTFullFeeUsd(
            long sigs, long uniqueHooksExecuted, long uniqueAccounts, long tokenTypes, long gasAmount) {

        return expectedCryptoTransferFullFeeUsd(
                sigs, uniqueHooksExecuted, uniqueAccounts, tokenTypes, gasAmount, false, true, false);
    }

    public static double expectedCryptoTransferFTFullFeeUsd(
            long sigs, long uniqueHooksExecuted, long uniqueAccounts, long tokenTypes, long gasAmount, int txnSize) {
        return addNodeAndNetworkBytes(
                expectedCryptoTransferFTFullFeeUsd(sigs, uniqueHooksExecuted, uniqueAccounts, tokenTypes, gasAmount),
                txnSize);
    }

    public static double expectedCryptoTransferFTFullFeeUsd(final Map<FeeParam, Object> extras) {

        return expectedCryptoTransferFTFullFeeUsd(
                longValue(extras, FeeParam.SIGNATURES, 0),
                longValue(extras, FeeParam.HOOKS_EXECUTED, 0),
                longValue(extras, FeeParam.ACCOUNTS, 0),
                longValue(extras, FeeParam.TOKEN_TYPES, 0),
                longValue(extras, FeeParam.GAS, 0),
                intValue(extras, FeeParam.TXN_SIZE, 0));
    }

    public static double expectedCryptoTransferNFTFullFeeUsd(
            long sigs, long uniqueHooksExecuted, long uniqueAccounts, long tokenTypes, long gasAmount) {

        return expectedCryptoTransferFullFeeUsd(
                sigs, uniqueHooksExecuted, uniqueAccounts, tokenTypes, gasAmount, false, true, false);
    }

    public static double expectedCryptoTransferNFTFullFeeUsd(
            long sigs, long uniqueHooksExecuted, long uniqueAccounts, long tokenTypes, long gasAmount, int txnSize) {
        return addNodeAndNetworkBytes(
                expectedCryptoTransferNFTFullFeeUsd(sigs, uniqueHooksExecuted, uniqueAccounts, tokenTypes, gasAmount),
                txnSize);
    }

    public static double expectedCryptoTransferNFTFullFeeUsd(final Map<FeeParam, Object> extras) {

        return expectedCryptoTransferNFTFullFeeUsd(
                longValue(extras, FeeParam.SIGNATURES, 0),
                longValue(extras, FeeParam.HOOKS_EXECUTED, 0),
                longValue(extras, FeeParam.ACCOUNTS, 0),
                longValue(extras, FeeParam.TOKEN_TYPES, 0),
                longValue(extras, FeeParam.GAS, 0),
                intValue(extras, FeeParam.TXN_SIZE, 0));
    }

    public static double expectedCryptoTransferFTAndNFTFullFeeUsd(
            long sigs, long uniqueHooksExecuted, long uniqueAccounts, long tokenTypes, long gasAmount) {

        return expectedCryptoTransferFullFeeUsd(
                sigs, uniqueHooksExecuted, uniqueAccounts, tokenTypes, gasAmount, false, true, false);
    }

    public static double expectedCryptoTransferFTAndNFTFullFeeUsd(
            long sigs, long uniqueHooksExecuted, long uniqueAccounts, long tokenTypes, long gasAmount, int txnSize) {
        return addNodeAndNetworkBytes(
                expectedCryptoTransferFTAndNFTFullFeeUsd(
                        sigs, uniqueHooksExecuted, uniqueAccounts, tokenTypes, gasAmount),
                txnSize);
    }

    public static double expectedCryptoTransferHBARAndFTFullFeeUsd(
            long sigs, long uniqueHooksExecuted, long uniqueAccounts, long tokenTypes, long gasAmount) {

        return expectedCryptoTransferFullFeeUsd(
                sigs, uniqueHooksExecuted, uniqueAccounts, tokenTypes, gasAmount, true, true, false);
    }

    public static double expectedCryptoTransferHBARAndFTFullFeeUsd(
            long sigs, long uniqueHooksExecuted, long uniqueAccounts, long tokenTypes, long gasAmount, int txnSize) {
        return addNodeAndNetworkBytes(
                expectedCryptoTransferHBARAndFTFullFeeUsd(
                        sigs, uniqueHooksExecuted, uniqueAccounts, tokenTypes, gasAmount),
                txnSize);
    }

    public static double expectedCryptoTransferHBARAndNFTFullFeeUsd(
            long sigs, long uniqueHooksExecuted, long uniqueAccounts, long tokenTypes, long gasAmount) {

        return expectedCryptoTransferFullFeeUsd(
                sigs, uniqueHooksExecuted, uniqueAccounts, tokenTypes, gasAmount, true, true, false);
    }

    public static double expectedCryptoTransferHBARAndNFTFullFeeUsd(
            long sigs, long uniqueHooksExecuted, long uniqueAccounts, long tokenTypes, long gasAmount, int txnSize) {
        return addNodeAndNetworkBytes(
                expectedCryptoTransferHBARAndNFTFullFeeUsd(
                        sigs, uniqueHooksExecuted, uniqueAccounts, tokenTypes, gasAmount),
                txnSize);
    }

    public static double expectedCryptoTransferHBARAndFTAndNFTFullFeeUsd(
            long sigs, long uniqueHooksExecuted, long uniqueAccounts, long tokenTypes, long gasAmount) {

        return expectedCryptoTransferFullFeeUsd(
                sigs, uniqueHooksExecuted, uniqueAccounts, tokenTypes, gasAmount, true, true, false);
    }

    public static double expectedCryptoTransferTokenWithCustomFullFeeUsd(
            long sigs, long uniqueHooksExecuted, long uniqueAccounts, long tokenTypes, long gasAmount) {

        return expectedCryptoTransferFullFeeUsd(
                sigs, uniqueHooksExecuted, uniqueAccounts, tokenTypes, gasAmount, false, false, true);
    }

    // Overload with txn size
    public static double expectedCryptoTransferTokenWithCustomFullFeeUsd(
            long sigs, long uniqueHooksExecuted, long uniqueAccounts, long tokenTypes, long gasAmount, int txnSize) {

        final double fullWithoutBytes = expectedCryptoTransferFullFeeUsd(
                sigs, uniqueHooksExecuted, uniqueAccounts, tokenTypes, gasAmount, false, false, true);

        final double nodeAndNetworkWithoutBytes = expectedNodeAndNetworkFeeUsd(sigs, 0);

        final double serviceOnly = fullWithoutBytes - nodeAndNetworkWithoutBytes;

        final double nodeAndNetworkWithBytes = expectedNodeAndNetworkFeeUsd(sigs, txnSize);

        return nodeAndNetworkWithBytes + serviceOnly;
    }

    public static double expectedCryptoTransferTokenWithCustomFullFeeUsd(final Map<FeeParam, Object> extras) {

        return expectedCryptoTransferTokenWithCustomFullFeeUsd(
                longValue(extras, FeeParam.SIGNATURES, 0),
                longValue(extras, FeeParam.HOOKS_EXECUTED, 0),
                longValue(extras, FeeParam.ACCOUNTS, 0),
                longValue(extras, FeeParam.TOKEN_TYPES, 0),
                longValue(extras, FeeParam.GAS, 0),
                intValue(extras, FeeParam.TXN_SIZE, 0));
    }

    public static double expectedCryptoTransferHBARAndFTAndNFTFullFeeUsd(
            long sigs, long uniqueHooksExecuted, long uniqueAccounts, long tokenTypes, long gasAmount, int txnSize) {
        return addNodeAndNetworkBytes(
                expectedCryptoTransferHBARAndFTAndNFTFullFeeUsd(
                        sigs, uniqueHooksExecuted, uniqueAccounts, tokenTypes, gasAmount),
                txnSize);
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

    public static double expectedTopicCreateWithCustomFeeFullFeeUsd(final Map<FeeParam, Object> extras) {
        return expectedTopicCreateWithCustomFeeFullFeeUsd(
                longValue(extras, FeeParam.SIGNATURES, 0),
                longValue(extras, FeeParam.KEYS, 0),
                intValue(extras, FeeParam.TXN_SIZE, 0));
    }

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

    public static double expectedTopicUpdateFullFeeUsd(final Map<FeeParam, Object> extras) {
        return expectedTopicUpdateFullFeeUsd(
                longValue(extras, FeeParam.SIGNATURES, 0),
                longValue(extras, FeeParam.KEYS, 0),
                intValue(extras, FeeParam.TXN_SIZE, 0));
    }

    /**
     * Overload when there are no key extras (no key change).
     */
    public static double expectedTopicUpdateFullFeeUsd(long sigs) {
        return expectedTopicUpdateFullFeeUsd(sigs, 0L);
    }

    public static double expectedTopicUpdateNetworkFeeOnlyUsd(long sigs) {
        // ----- node fees -----
        final long sigExtrasNode = Math.max(0L, sigs - NODE_INCLUDED_SIGNATURES);
        final double nodeExtrasFee = sigExtrasNode * SIGNATURE_FEE_USD;
        final double nodeFee = NODE_BASE_FEE_USD + nodeExtrasFee;

        // ----- network fees -----
        return nodeFee * NETWORK_MULTIPLIER;
    }

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

    public static double expectedTopicDeleteFullFeeUsd(final Map<FeeParam, Object> extras) {
        return expectedTopicDeleteFullFeeUsd(
                longValue(extras, FeeParam.SIGNATURES, 0), intValue(extras, FeeParam.TXN_SIZE, 0));
    }

    public static double expectedTopicDeleteNetworkFeeOnlyUsd(long sigs) {
        // ----- node fees -----
        final long sigExtrasNode = Math.max(0L, sigs - NODE_INCLUDED_SIGNATURES);
        final double nodeExtrasFee = sigExtrasNode * SIGNATURE_FEE_USD;
        final double nodeFee = NODE_BASE_FEE_USD + nodeExtrasFee;

        // ----- network fees -----
        return nodeFee * NETWORK_MULTIPLIER;
    }

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
            serviceBaseFee = CONS_SUBMIT_MESSAGE_WITH_CUSTOM_FEE_USD;
        }
        final double serviceFee = serviceBaseFee + serviceBytesExtrasFee;

        return nodeFee + networkFee + serviceFee;
    }

    public static double expectedTopicSubmitMessageFullFeeUsd(long sigs, long messageBytes, int txnSize) {
        return expectedTopicSubmitMessageFullFeeUsd(sigs, messageBytes, false, txnSize);
    }

    public static double expectedTopicSubmitMessageFullFeeUsd(final Map<FeeParam, Object> extras) {

        return expectedTopicSubmitMessageFullFeeUsd(
                longValue(extras, FeeParam.SIGNATURES, 0),
                longValue(extras, FeeParam.BYTES, 0),
                intValue(extras, FeeParam.TXN_SIZE, 0));
    }

    public static double expectedTopicSubmitMessageWithCustomFeeFullFeeUsd(long sigs, long messageBytes, int txnSize) {
        return expectedTopicSubmitMessageFullFeeUsd(sigs, messageBytes, true, txnSize);
    }

    public static double expectedTopicSubmitMessageWithCustomFeeFullFeeUsd(final Map<FeeParam, Object> extras) {

        return expectedTopicSubmitMessageWithCustomFeeFullFeeUsd(
                longValue(extras, FeeParam.SIGNATURES, 0),
                longValue(extras, FeeParam.BYTES, 0),
                intValue(extras, FeeParam.TXN_SIZE, 0));
    }

    public static double expectedTopicSubmitMessageNetworkFeeOnlyUsd(long sigs) {
        // ----- node fees -----
        final long sigExtrasNode = Math.max(0L, sigs - NODE_INCLUDED_SIGNATURES);
        final double nodeExtrasFee = sigExtrasNode * SIGNATURE_FEE_USD;
        final double nodeFee = NODE_BASE_FEE_USD + nodeExtrasFee;

        // ----- network fees -----
        return nodeFee * NETWORK_MULTIPLIER;
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

    /**
     * SimpleFees formula for TokenCreate (fungible or NFT without custom fees):
     * node    = NODE_BASE + SIGNATURE_FEE * max(0, sigs - includedSigsNode)
     * network = node * NETWORK_MULTIPLIER
     * service = TOKEN_CREATE_BASE + KEYS_FEE * max(0, keys - includedKeys)
     * total   = node + network + service
     */
    public static double expectedTokenCreateFungibleFullFeeUsd(long sigs, long keys, int txnSize) {
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

    public static double expectedTokenCreateFullFeeUsd(final Map<FeeParam, Object> extras) {

        return expectedTokenCreateFungibleFullFeeUsd(
                longValue(extras, FeeParam.SIGNATURES, 0),
                longValue(extras, FeeParam.KEYS, 0),
                intValue(extras, FeeParam.TXN_SIZE, 0));
    }

    /**
     * SimpleFees formula for TokenCreate (fungible with custom fees):
     * node    = NODE_BASE + SIGNATURE_FEE * max(0, sigs - includedSigsNode)
     * network = node * NETWORK_MULTIPLIER
     * service = TOKEN_CREATE_BASE + KEYS_FEE * extras + TOKEN_CREATE_WITH_CUSTOM_FEE
     * total   = node + network + service
     */
    public static double expectedTokenCreateFungibleWithCustomFeesFullFeeUsd(long sigs, long keys, int txnSize) {
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

    public static double expectedTokenCreateWithCustomFullFeeUsd(final Map<FeeParam, Object> extras) {
        return expectedTokenCreateFungibleWithCustomFeesFullFeeUsd(
                longValue(extras, FeeParam.SIGNATURES, 0),
                longValue(extras, FeeParam.KEYS, 0),
                intValue(extras, FeeParam.TXN_SIZE, 0));
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

    public static double expectedTokenUpdateFullFeeUsd(long sigs, long keys) {
        return expectedTokenUpdateFullFeeUsd(sigs, keys, 0);
    }

    public static double expectedTokenUpdateFullFeeUsd(final Map<FeeParam, Object> extras) {
        return expectedTokenUpdateFullFeeUsd(
                longValue(extras, FeeParam.SIGNATURES, 0),
                longValue(extras, FeeParam.KEYS, 0),
                intValue(extras, FeeParam.TXN_SIZE, 0));
    }

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

    public static double expectedTokenNftUpdateFullFeeUsd(final Map<FeeParam, Object> extras) {
        return expectedTokenNftUpdateFullFeeUsd(
                longValue(extras, FeeParam.SIGNATURES, 0),
                longValue(extras, FeeParam.NON_FUNGIBLE_TOKENS, 0),
                intValue(extras, FeeParam.TXN_SIZE, 0));
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

    /**
     * SimpleFees formula for TokenDelete:
     * node    = NODE_BASE + SIGNATURE_FEE * max(0, sigs - includedSigsNode)
     * network = node * NETWORK_MULTIPLIER
     * service = TOKEN_DELETE_BASE
     * total   = node + network + service
     */
    public static double expectedTokenDeleteFullFeeUsd(long sigs, int txnSize) {
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

    public static double expectedTokenDeleteFullFeeUsd(long sigs) {
        return expectedTokenDeleteFullFeeUsd(sigs, 0);
    }

    public static double expectedTokenDeleteFullFeeUsd(final Map<FeeParam, Object> extras) {
        return expectedTokenDeleteFullFeeUsd(
                longValue(extras, FeeParam.SIGNATURES, 0), intValue(extras, FeeParam.TXN_SIZE, 0));
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

    /**
     * SimpleFees formula for TokenMint (fungible):
     * node    = NODE_BASE + SIGNATURE_FEE * max(0, sigs - includedSigsNode)
     * network = node * NETWORK_MULTIPLIER
     * service = TOKEN_MINT_BASE
     * total   = node + network + service
     */
    public static double expectedTokenMintFungibleFullFeeUsd(long sigs, int txnSize) {
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

    public static double expectedTokenMintFungibleFullFeeUsd(long sigs) {
        return expectedTokenMintFungibleFullFeeUsd(sigs, 0);
    }

    public static double expectedTokenMintFungibleFullFeeUsd(final Map<FeeParam, Object> extras) {

        return expectedTokenMintFungibleFullFeeUsd(
                longValue(extras, FeeParam.SIGNATURES, 0), intValue(extras, FeeParam.TXN_SIZE, 0));
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
        final double serviceFee = TOKEN_MINT_NFT_BASE_FEE_USD + serviceExtrasFee;

        return nodeFee + networkFee + serviceFee;
    }

    public static double expectedTokenMintNftFullFeeUsd(final Map<FeeParam, Object> extras) {

        return expectedTokenMintNftFullFeeUsd(
                longValue(extras, FeeParam.SIGNATURES, 0),
                longValue(extras, FeeParam.TOKEN_TYPES, 0),
                intValue(extras, FeeParam.TXN_SIZE, 0));
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

    /**
     * SimpleFees formula for TokenBurn (fungible):
     * node    = NODE_BASE + SIGNATURE_FEE * max(0, sigs - includedSigsNode)
     * network = node * NETWORK_MULTIPLIER
     * service = TOKEN_BURN_BASE
     * total   = node + network + service
     */
    public static double expectedTokenBurnFungibleFullFeeUsd(long sigs, int txnSize) {
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

    public static double expectedTokenBurnFungibleFullFeeUsd(final Map<FeeParam, Object> extras) {
        return expectedTokenBurnFungibleFullFeeUsd(
                longValue(extras, FeeParam.SIGNATURES, 0), intValue(extras, FeeParam.TXN_SIZE, 0));
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

    /**
     * SimpleFees formula for TokenAssociate:
     * node    = NODE_BASE + SIGNATURE_FEE * max(0, sigs - includedSigsNode)
     * network = node * NETWORK_MULTIPLIER
     * service = TOKEN_ASSOCIATE_BASE * tokens count
     * total   = node + network + service
     */
    public static double expectedTokenAssociateFullFeeUsd(long sigs, long tokens, int txnSize) {
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

    public static double expectedTokenAssociateFullFeeUsd(long sigs, long tokens) {
        return expectedTokenAssociateFullFeeUsd(sigs, tokens, 0);
    }

    public static double expectedTokenAssociateFullFeeUsd(final Map<FeeParam, Object> extras) {
        return expectedTokenAssociateFullFeeUsd(
                longValue(extras, FeeParam.SIGNATURES, 0),
                longValue(extras, FeeParam.TOKENS, 0),
                intValue(extras, FeeParam.TXN_SIZE, 0));
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

    /**
     * SimpleFees formula for TokenDissociate:
     * node    = NODE_BASE + SIGNATURE_FEE * max(0, sigs - includedSigsNode)
     * network = node * NETWORK_MULTIPLIER
     * service = TOKEN_DISSOCIATE_BASE
     * total   = node + network + service
     */
    public static double expectedTokenDissociateFullFeeUsd(long sigs, long tokens, int txnSize) {
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

    public static double expectedTokenDissociateFullFeeUsd(long sigs, long tokens) {
        return expectedTokenDissociateFullFeeUsd(sigs, tokens, 0);
    }

    public static double expectedTokenDissociateFullFeeUsd(final Map<FeeParam, Object> extras) {
        return expectedTokenDissociateFullFeeUsd(
                longValue(extras, FeeParam.SIGNATURES, 0), intValue(extras, FeeParam.TXN_SIZE, 0));
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

    /**
     * SimpleFees formula for TokenGrantKyc:
     * node    = NODE_BASE + SIGNATURE_FEE * max(0, sigs - includedSigsNode)
     * network = node * NETWORK_MULTIPLIER
     * service = TOKEN_GRANT_KYC_BASE
     * total   = node + network + service
     */
    public static double expectedTokenGrantKycFullFeeUsd(long sigs, int txnSize) {
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

    public static double expectedTokenGrantKycFullFeeUsd(long sigs) {
        return expectedTokenGrantKycFullFeeUsd(sigs, 0);
    }

    public static double expectedTokenGrantKycFullFeeUsd(final Map<FeeParam, Object> extras) {
        return expectedTokenGrantKycFullFeeUsd(
                longValue(extras, FeeParam.SIGNATURES, 0), intValue(extras, FeeParam.TXN_SIZE, 0));
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

    /**
     * SimpleFees formula for TokenRevokeKyc:
     * node    = NODE_BASE + SIGNATURE_FEE * max(0, sigs - includedSigsNode)
     * network = node * NETWORK_MULTIPLIER
     * service = TOKEN_REVOKE_KYC_BASE
     * total   = node + network + service
     */
    public static double expectedTokenRevokeKycFullFeeUsd(long sigs, int txnSize) {
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

    public static double expectedTokenRevokeKycFullFeeUsd(long sigs) {
        return expectedTokenRevokeKycFullFeeUsd(sigs, 0);
    }

    public static double expectedTokenRevokeKycFullFeeUsd(final Map<FeeParam, Object> extras) {
        return expectedTokenRevokeKycFullFeeUsd(
                longValue(extras, FeeParam.SIGNATURES, 0), intValue(extras, FeeParam.TXN_SIZE, 0));
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

    /**
     * SimpleFees formula for TokenFreeze:
     * node    = NODE_BASE + SIGNATURE_FEE * max(0, sigs - includedSigsNode)
     * network = node * NETWORK_MULTIPLIER
     * service = TOKEN_FREEZE_BASE
     * total   = node + network + service
     */
    public static double expectedTokenFreezeFullFeeUsd(long sigs, int txnSize) {
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

    public static double expectedTokenFreezeFullFeeUsd(long sigs) {
        return expectedTokenFreezeFullFeeUsd(sigs, 0);
    }

    public static double expectedTokenFreezeFullFeeUsd(final Map<FeeParam, Object> extras) {
        return expectedTokenFreezeFullFeeUsd(
                longValue(extras, FeeParam.SIGNATURES, 0), intValue(extras, FeeParam.TXN_SIZE, 0));
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

    /**
     * SimpleFees formula for TokenUnfreeze:
     * node    = NODE_BASE + SIGNATURE_FEE * max(0, sigs - includedSigsNode)
     * network = node * NETWORK_MULTIPLIER
     * service = TOKEN_UNFREEZE_BASE
     * total   = node + network + service
     */
    public static double expectedTokenUnfreezeFullFeeUsd(long sigs, int txnSize) {
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

    public static double expectedTokenUnfreezeFullFeeUsd(long sigs) {
        return expectedTokenUnfreezeFullFeeUsd(sigs, 0);
    }

    public static double expectedTokenUnfreezeFullFeeUsd(final Map<FeeParam, Object> extras) {
        return expectedTokenUnfreezeFullFeeUsd(
                longValue(extras, FeeParam.SIGNATURES, 0), intValue(extras, FeeParam.TXN_SIZE, 0));
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

    /**
     * SimpleFees formula for TokenPause:
     * node    = NODE_BASE + SIGNATURE_FEE * max(0, sigs - includedSigsNode)
     * network = node * NETWORK_MULTIPLIER
     * service = TOKEN_PAUSE_BASE
     * total   = node + network + service
     */
    public static double expectedTokenPauseFullFeeUsd(long sigs, int txnSize) {
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

    public static double expectedTokenPauseFullFeeUsd(long sigs) {
        return expectedTokenPauseFullFeeUsd(sigs, 0);
    }

    public static double expectedTokenPauseFullFeeUsd(final Map<FeeParam, Object> extras) {
        return expectedTokenPauseFullFeeUsd(
                longValue(extras, FeeParam.SIGNATURES, 0), intValue(extras, FeeParam.TXN_SIZE, 0));
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

    /**
     * SimpleFees formula for TokenUnpause:
     * node    = NODE_BASE + SIGNATURE_FEE * max(0, sigs - includedSigsNode)
     * network = node * NETWORK_MULTIPLIER
     * service = TOKEN_UNPAUSE_BASE
     * total   = node + network + service
     */
    public static double expectedTokenUnpauseFullFeeUsd(long sigs, int txnSize) {
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

    public static double expectedTokenUnpauseFullFeeUsd(long sigs) {
        return expectedTokenUnpauseFullFeeUsd(sigs, 0);
    }

    public static double expectedTokenUnpauseFullFeeUsd(final Map<FeeParam, Object> extras) {
        return expectedTokenUnpauseFullFeeUsd(
                longValue(extras, FeeParam.SIGNATURES, 0), intValue(extras, FeeParam.TXN_SIZE, 0));
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

    /**
     * SimpleFees formula for AtomicBatch:
     * node    = NODE_BASE + SIGNATURE_FEE * max(0, sigs - includedSigsNode + nodeFeeFromBytesUsd(txnSize))
     * network = node * NETWORK_MULTIPLIER
     * service = ATOMIC_BATCH_BASE
     * total   = node + network + service
     */
    public static double expectedAtomicBatchFullFeeUsd(long sigs, int txnSize) {
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

    public static double expectedAtomicBatchFullFeeUsd(final Map<FeeParam, Object> extras) {

        return expectedAtomicBatchFullFeeUsd(
                longValue(extras, FeeParam.SIGNATURES, 0), intValue(extras, FeeParam.TXN_SIZE, 0));
    }

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

    public static double expectedCryptoApproveAllowanceFullFeeUsd(final Map<FeeParam, Object> extras) {
        return expectedCryptoApproveAllowanceFullFeeUsd(
                longValue(extras, FeeParam.SIGNATURES, 0),
                longValue(extras, FeeParam.ALLOWANCES, 0),
                intValue(extras, FeeParam.TXN_SIZE, 0));
    }

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

    public static double expectedCryptoDeleteAllowanceFullFeeUsd(final Map<FeeParam, Object> extras) {
        return expectedCryptoDeleteAllowanceFullFeeUsd(
                longValue(extras, FeeParam.SIGNATURES, 0),
                longValue(extras, FeeParam.ALLOWANCES, 0),
                intValue(extras, FeeParam.TXN_SIZE, 0));
    }

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

    public static double expectedFileCreateFullFeeUsd(final Map<FeeParam, Object> extras) {

        return expectedFileCreateFullFeeUsd(
                longValue(extras, FeeParam.SIGNATURES, 0),
                longValue(extras, FeeParam.KEYS, 0),
                longValue(extras, FeeParam.BYTES, 0),
                intValue(extras, FeeParam.TXN_SIZE, 0));
    }

    public static double expectedFileDeleteFullFeeUsd(long sigs, int txnSize) {
        // ----- node fees -----
        final long sigExtrasNode = Math.max(0L, sigs - NODE_INCLUDED_SIGNATURES);
        final double nodeExtrasFee = sigExtrasNode * SIGNATURE_FEE_USD;
        final double nodeFee = NODE_BASE_FEE_USD + nodeExtrasFee + nodeFeeFromBytesUsd(txnSize);

        // ----- network fees -----
        final double networkFee = nodeFee * NETWORK_MULTIPLIER;

        return nodeFee + networkFee + FILE_DELETE_BASE_FEE_USD;
    }

    public static double expectedFileDeleteFullFeeUsd(final Map<FeeParam, Object> extras) {

        return expectedFileDeleteFullFeeUsd(
                longValue(extras, FeeParam.SIGNATURES, 0), intValue(extras, FeeParam.TXN_SIZE, 0));
    }

    public static double expectedFileAppendFullFeeUsd(long sigs, long messageBytes, int txnSize) {
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

    public static double expectedFileAppendFullFeeUsd(final Map<FeeParam, Object> extras) {

        return expectedFileAppendFullFeeUsd(
                longValue(extras, FeeParam.SIGNATURES, 0),
                longValue(extras, FeeParam.BYTES, 0),
                intValue(extras, FeeParam.TXN_SIZE, 0));
    }

    public static double expectedPrngFullFeeUsd(long sigs, int txnSize) {
        // ----- node fees -----
        final long sigExtrasNode = Math.max(0L, sigs - NODE_INCLUDED_SIGNATURES);
        final double nodeExtrasFee = sigExtrasNode * SIGNATURE_FEE_USD;
        final double nodeFee = NODE_BASE_FEE_USD + nodeExtrasFee + nodeFeeFromBytesUsd(txnSize);

        // ----- network fees -----
        final double networkFee = nodeFee * NETWORK_MULTIPLIER;

        return nodeFee + networkFee + UTIL_PRNG_BASE_FEE_USD;
    }

    public static double expectedPrngFullFeeUsd(final Map<FeeParam, Object> extras) {

        return expectedPrngFullFeeUsd(
                longValue(extras, FeeParam.SIGNATURES, 0), intValue(extras, FeeParam.TXN_SIZE, 0));
    }

    public static double expectedTokenClaimAirdropFullFeeUsd(long sigs, int txnSize) {
        // ----- node fees -----
        final long sigExtrasNode = Math.max(0L, sigs - NODE_INCLUDED_SIGNATURES);
        final double nodeExtrasFee = sigExtrasNode * SIGNATURE_FEE_USD;
        final double nodeFee = NODE_BASE_FEE_USD + nodeExtrasFee + nodeFeeFromBytesUsd(txnSize);

        // ----- network fees -----
        final double networkFee = nodeFee * NETWORK_MULTIPLIER;

        return nodeFee + networkFee + AIRDROP_CLAIM_FEE_USD;
    }

    public static double expectedTokenClaimAirdropFullFeeUsd(final Map<FeeParam, Object> extras) {

        return expectedTokenClaimAirdropFullFeeUsd(
                longValue(extras, FeeParam.SIGNATURES, 0), intValue(extras, FeeParam.TXN_SIZE, 0));
    }

    public static double expectedTokenCancelAirdropFullFeeUsd(long sigs, int txnSize) {
        // ----- node fees -----
        final long sigExtrasNode = Math.max(0L, sigs - NODE_INCLUDED_SIGNATURES);
        final double nodeExtrasFee = sigExtrasNode * SIGNATURE_FEE_USD;
        final double nodeFee = NODE_BASE_FEE_USD + nodeExtrasFee + nodeFeeFromBytesUsd(txnSize);

        // ----- network fees -----
        final double networkFee = nodeFee * NETWORK_MULTIPLIER;

        return nodeFee + networkFee + AIRDROP_CANCEL_FEE_USD;
    }

    public static double expectedTokenCancelAirdropFullFeeUsd(final Map<FeeParam, Object> extras) {

        return expectedTokenCancelAirdropFullFeeUsd(
                longValue(extras, FeeParam.SIGNATURES, 0), intValue(extras, FeeParam.TXN_SIZE, 0));
    }

    public static double expectedTokenRejectFullFeeUsd(long sigs, int txnSize) {
        // ----- node fees -----
        final long sigExtrasNode = Math.max(0L, sigs - NODE_INCLUDED_SIGNATURES);
        final double nodeExtrasFee = sigExtrasNode * SIGNATURE_FEE_USD;
        final double nodeFee = NODE_BASE_FEE_USD + nodeExtrasFee + nodeFeeFromBytesUsd(txnSize);

        // ----- network fees -----
        final double networkFee = nodeFee * NETWORK_MULTIPLIER;

        return nodeFee + networkFee + TOKEN_REJECT_FEE_USD;
    }

    public static double expectedTokenRejectFullFeeUsd(final Map<FeeParam, Object> extras) {

        return expectedTokenRejectFullFeeUsd(
                longValue(extras, FeeParam.SIGNATURES, 0), intValue(extras, FeeParam.TXN_SIZE, 0));
    }

    public static double expectedTokenFeeScheduleUpdateFullFeeUsd(long sigs, int txnSize) {
        // ----- node fees -----
        final long sigExtrasNode = Math.max(0L, sigs - NODE_INCLUDED_SIGNATURES);
        final double nodeExtrasFee = sigExtrasNode * SIGNATURE_FEE_USD;
        final double nodeFee = NODE_BASE_FEE_USD + nodeExtrasFee + nodeFeeFromBytesUsd(txnSize);

        // ----- network fees -----
        final double networkFee = nodeFee * NETWORK_MULTIPLIER;

        return nodeFee + networkFee + TOKEN_FEE_SCHEDULE_UPDATE_FEE_USD;
    }

    public static double expectedTokenFeeScheduleUpdateFullFeeUsd(final Map<FeeParam, Object> extras) {

        return expectedTokenFeeScheduleUpdateFullFeeUsd(
                longValue(extras, FeeParam.SIGNATURES, 0), intValue(extras, FeeParam.TXN_SIZE, 0));
    }

    /*
     * Dual-mode fee validation that branches on {@code fees.simpleFeesEnabled} at runtime.
     * When simple fees are enabled, validates against {@code simpleFee};
     * otherwise validates against {@code legacyFee}.
     */
    public static SpecOperation validateFees(final String txn, final double legacyFee, final double simpleFee) {
        return doWithStartupConfig("fees.simpleFeesEnabled", flag -> {
            if ("true".equals(flag)) {
                return validateChargedUsdWithin(txn, simpleFee, 0.01);
            } else {
                return validateChargedUsdWithin(txn, legacyFee, 0.01);
            }
        });
    }

    public static SpecOperation validateInnerTxnFees(String txn, String parent, double legacyFee, double simpleFee) {
        return doWithStartupConfig("fees.simpleFeesEnabled", flag -> {
            if ("true".equals(flag)) {
                return validateInnerTxnChargedUsd(txn, parent, simpleFee);
            } else {
                return validateInnerTxnChargedUsd(txn, parent, legacyFee);
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
}
