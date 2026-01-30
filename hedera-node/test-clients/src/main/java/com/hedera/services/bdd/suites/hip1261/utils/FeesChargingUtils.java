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
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.BYTES_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.CONS_CREATE_TOPIC_BASE_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.CONS_CREATE_TOPIC_INCLUDED_KEYS;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.CONS_CREATE_TOPIC_WITH_CUSTOM_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.CONS_DELETE_TOPIC_BASE_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.CONS_UPDATE_TOPIC_BASE_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.CONS_UPDATE_TOPIC_INCLUDED_KEYS;
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
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.SUBMIT_MESSAGE_BASE_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.TOKEN_ASSOCIATE_BASE_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.TOKEN_ASSOCIATE_EXTRA_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.TOKEN_ASSOCIATE_INCLUDED_TOKENS;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.TOKEN_BURN_BASE_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.TOKEN_CREATE_BASE_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.TOKEN_CREATE_INCLUDED_KEYS;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.TOKEN_CREATE_WITH_CUSTOM_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.TOKEN_DELETE_BASE_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.TOKEN_DISSOCIATE_BASE_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.TOKEN_FREEZE_BASE_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.TOKEN_GRANT_KYC_BASE_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.TOKEN_MINT_BASE_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.TOKEN_MINT_INCLUDED_NFT;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.TOKEN_MINT_NFT_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.TOKEN_PAUSE_BASE_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.TOKEN_REVOKE_KYC_BASE_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.TOKEN_TRANSFER_BASE_CUSTOM_FEES_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.TOKEN_TRANSFER_BASE_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.TOKEN_UNFREEZE_BASE_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.TOKEN_UNPAUSE_BASE_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.TOKEN_UPDATE_BASE_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.TOKEN_UPDATE_INCLUDED_KEYS;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.TOKEN_WIPE_BASE_FEE_USD;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.protobuf.InvalidProtocolBufferException;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hederahashgraph.api.proto.java.Transaction;
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

    public static double expectedCryptoTransferHbarFullFeeUsd(
            long sigs,
            long uniqueHooksExecuted,
            long uniqueAccounts,
            long uniqueFungibleTokens,
            long uniqueNonFungibleTokens,
            long gasAmount,
            int txnSize) {
        return addNodeAndNetworkBytes(
                expectedCryptoTransferHbarFullFeeUsd(
                        sigs,
                        uniqueHooksExecuted,
                        uniqueAccounts,
                        uniqueFungibleTokens,
                        uniqueNonFungibleTokens,
                        gasAmount),
                txnSize);
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

    public static double expectedCryptoTransferFTFullFeeUsd(
            long sigs,
            long uniqueHooksExecuted,
            long uniqueAccounts,
            long uniqueFungibleTokens,
            long uniqueNonFungibleTokens,
            long gasAmount,
            int txnSize) {
        return addNodeAndNetworkBytes(
                expectedCryptoTransferFTFullFeeUsd(
                        sigs,
                        uniqueHooksExecuted,
                        uniqueAccounts,
                        uniqueFungibleTokens,
                        uniqueNonFungibleTokens,
                        gasAmount),
                txnSize);
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

    public static double expectedCryptoTransferNFTFullFeeUsd(
            long sigs,
            long uniqueHooksExecuted,
            long uniqueAccounts,
            long uniqueFungibleTokens,
            long uniqueNonFungibleTokens,
            long gasAmount,
            int txnSize) {
        return addNodeAndNetworkBytes(
                expectedCryptoTransferNFTFullFeeUsd(
                        sigs,
                        uniqueHooksExecuted,
                        uniqueAccounts,
                        uniqueFungibleTokens,
                        uniqueNonFungibleTokens,
                        gasAmount),
                txnSize);
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

    public static double expectedCryptoTransferFTAndNFTFullFeeUsd(
            long sigs,
            long uniqueHooksExecuted,
            long uniqueAccounts,
            long uniqueFungibleTokens,
            long uniqueNonFungibleTokens,
            long gasAmount,
            int txnSize) {
        return addNodeAndNetworkBytes(
                expectedCryptoTransferFTAndNFTFullFeeUsd(
                        sigs,
                        uniqueHooksExecuted,
                        uniqueAccounts,
                        uniqueFungibleTokens,
                        uniqueNonFungibleTokens,
                        gasAmount),
                txnSize);
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

    public static double expectedCryptoTransferHBARAndFTFullFeeUsd(
            long sigs,
            long uniqueHooksExecuted,
            long uniqueAccounts,
            long uniqueFungibleTokens,
            long uniqueNonFungibleTokens,
            long gasAmount,
            int txnSize) {
        return addNodeAndNetworkBytes(
                expectedCryptoTransferHBARAndFTFullFeeUsd(
                        sigs,
                        uniqueHooksExecuted,
                        uniqueAccounts,
                        uniqueFungibleTokens,
                        uniqueNonFungibleTokens,
                        gasAmount),
                txnSize);
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

    public static double expectedCryptoTransferHBARAndNFTFullFeeUsd(
            long sigs,
            long uniqueHooksExecuted,
            long uniqueAccounts,
            long uniqueFungibleTokens,
            long uniqueNonFungibleTokens,
            long gasAmount,
            int txnSize) {
        return addNodeAndNetworkBytes(
                expectedCryptoTransferHBARAndNFTFullFeeUsd(
                        sigs,
                        uniqueHooksExecuted,
                        uniqueAccounts,
                        uniqueFungibleTokens,
                        uniqueNonFungibleTokens,
                        gasAmount),
                txnSize);
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

    // Overload with txn size
    public static double expectedCryptoTransferTokenWithCustomFullFeeUsd(
            long sigs,
            long uniqueHooksExecuted,
            long uniqueAccounts,
            long uniqueFungibleTokens,
            long uniqueNonFungibleTokens,
            long gasAmount,
            int txnSize) {

        final double fullWithoutBytes = expectedCryptoTransferFullFeeUsd(
                sigs,
                uniqueHooksExecuted,
                uniqueAccounts,
                uniqueFungibleTokens,
                uniqueNonFungibleTokens,
                gasAmount,
                false,
                false,
                true);

        final double nodeAndNetworkWithoutBytes = expectedNodeAndNetworkFeeUsd(sigs, 0);

        final double serviceOnly = fullWithoutBytes - nodeAndNetworkWithoutBytes;

        final double nodeAndNetworkWithBytes = expectedNodeAndNetworkFeeUsd(sigs, txnSize);

        return nodeAndNetworkWithBytes + serviceOnly;
    }

    public static double expectedCryptoTransferHBARAndFTAndNFTFullFeeUsd(
            long sigs,
            long uniqueHooksExecuted,
            long uniqueAccounts,
            long uniqueFungibleTokens,
            long uniqueNonFungibleTokens,
            long gasAmount,
            int txnSize) {
        return addNodeAndNetworkBytes(
                expectedCryptoTransferHBARAndFTAndNFTFullFeeUsd(
                        sigs,
                        uniqueHooksExecuted,
                        uniqueAccounts,
                        uniqueFungibleTokens,
                        uniqueNonFungibleTokens,
                        gasAmount),
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
        final double serviceFee = CONS_CREATE_TOPIC_WITH_CUSTOM_FEE_USD + serviceExtrasFee;

        return nodeFee + networkFee + serviceFee;
    }

    public static double expectedTopicCreateWithCustomFeeFullFeeUsd(long sigs, long keys, int txnSize) {
        return addNodeAndNetworkBytes(expectedTopicCreateWithCustomFeeFullFeeUsd(sigs, keys), txnSize);
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
     * node    = nodeFeeFromBytes(txnSize) + SIGNATURE_FEE * max(0, sigs - includedSigsNode)
     * network = node * NETWORK_MULTIPLIER
     * service = CONS_SUBMIT_MESSAGE_BASE
     *         + BYTES_FEE * max(0, bytes - includedBytesService)
     * total   = node + network + service
     *
     * Note: For ConsensusSubmitMessage, the node base fee is NOT charged.
     * Only signature extras and bytes overage contribute to node/network fees.
     * The message bytes fee is part of the service fee, not the node fee.
     */
    public static double expectedTopicSubmitMessageFullFeeUsd(long sigs, long bytes, int txnSize) {
        // ----- node fees (only charged for txn size overage and extra signatures, NO base fee) -----
        final long sigExtrasNode = Math.max(0L, sigs - NODE_INCLUDED_SIGNATURES);
        final double nodeExtrasFee = sigExtrasNode * SIGNATURE_FEE_USD;
        final double nodeBytesOverageFee = nodeFeeFromBytesUsd(txnSize);
        // No node base fee for SubmitMessage - only extras
        final double nodeFee = nodeExtrasFee + nodeBytesOverageFee;

        // ----- network fees -----
        final double networkFee = nodeFee * NETWORK_MULTIPLIER;

        // ----- service fees -----
        final long byteExtrasService = Math.max(0L, bytes - 1024L);
        final double serviceExtrasFee = byteExtrasService * BYTES_FEE_USD;
        final double serviceFee = SUBMIT_MESSAGE_BASE_FEE_USD + serviceExtrasFee;

        return nodeFee + networkFee + serviceFee;
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

    public static double expectedFeeFromBytesFor(HapiSpec spec, Logger opLog, String txnName) {
        // Get the transaction bytes from the registry
        final var txnBytes = spec.registry().getBytes(txnName);
        final var txnSize = txnBytes.length;

        // Node fee BYTES extra: (txnBytes - 1024) * BYTES_FEE_USD * networkMultiplier
        final var nodeBytesOverage = Math.max(0, txnSize - 1024);
        double expectedFee = nodeBytesOverage * BYTES_FEE_USD * (1 + NETWORK_MULTIPLIER);

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
    public static double expectedTokenCreateFungibleFullFeeUsd(long sigs, long keys) {
        // ----- node fees -----
        final long sigExtrasNode = Math.max(0L, sigs - NODE_INCLUDED_SIGNATURES);
        final double nodeExtrasFee = sigExtrasNode * SIGNATURE_FEE_USD;
        final double nodeFee = NODE_BASE_FEE_USD + nodeExtrasFee;
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
        return expectedTokenCreateFungibleFullFeeUsd(sigs, keys);
    }

    /**
     * SimpleFees formula for TokenCreate (fungible with custom fees):
     * node    = NODE_BASE + SIGNATURE_FEE * max(0, sigs - includedSigsNode)
     * network = node * NETWORK_MULTIPLIER
     * service = TOKEN_CREATE_BASE + KEYS_FEE * extras + TOKEN_CREATE_WITH_CUSTOM_FEE
     * total   = node + network + service
     */
    public static double expectedTokenCreateFungibleWithCustomFeesFullFeeUsd(long sigs, long keys) {
        // ----- node fees -----
        final long sigExtrasNode = Math.max(0L, sigs - NODE_INCLUDED_SIGNATURES);
        final double nodeExtrasFee = sigExtrasNode * SIGNATURE_FEE_USD;
        final double nodeFee = NODE_BASE_FEE_USD + nodeExtrasFee;

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
        return expectedTokenCreateFungibleWithCustomFeesFullFeeUsd(sigs, keys);
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
    public static double expectedTokenUpdateFullFeeUsd(long sigs, long keys) {
        // ----- node fees -----
        final long sigExtrasNode = Math.max(0L, sigs - NODE_INCLUDED_SIGNATURES);
        final double nodeExtrasFee = sigExtrasNode * SIGNATURE_FEE_USD;
        final double nodeFee = NODE_BASE_FEE_USD + nodeExtrasFee;

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
        return expectedTokenUpdateFullFeeUsd(sigs, 0L);
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
    public static double expectedTokenDeleteFullFeeUsd(long sigs) {
        // ----- node fees -----
        final long sigExtrasNode = Math.max(0L, sigs - NODE_INCLUDED_SIGNATURES);
        final double nodeExtrasFee = sigExtrasNode * SIGNATURE_FEE_USD;
        final double nodeFee = NODE_BASE_FEE_USD + nodeExtrasFee;

        // ----- network fees -----
        final double networkFee = nodeFee * NETWORK_MULTIPLIER;

        // ----- service fees -----
        final double serviceFee = TOKEN_DELETE_BASE_FEE_USD;

        return nodeFee + networkFee + serviceFee;
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
    public static double expectedTokenMintFungibleFullFeeUsd(long sigs) {
        // ----- node fees -----
        final long sigExtrasNode = Math.max(0L, sigs - NODE_INCLUDED_SIGNATURES);
        final double nodeExtrasFee = sigExtrasNode * SIGNATURE_FEE_USD;
        final double nodeFee = NODE_BASE_FEE_USD + nodeExtrasFee;

        // ----- network fees -----
        final double networkFee = nodeFee * NETWORK_MULTIPLIER;

        // ----- service fees -----
        final double serviceFee = TOKEN_MINT_BASE_FEE_USD;

        return nodeFee + networkFee + serviceFee;
    }

    /**
     * SimpleFees formula for TokenMint (NFT):
     * node    = NODE_BASE + SIGNATURE_FEE * max(0, sigs - includedSigsNode)
     * network = node * NETWORK_MULTIPLIER
     * service = TOKEN_MINT_BASE + TOKEN_MINT_NFT_FEE * max(0, nftSerials - includedNft)
     * total   = node + network + service
     */
    public static double expectedTokenMintNftFullFeeUsd(long sigs, long nftSerials) {
        // ----- node fees -----
        final long sigExtrasNode = Math.max(0L, sigs - NODE_INCLUDED_SIGNATURES);
        final double nodeExtrasFee = sigExtrasNode * SIGNATURE_FEE_USD;
        final double nodeFee = NODE_BASE_FEE_USD + nodeExtrasFee;

        // ----- network fees -----
        final double networkFee = nodeFee * NETWORK_MULTIPLIER;

        // ----- service fees -----
        final long serialExtrasService = Math.max(0L, nftSerials - TOKEN_MINT_INCLUDED_NFT);
        final double serviceExtrasFee = serialExtrasService * TOKEN_MINT_NFT_FEE_USD;
        final double serviceFee = TOKEN_MINT_BASE_FEE_USD + serviceExtrasFee;

        return nodeFee + networkFee + serviceFee;
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
    public static double expectedTokenBurnFungibleFullFeeUsd(long sigs) {
        // ----- node fees -----
        final long sigExtrasNode = Math.max(0L, sigs - NODE_INCLUDED_SIGNATURES);
        final double nodeExtrasFee = sigExtrasNode * SIGNATURE_FEE_USD;
        final double nodeFee = NODE_BASE_FEE_USD + nodeExtrasFee;

        // ----- network fees -----
        final double networkFee = nodeFee * NETWORK_MULTIPLIER;

        // ----- service fees -----
        final double serviceFee = TOKEN_BURN_BASE_FEE_USD;

        return nodeFee + networkFee + serviceFee;
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
    public static double expectedTokenAssociateFullFeeUsd(long sigs, long tokens) {
        // ----- node fees -----
        final long sigExtrasNode = Math.max(0L, sigs - NODE_INCLUDED_SIGNATURES);
        final double nodeExtrasFee = sigExtrasNode * SIGNATURE_FEE_USD;
        final double nodeFee = NODE_BASE_FEE_USD + nodeExtrasFee;
        // ----- network fees -----
        final double networkFee = nodeFee * NETWORK_MULTIPLIER;
        // ----- service fees -----
        final long extraTokens = Math.max(0L, tokens - TOKEN_ASSOCIATE_INCLUDED_TOKENS);
        final double serviceFee = TOKEN_ASSOCIATE_BASE_FEE_USD + extraTokens * TOKEN_ASSOCIATE_EXTRA_FEE_USD;
        return nodeFee + networkFee + serviceFee;
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
    public static double expectedTokenDissociateFullFeeUsd(long sigs, long tokens) {
        // ----- node fees -----
        final long sigExtrasNode = Math.max(0L, sigs - NODE_INCLUDED_SIGNATURES);
        final double nodeExtrasFee = sigExtrasNode * SIGNATURE_FEE_USD;
        final double nodeFee = NODE_BASE_FEE_USD + nodeExtrasFee;

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
    public static double expectedTokenGrantKycFullFeeUsd(long sigs) {
        // ----- node fees -----
        final long sigExtrasNode = Math.max(0L, sigs - NODE_INCLUDED_SIGNATURES);
        final double nodeExtrasFee = sigExtrasNode * SIGNATURE_FEE_USD;
        final double nodeFee = NODE_BASE_FEE_USD + nodeExtrasFee;

        // ----- network fees -----
        final double networkFee = nodeFee * NETWORK_MULTIPLIER;

        // ----- service fees -----
        final double serviceFee = TOKEN_GRANT_KYC_BASE_FEE_USD;

        return nodeFee + networkFee + serviceFee;
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
    public static double expectedTokenRevokeKycFullFeeUsd(long sigs) {
        // ----- node fees -----
        final long sigExtrasNode = Math.max(0L, sigs - NODE_INCLUDED_SIGNATURES);
        final double nodeExtrasFee = sigExtrasNode * SIGNATURE_FEE_USD;
        final double nodeFee = NODE_BASE_FEE_USD + nodeExtrasFee;

        // ----- network fees -----
        final double networkFee = nodeFee * NETWORK_MULTIPLIER;

        // ----- service fees -----
        final double serviceFee = TOKEN_REVOKE_KYC_BASE_FEE_USD;

        return nodeFee + networkFee + serviceFee;
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
    public static double expectedTokenFreezeFullFeeUsd(long sigs) {
        // ----- node fees -----
        final long sigExtrasNode = Math.max(0L, sigs - NODE_INCLUDED_SIGNATURES);
        final double nodeExtrasFee = sigExtrasNode * SIGNATURE_FEE_USD;
        final double nodeFee = NODE_BASE_FEE_USD + nodeExtrasFee;

        // ----- network fees -----
        final double networkFee = nodeFee * NETWORK_MULTIPLIER;

        // ----- service fees -----
        final double serviceFee = TOKEN_FREEZE_BASE_FEE_USD;

        return nodeFee + networkFee + serviceFee;
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
    public static double expectedTokenUnfreezeFullFeeUsd(long sigs) {
        // ----- node fees -----
        final long sigExtrasNode = Math.max(0L, sigs - NODE_INCLUDED_SIGNATURES);
        final double nodeExtrasFee = sigExtrasNode * SIGNATURE_FEE_USD;
        final double nodeFee = NODE_BASE_FEE_USD + nodeExtrasFee;

        // ----- network fees -----
        final double networkFee = nodeFee * NETWORK_MULTIPLIER;

        // ----- service fees -----
        final double serviceFee = TOKEN_UNFREEZE_BASE_FEE_USD;

        return nodeFee + networkFee + serviceFee;
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
    public static double expectedTokenPauseFullFeeUsd(long sigs) {
        // ----- node fees -----
        final long sigExtrasNode = Math.max(0L, sigs - NODE_INCLUDED_SIGNATURES);
        final double nodeExtrasFee = sigExtrasNode * SIGNATURE_FEE_USD;
        final double nodeFee = NODE_BASE_FEE_USD + nodeExtrasFee;

        // ----- network fees -----
        final double networkFee = nodeFee * NETWORK_MULTIPLIER;

        // ----- service fees -----
        final double serviceFee = TOKEN_PAUSE_BASE_FEE_USD;

        return nodeFee + networkFee + serviceFee;
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
    public static double expectedTokenUnpauseFullFeeUsd(long sigs) {
        // ----- node fees -----
        final long sigExtrasNode = Math.max(0L, sigs - NODE_INCLUDED_SIGNATURES);
        final double nodeExtrasFee = sigExtrasNode * SIGNATURE_FEE_USD;
        final double nodeFee = NODE_BASE_FEE_USD + nodeExtrasFee;

        // ----- network fees -----
        final double networkFee = nodeFee * NETWORK_MULTIPLIER;

        // ----- service fees -----
        final double serviceFee = TOKEN_UNPAUSE_BASE_FEE_USD;

        return nodeFee + networkFee + serviceFee;
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
}
