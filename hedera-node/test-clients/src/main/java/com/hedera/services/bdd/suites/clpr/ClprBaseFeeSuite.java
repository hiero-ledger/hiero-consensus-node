// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.clpr;

import static com.hedera.services.bdd.junit.ContextRequirement.PROPERTY_OVERRIDES;
import static com.hedera.services.bdd.junit.TestTags.CLPR;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprRegisterChannel;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprRegisterConnector;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprSubmitBundle;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overridingTwo;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.validateChargedUsdFromRecordWithTxnSize;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.NETWORK_MULTIPLIER;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.NODE_BASE_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.NODE_INCLUDED_BYTES;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.NODE_INCLUDED_SIGNATURES;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.PROCESSING_BYTES_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.SIGNATURE_FEE_USD;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CLPR_CHANNEL_NOT_FOUND;

import com.hedera.services.bdd.junit.LeakyHapiTest;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * HapiTests that close the unit-test ↔ live-HAPI gap on CLPR base-fee charging.
 *
 * <p>The genesis fee schedule pins every CLPR transaction at a flat 10,000,000 tinycents
 * (= $0.001 = 1/10¢). {@code ClprFeeCalculatorTest} and {@code ClprGenesisFeeScheduleTest}
 * verify the calculator and schedule independently. These tests exercise the wire and
 * confirm the USD actually charged matches the configured base.
 *
 * <p>Coverage:
 * <ul>
 *   <li>{@code submitBundle} — failure-path against an unknown channel. Pre-check
 *       passes, the handler throws {@code CLPR_CHANNEL_NOT_FOUND}, and Hiero charges
 *       the full fee for handler-stage failures. Avoids the verifier-contract setup.
 *       TODO: add a success-path test in {@code ClprSubmitBundleSuite} to confirm the
 *       base fee is also charged (not zero, not the endpoint-penalty amount) after a
 *       valid proof verification and message dispatch.</li>
 *   <li>{@code registerChannel}, {@code registerConnector} — success path. Both
 *       commit-phase ops are permissionless and need only {@code clpr.enabled=true}.</li>
 *   <li>Reveal-phase ops ({@code completeChannel}, {@code completeConnector}) are not
 *       covered here because they require a deployed verifier/connector smart contract and
 *       a full two-phase lifecycle setup. Fee coverage for those ops should be added to
 *       {@code ClprChannelCommitRevealSuite} and {@code ClprConnectorSuite}.</li>
 * </ul>
 *
 * <p>Tests pay with a freshly-created account rather than {@code GENESIS}: the treasury is
 * fee-exempt at the record level, so a GENESIS-paid record always shows zero fees and
 * cannot validate the base charge.
 *
 * <p>Both {@code clpr.enabled} and {@code fees.simpleFeesEnabled} are pinned {@code true}
 * per test. {@code ClprFeeCalculator} only emits a service base under the simple-fees path
 * (it throws {@code UnsupportedOperationException} otherwise), so if the network default
 * for {@code fees.simpleFeesEnabled} ever flips, an unguarded suite would degrade silently
 * — either failing with the calculator's throw, or asserting {@link #expectedClprTotalUsd}
 * against the wrong fee model.
 *
 * <p>Tolerance is {@value #ALLOWED_PERCENT_DIFF}%, the same tight value used by the
 * HIP-1261 simple-fees suites ({@link
 * com.hedera.services.bdd.suites.hip1261.CryptoCreateSimpleFeesEmbeddedTest}). That works
 * because the expected USD is the <em>full</em> total (service + node + network),
 * computed from {@code SimpleFeesScheduleConstantsInUsd} via
 * {@link #expectedClprTotalUsd(long, int)} — not a service-base-only target with a
 * fudge-factor tolerance.
 *
 * <p>{@code updateLedgerConfiguration} is intentionally out of scope: it is gated by
 * {@code PrivilegesVerifier.checkClprAdmin}, so a non-admin payer is rejected at
 * pre-check and only network fees are charged — not enough to validate the service base.
 */
@Tag(CLPR)
public class ClprBaseFeeSuite {

    private static final String PAYER = "feePayer";

    /** Flat CLPR service-fee base from genesis simpleFeesSchedules.json (10,000,000 tinycents). */
    private static final double CLPR_SERVICE_BASE_FEE_USD = 0.001;

    /** Matches the HIP-1261 simple-fees convention. Tight, because the expected USD is precise. */
    private static final double ALLOWED_PERCENT_DIFF = 0.1;

    /** Test payer always provides exactly one key. */
    private static final long PAYER_SIGNATURES = 1L;

    @LeakyHapiTest(requirement = PROPERTY_OVERRIDES)
    final Stream<DynamicTest> submitBundleChargesConfiguredBaseFee() {
        final var txn = "clprSubmitBundleBaseFeeTxn";
        return hapiTest(
                overridingTwo("clpr.enabled", "true", "fees.simpleFeesEnabled", "true"),
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                clprSubmitBundle()
                        .channelId(new byte[32])
                        .bundlePayload(new byte[] {1})
                        .endpointNodeId(0L)
                        .payingWith(PAYER)
                        .via(txn)
                        .hasKnownStatus(CLPR_CHANNEL_NOT_FOUND),
                validateChargedUsdFromRecordWithTxnSize(
                        txn, txnSize -> expectedClprTotalUsd(PAYER_SIGNATURES, txnSize), ALLOWED_PERCENT_DIFF));
    }

    @LeakyHapiTest(requirement = PROPERTY_OVERRIDES)
    final Stream<DynamicTest> registerChannelChargesConfiguredBaseFee() {
        final var txn = "clprRegisterChannelBaseFeeTxn";
        return hapiTest(
                overridingTwo("clpr.enabled", "true", "fees.simpleFeesEnabled", "true"),
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                clprRegisterChannel()
                        .ownershipCommitment(commitment((byte) 0x01))
                        .payingWith(PAYER)
                        .via(txn),
                validateChargedUsdFromRecordWithTxnSize(
                        txn, txnSize -> expectedClprTotalUsd(PAYER_SIGNATURES, txnSize), ALLOWED_PERCENT_DIFF));
    }

    @LeakyHapiTest(requirement = PROPERTY_OVERRIDES)
    final Stream<DynamicTest> registerConnectorChargesConfiguredBaseFee() {
        final var txn = "clprRegisterConnectorBaseFeeTxn";
        return hapiTest(
                overridingTwo("clpr.enabled", "true", "fees.simpleFeesEnabled", "true"),
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                clprRegisterConnector()
                        .commitment(commitment((byte) 0x02))
                        .payingWith(PAYER)
                        .via(txn),
                validateChargedUsdFromRecordWithTxnSize(
                        txn, txnSize -> expectedClprTotalUsd(PAYER_SIGNATURES, txnSize), ALLOWED_PERCENT_DIFF));
    }

    /**
     * Expected total USD charge = service base + node fee + network fee.
     *
     * <p>Mirrors the formula in {@code FeesChargingUtils.expectedNodeAndNetworkFeeUsd}
     * (which is package-private and per-op-specific) but parameterised on the CLPR service
     * base. Node fee includes a signature-count overage if {@code sigs > NODE_INCLUDED_SIGNATURES}
     * and a byte-count overage if {@code txnSize > NODE_INCLUDED_BYTES}; the network fee is
     * {@code nodeFee × NETWORK_MULTIPLIER}.
     */
    private static double expectedClprTotalUsd(final long sigs, final int txnSize) {
        final double sigExtras = Math.max(0, sigs - NODE_INCLUDED_SIGNATURES) * SIGNATURE_FEE_USD;
        final double bytesExtras = Math.max(0, txnSize - NODE_INCLUDED_BYTES) * PROCESSING_BYTES_FEE_USD;
        final double nodeFee = NODE_BASE_FEE_USD + sigExtras + bytesExtras;
        final double networkFee = nodeFee * NETWORK_MULTIPLIER;
        return CLPR_SERVICE_BASE_FEE_USD + nodeFee + networkFee;
    }

    /** A 32-byte commitment whose first byte distinguishes it from other tests in this suite. */
    private static byte[] commitment(final byte tag) {
        final var bytes = new byte[32];
        bytes[0] = tag;
        return bytes;
    }
}
