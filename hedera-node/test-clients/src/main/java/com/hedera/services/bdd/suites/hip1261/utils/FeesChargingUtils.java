// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1261.utils;

import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.crypto.CryptoTransferSuite.sdec;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.CRYPTO_CREATE_BASE_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.CRYPTO_CREATE_INCLUDED_HOOKS;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.CRYPTO_CREATE_INCLUDED_KEYS;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.HOOKS_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.KEYS_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.NETWORK_MULTIPLIER;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.NODE_BASE_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.NODE_INCLUDED_SIGNATURES;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.SIGNATURE_FEE_USD;
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
    public static double expectedFullFeeUsd(long sigs, long keys, long hooks) {
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
    public static double expectedFullFeeUsd(long sigs, long keys) {
        return expectedFullFeeUsd(sigs, keys, 0L);
    }

    public static double expectedNetworkFeeOnlyUsd(long sigs) {
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
}
