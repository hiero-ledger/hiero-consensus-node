// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.clpr;

import static com.hedera.services.bdd.junit.ContextRequirement.PROPERTY_OVERRIDES;
import static com.hedera.services.bdd.junit.TestTags.CLPR;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprCloseChannel;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprRegisterChannel;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprRegisterConnector;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprSubmitBundle;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprUpdateLedgerConfiguration;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.validateChargedUsdFromRecordWithTxnSize;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.NETWORK_MULTIPLIER;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.NODE_BASE_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.NODE_INCLUDED_BYTES;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.NODE_INCLUDED_SIGNATURES;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.PROCESSING_BYTES_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.SIGNATURE_FEE_USD;
import static com.hedera.services.bdd.suites.interledger.ClprTestHelpers.CLPR_SERVICE_ADDRESS_20;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.AUTHORIZATION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CLPR_CHANNEL_NOT_FOUND;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.NOT_SUPPORTED;

import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.spec.transactions.clpr.HapiClprUpdateLedgerConfiguration;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * HapiTests that close the unit-test ↔ live-HAPI gap on CLPR fee charging.
 *
 * <p>The genesis fee schedule prices each CLPR transaction per the CLPR fee proposal, and gives most of them a
 * {@code failureFee}: the flat service fee a failed transaction pays in place of its base fee and extras.
 * {@code ClprFeeCalculatorTest} and {@code ClprGenesisFeeScheduleTest} verify the calculator and schedule
 * independently. These tests exercise the wire and confirm the USD actually charged.
 *
 * <p>Coverage:
 * <ul>
 *   <li>{@code registerChannel} — success path; the permissionless commit only needs CLPR enabled, and pays the
 *       full $100 base fee.</li>
 *   <li>{@code submitBundle} and {@code registerConnector} — failure paths against an unknown channel. Pre-check
 *       passes and the handler throws {@code CLPR_CHANNEL_NOT_FOUND}, so the payer is charged the $0.01 failure
 *       fee instead of the usual service fee.</li>
 *   <li>{@code updateLedgerConfiguration} and {@code closeChannel} — a non-admin payer fails authorization at
 *       consensus. The admin-only transactions are priced at $0, so the failure fee is what a non-admin pays:
 *       $0.01 for a ledger configuration update, and nothing beyond the node and network fees for a close.</li>
 *   <li>Reveal-phase ops ({@code completeChannel}, {@code completeConnector}) are not covered here because they
 *       require a deployed verifier/connector smart contract and a full two-phase lifecycle setup.</li>
 * </ul>
 *
 * <p>Tests pay with a freshly-created account rather than {@code GENESIS}: the treasury is
 * fee-exempt at the record level, so a GENESIS-paid record always shows zero fees and
 * cannot validate the charge. The account must afford the $100 success price even where the
 * transaction fails, since the failure is only known after the payer is checked.
 *
 * <p>{@code fees.simpleFeesEnabled} is pinned {@code true} per test, so that if the network default ever
 * flips, the suite does not silently assert {@link #expectedClprTotalUsd} against the wrong fee model.
 *
 * <p>Tolerance is {@value #ALLOWED_PERCENT_DIFF}%, the same tight value used by the
 * HIP-1261 simple-fees suites ({@link
 * com.hedera.services.bdd.suites.hip1261.CryptoCreateSimpleFeesEmbeddedTest}). That works
 * because the expected USD is the <em>full</em> total (service + node + network),
 * computed from {@code SimpleFeesScheduleConstantsInUsd} via
 * {@link #expectedClprTotalUsd(double, long, int)} — not a service-only target with a
 * fudge-factor tolerance.
 */
@Tag(CLPR)
public class ClprBaseFeeSuite {

    private static final String PAYER = "feePayer";

    /** {@code ClprRegisterChannel} base fee from genesis simpleFeesSchedules.json. */
    private static final double REGISTER_CHANNEL_FEE_USD = 100.0;

    /** The {@code failureFee} genesis simpleFeesSchedules.json gives CLPR transactions. */
    private static final double CLPR_FAILURE_FEE_USD = 0.01;

    /** Matches the HIP-1261 simple-fees convention. Tight, because the expected USD is precise. */
    private static final double ALLOWED_PERCENT_DIFF = 0.1;

    /** Test payer always provides exactly one key. */
    private static final long PAYER_SIGNATURES = 1L;

    @LeakyHapiTest(requirement = PROPERTY_OVERRIDES)
    final Stream<DynamicTest> failedSubmitBundleChargesFailureFee() {
        final var txn = "clprSubmitBundleFailureFeeTxn";
        return hapiTest(
                overriding("fees.simpleFeesEnabled", "true"),
                cryptoCreate(PAYER).balance(ONE_MILLION_HBARS),
                clprSubmitBundle()
                        .channelId(new byte[32])
                        .bundlePayload(new byte[] {1})
                        .endpointNodeId(0L)
                        .payingWith(PAYER)
                        .via(txn)
                        .hasKnownStatus(CLPR_CHANNEL_NOT_FOUND),
                validateChargedUsdFromRecordWithTxnSize(
                        txn,
                        txnSize -> expectedClprTotalUsd(CLPR_FAILURE_FEE_USD, PAYER_SIGNATURES, txnSize),
                        ALLOWED_PERCENT_DIFF));
    }

    @LeakyHapiTest(requirement = PROPERTY_OVERRIDES)
    final Stream<DynamicTest> registerChannelChargesConfiguredBaseFee() {
        final var txn = "clprRegisterChannelBaseFeeTxn";
        return hapiTest(
                overriding("fees.simpleFeesEnabled", "true"),
                cryptoCreate(PAYER).balance(ONE_MILLION_HBARS),
                clprRegisterChannel()
                        .ownershipCommitment(commitment((byte) 0x01))
                        .payingWith(PAYER)
                        .via(txn),
                validateChargedUsdFromRecordWithTxnSize(
                        txn,
                        txnSize -> expectedClprTotalUsd(REGISTER_CHANNEL_FEE_USD, PAYER_SIGNATURES, txnSize),
                        ALLOWED_PERCENT_DIFF));
    }

    @LeakyHapiTest(requirement = PROPERTY_OVERRIDES)
    final Stream<DynamicTest> failedRegisterConnectorChargesFailureFee() {
        final var txn = "clprRegisterConnectorFailureFeeTxn";
        return hapiTest(
                overriding("fees.simpleFeesEnabled", "true"),
                cryptoCreate(PAYER).balance(ONE_MILLION_HBARS),
                clprRegisterConnector()
                        .channelId(new byte[32])
                        .commitment(commitment((byte) 0x02))
                        .payingWith(PAYER)
                        .via(txn)
                        .hasKnownStatus(CLPR_CHANNEL_NOT_FOUND),
                validateChargedUsdFromRecordWithTxnSize(
                        txn,
                        txnSize -> expectedClprTotalUsd(CLPR_FAILURE_FEE_USD, PAYER_SIGNATURES, txnSize),
                        ALLOWED_PERCENT_DIFF));
    }

    @LeakyHapiTest(requirement = PROPERTY_OVERRIDES)
    final Stream<DynamicTest> nonAdminLedgerConfigurationUpdateChargesFailureFee() {
        final var txn = "clprUpdateLedgerConfigurationFailureFeeTxn";
        return hapiTest(
                overriding("fees.simpleFeesEnabled", "true"),
                cryptoCreate(PAYER).balance(ONE_MILLION_HBARS),
                clprUpdateLedgerConfiguration()
                        .serviceAddress(CLPR_SERVICE_ADDRESS_20)
                        .throttles(HapiClprUpdateLedgerConfiguration.defaultThrottles())
                        .payingWith(PAYER)
                        .via(txn)
                        .hasKnownStatusFrom(AUTHORIZATION_FAILED, NOT_SUPPORTED),
                validateChargedUsdFromRecordWithTxnSize(
                        txn,
                        txnSize -> expectedClprTotalUsd(CLPR_FAILURE_FEE_USD, PAYER_SIGNATURES, txnSize),
                        ALLOWED_PERCENT_DIFF));
    }

    @LeakyHapiTest(requirement = PROPERTY_OVERRIDES)
    final Stream<DynamicTest> nonAdminCloseChannelChargesOnlyNodeAndNetworkFees() {
        final var txn = "clprCloseChannelFailureFeeTxn";
        return hapiTest(
                overriding("fees.simpleFeesEnabled", "true"),
                cryptoCreate(PAYER).balance(ONE_MILLION_HBARS),
                clprCloseChannel()
                        .channelId(new byte[32])
                        .payingWith(PAYER)
                        .via(txn)
                        .hasKnownStatusFrom(AUTHORIZATION_FAILED, NOT_SUPPORTED),
                validateChargedUsdFromRecordWithTxnSize(
                        txn, txnSize -> expectedClprTotalUsd(0.0, PAYER_SIGNATURES, txnSize), ALLOWED_PERCENT_DIFF));
    }

    /**
     * Expected total USD charge = service fee + node fee + network fee.
     *
     * <p>Mirrors the formula in {@code FeesChargingUtils.expectedNodeAndNetworkFeeUsd}
     * (which is package-private and per-op-specific) but parameterised on the CLPR service
     * fee. Node fee includes a signature-count overage if {@code sigs > NODE_INCLUDED_SIGNATURES}
     * and a byte-count overage if {@code txnSize > NODE_INCLUDED_BYTES}; the network fee is
     * {@code nodeFee × NETWORK_MULTIPLIER}.
     */
    private static double expectedClprTotalUsd(final double serviceFeeUsd, final long sigs, final int txnSize) {
        final double sigExtras = Math.max(0, sigs - NODE_INCLUDED_SIGNATURES) * SIGNATURE_FEE_USD;
        final double bytesExtras = Math.max(0, txnSize - NODE_INCLUDED_BYTES) * PROCESSING_BYTES_FEE_USD;
        final double nodeFee = NODE_BASE_FEE_USD + sigExtras + bytesExtras;
        final double networkFee = nodeFee * NETWORK_MULTIPLIER;
        return serviceFeeUsd + nodeFee + networkFee;
    }

    /** A 32-byte commitment whose first byte distinguishes it from other tests in this suite. */
    private static byte[] commitment(final byte tag) {
        final var bytes = new byte[32];
        bytes[0] = tag;
        return bytes;
    }
}
