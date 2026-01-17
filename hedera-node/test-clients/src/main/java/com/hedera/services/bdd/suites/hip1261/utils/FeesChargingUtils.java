// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1261.utils;

import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.crypto.CryptoTransferSuite.sdec;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.CONS_CREATE_TOPIC_BASE_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.CONS_CREATE_TOPIC_INCLUDED_KEYS;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.CRYPTO_CREATE_BASE_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.CRYPTO_CREATE_INCLUDED_HOOKS;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.CRYPTO_CREATE_INCLUDED_KEYS;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.HOOKS_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.KEYS_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.NETWORK_MULTIPLIER;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.NODE_BASE_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.NODE_INCLUDED_SIGNATURES;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.SIGNATURE_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.TOKEN_ASSOCIATE_BASE_FEE_USD;
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
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.TOKEN_UNFREEZE_BASE_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.TOKEN_UNPAUSE_BASE_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.TOKEN_UPDATE_BASE_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.TOKEN_UPDATE_INCLUDED_KEYS;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.TOKEN_WIPE_BASE_FEE_USD;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.services.bdd.spec.HapiSpecOperation;
import java.util.concurrent.atomic.AtomicLong;

public class FeesChargingUtils {

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
        // ----- node fees -----
        final long sigExtrasNode = Math.max(0L, sigs - NODE_INCLUDED_SIGNATURES);
        final double nodeExtrasFee = sigExtrasNode * SIGNATURE_FEE_USD;
        final double nodeFee = NODE_BASE_FEE_USD + nodeExtrasFee;

        // ----- network fees -----
        final double networkFee = nodeFee * NETWORK_MULTIPLIER;

        // ----- service fees -----
        final long keyExtrasService = Math.max(0L, keys - CRYPTO_CREATE_INCLUDED_KEYS);
        final long hookExtrasService = Math.max(0L, hooks - CRYPTO_CREATE_INCLUDED_HOOKS);
        final double serviceExtrasFee = keyExtrasService * KEYS_FEE_USD + hookExtrasService * HOOKS_FEE_USD;
        final double serviceFee = CRYPTO_CREATE_BASE_FEE_USD + serviceExtrasFee;

        return nodeFee + networkFee + serviceFee;
    }

    /**
     * Overload when there are no hooks extras.
     */
    public static double expectedCryptoCreateFullFeeUsd(long sigs, long keys) {
        return expectedCryptoCreateFullFeeUsd(sigs, keys, 0L);
    }

    /**
     *  Simple fees calculation for CryptoCreate with node fee only
     */
    public static double expectedCryptoCreateNetworkFeeOnlyUsd(long sigs) {
        // ----- node fees -----
        final long sigExtrasNode = Math.max(0L, sigs - NODE_INCLUDED_SIGNATURES);
        final double nodeExtrasFee = sigExtrasNode * SIGNATURE_FEE_USD;
        final double nodeFee = NODE_BASE_FEE_USD + nodeExtrasFee;

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
        // ----- node fees -----
        final long sigExtrasNode = Math.max(0L, sigs - NODE_INCLUDED_SIGNATURES);
        final double nodeExtrasFee = sigExtrasNode * SIGNATURE_FEE_USD;
        final double nodeFee = NODE_BASE_FEE_USD + nodeExtrasFee;

        // ----- network fees -----
        final double networkFee = nodeFee * NETWORK_MULTIPLIER;

        // ----- service fees -----
        final long keyExtrasService = Math.max(0L, keys - CONS_CREATE_TOPIC_INCLUDED_KEYS);
        final double serviceExtrasFee = keyExtrasService * KEYS_FEE_USD;
        final double serviceFee = CONS_CREATE_TOPIC_BASE_FEE_USD + serviceExtrasFee;

        return nodeFee + networkFee + serviceFee;
    }

    public static double expectedTopicCreateNetworkFeeOnlyUsd(long sigs) {
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
     * service = TOKEN_ASSOCIATE_BASE
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
        final double serviceFee = TOKEN_ASSOCIATE_BASE_FEE_USD;
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
}
