// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.ethereum;

import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.ethereumCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromAccountToAlias;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsdWithin;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.RELAYER;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SHAPE;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SOURCE_KEY;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.signedTxnSizeFor;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.NETWORK_MULTIPLIER;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.NODE_AND_NETWORK_BASE_FEE;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.NODE_INCLUDED_BYTES;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.PROCESSING_BYTES_FEE_USD;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ETHEREUM_TRANSACTION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSACTION_OVERSIZE;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.node.app.hapi.utils.ethereum.EthTxData;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Verifies pre-handle handling of a file-referenced {@code EthereumTransaction} with
 * {@code jumboTransactions.chargeHydratedCallDataSize} enabled.
 *
 * <p>A file-referenced {@code EthereumTransaction} carries an empty inline {@code callData} plus a
 * {@code FileID} on the wire; the referenced file is read and hex-decoded at consensus pre-handle (on
 * every node). With the flag enabled, pre-handle first enforces the callData size ceiling and the
 * EIP-7623 gas floor on the hydrated payload and only then recovers the signature; a transaction that
 * fails either step produces a charged consensus record that consumes no gas, with the payer charged
 * the non-refundable node and network fee metered on the hydrated callData size — exactly as an inline
 * payload of the same size would be charged.
 */
@Tag(SMART_CONTRACT)
@SuppressWarnings("java:S5960") // assertions in production test code are intentional
public class EthereumFileCallDataPreHandleFailureTest {
    private static final String CONTRACT = "CalldataSize";
    private static final String FUNCTION = "callme";
    private static final String CHARGE_HYDRATED = "jumboTransactions.chargeHydratedCallDataSize";
    private static final String MAX_BYTES_PER_SEC = "jumboTransactions.maxBytesPerSec";
    private static final String FILES_MAX_SIZE_KB = "files.maxSizeKb";

    // ABI encoding of callme(bytes): 4-byte selector, offset word, length word, then the 32-byte-aligned payload
    private static final int ABI_HEAD_BYTES = 4 + 32 + 32;

    @LeakyHapiTest(overrides = {CHARGE_HYDRATED})
    @DisplayName(
            "File-referenced eth txn is fully hydrated then fails pre-handle: no gas, fee metered on hydrated size")
    final Stream<DynamicTest> fileCallDataIsHydratedThenFailsPreHandleWithoutGas() {
        final var payload = new byte[127 * 1024];
        final var hydratedCallDataBytes = ABI_HEAD_BYTES + payload.length;
        return hapiTest(
                overriding(CHARGE_HYDRATED, "true"),
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(RELAYER).balance(ONE_MILLION_HBARS),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS)),
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT),
                // file-reference path: uploads hex to HFS, empties inline callData, sets FileID;
                // malformed (zeroed s) -> INVALID_ETHEREUM_TRANSACTION at recovery, after hydration
                ethereumCall(CONTRACT, FUNCTION, (Object) payload)
                        .type(EthTxData.EthTransactionType.EIP1559)
                        .createCallDataFile()
                        .withMalformedSignature()
                        .signingWith(SECP_256K1_SOURCE_KEY)
                        .payingWith(RELAYER)
                        // offered fee must cover the byte-metered node + network fee at ingest
                        .fee(ONE_HUNDRED_HBARS)
                        .nonce(0)
                        // must clear the EIP-7623 floor pre-handle now enforces on the hydrated payload
                        // (~1.32M gas for ~130 KiB of mostly-zero callData), so recovery is actually reached
                        .gasLimit(1_500_000L)
                        .via("failedEthTxn")
                        // (A) consensus failure (known status), not a precheck -> proves it reached pre-handle
                        .hasKnownStatus(INVALID_ETHEREUM_TRANSACTION)
                        // (B) never executed -> no gas consumed
                        .exposingGasTo((_, gasUsed) ->
                                assertEquals(0L, gasUsed, "pre-handle-failed eth txn must not consume gas")),
                // (C) payer charged a non-zero, non-refundable fee (node + network fee)
                getTxnRecord("failedEthTxn").hasPriority(recordWith().feeGreaterThan(0L)),
                // (C) metered on the hydrated callData size (~$1.29), not just the tiny on-wire size
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        validateChargedUsdWithin(
                                "failedEthTxn",
                                expectedPreHandleFailureFeeUsd(
                                        signedTxnSizeFor(spec, "failedEthTxn"), hydratedCallDataBytes),
                                1))));
    }

    /**
     * The ~512 KiB variant: a payload far above the {@code ethereumMaxCallDataSize} (128 KiB) ceiling that
     * can only be delivered by file reference (inline it could never be submitted). The jumbo bytes bucket
     * is widened so ingest admits it, at which point pre-handle rejects it with {@code TRANSACTION_OVERSIZE}
     * on the hydrated size — before signature recovery, so the malformed signature is never reached — and
     * the payer is still charged the byte-metered node and network fee for the payload that was hydrated.
     */
    @LeakyHapiTest(overrides = {FILES_MAX_SIZE_KB, CHARGE_HYDRATED, MAX_BYTES_PER_SEC})
    @DisplayName("~512 KiB file callData is rejected at pre-handle as oversize: no gas, fee metered on hydrated size")
    final Stream<DynamicTest> oversizeFileCallDataIsRejectedAtPreHandle() {
        final var payload = new byte[512 * 1024]; // -> ~1 MiB of hex in HFS
        final var hydratedCallDataBytes = ABI_HEAD_BYTES + payload.length;
        return hapiTest(
                overriding(FILES_MAX_SIZE_KB, "2048"), // 512 KiB -> ~1 MiB hex exceeds the 1024 KiB default
                overriding(CHARGE_HYDRATED, "true"),
                // 1 MiB/sec bucket so the ~512 KiB hydrated size passes the ingest bytes throttle
                overriding(MAX_BYTES_PER_SEC, String.valueOf(1024 * 1024)),
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(RELAYER).balance(ONE_MILLION_HBARS),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS)),
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT),
                ethereumCall(CONTRACT, FUNCTION, (Object) payload)
                        .type(EthTxData.EthTransactionType.EIP1559)
                        .createCallDataFile()
                        .withMalformedSignature() // never reached: the size ceiling fails first
                        .signingWith(SECP_256K1_SOURCE_KEY)
                        .payingWith(RELAYER)
                        .fee(ONE_HUNDRED_HBARS)
                        .nonce(0)
                        .gasLimit(1_500_000L)
                        .via("oversizeEthTxn")
                        .hasKnownStatus(TRANSACTION_OVERSIZE)
                        .exposingGasTo((_, gasUsed) ->
                                assertEquals(0L, gasUsed, "pre-handle-failed eth txn must not consume gas")),
                getTxnRecord("oversizeEthTxn").hasPriority(recordWith().feeGreaterThan(0L)),
                // ~$5.23: the byte fee tracks the ~512 KiB hydrated payload, not the (empty) on-wire callData
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        validateChargedUsdWithin(
                                "oversizeEthTxn",
                                expectedPreHandleFailureFeeUsd(
                                        signedTxnSizeFor(spec, "oversizeEthTxn"), hydratedCallDataBytes),
                                1))));
    }

    /**
     * The node + network fee (no service fee) charged for a pre-handle failure once the hydrated callData
     * counts toward {@code PROCESSING_BYTES} alongside the on-wire bytes of the signed transaction itself.
     */
    private static double expectedPreHandleFailureFeeUsd(final long signedTxnBytes, final long hydratedCallDataBytes) {
        final long chargedBytes = Math.max(0, signedTxnBytes + hydratedCallDataBytes - NODE_INCLUDED_BYTES);
        return NODE_AND_NETWORK_BASE_FEE + chargedBytes * PROCESSING_BYTES_FEE_USD * (1 + NETWORK_MULTIPLIER);
    }
}
