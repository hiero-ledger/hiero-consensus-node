// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1261;

import static com.hedera.services.bdd.junit.ContextRequirement.PROPERTY_OVERRIDES;
import static com.hedera.services.bdd.junit.TestTags.SIMPLE_FEES;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.keys.KeyShape.threshOf;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileAppend;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.submitMessageTo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertionsHold;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.getChargedUsedForInnerTxn;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyListNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedAccount;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedAtomicBatchFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedCryptoCreateFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedFileAppendFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedFileCreateFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedFileUpdateFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenMintNftFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTopicSubmitMessageFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.signedInnerTxnSizeFor;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.signedTxnSizeFor;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.validateChargedUsdWithinWithTxnSize;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.validateInnerChargedUsdWithinWithTxnSize;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.NODE_INCLUDED_BYTES;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BATCH_TRANSACTION_IN_BLACKLIST;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.RECORD_NOT_FOUND;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;
import static org.hiero.hapi.support.fees.Extra.PROCESSING_BYTES;
import static org.hiero.hapi.support.fees.Extra.SIGNATURES;
import static org.hiero.hapi.support.fees.Extra.STATE_BYTES;
import static org.hiero.hapi.support.fees.Extra.TOKEN_MINT_NFT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.transactions.token.HapiTokenCreate;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;

@Tag(SIMPLE_FEES)
@HapiTestLifecycle
public class AtomicBatchBoundarySimpleFeesTest {
    private static final String BATCH_OPERATOR = "batchOperator";
    private static final String PAYER = "payer";
    private static final String OWNER = "owner";
    private static final String RECEIVER_ASSOCIATED_FIRST = "receiverAssociatedFirst";
    private static final String RECEIVER_ASSOCIATED_SECOND = "receiverAssociatedSecond";
    private static final String adminKey = "adminKey";
    private static final String submitKey = "submitKey";
    private static final String supplyKey = "supplyKey";
    private static final String NON_FUNGIBLE_TOKEN = "nonFungibleToken";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of("fees.simpleFeesEnabled", "true"));
    }

    @Nested
    @DisplayName("Outer Batch PROCESSING_BYTES Scaling Scenarios")
    class OuterBatchProcessingBytesScalingScenarios {
        @HapiTest
        @DisplayName("Atomic Batch with 1 inner txn vs 10 inner txns — outer batch fee scales with PROCESSING_BYTES")
        final Stream<DynamicTest> outerBatchFeeScalesWithProcessingBytesAsInnerTxnsIncrease() {
            return hapiTest(flattened(
                    createAccountsAndKeys(),

                    // batch 1 - 1 inner txn, inner payer = PAYER, batch payer = BATCH_OPERATOR
                    atomicBatch(cryptoCreate("singleInnerAccount")
                                    .payingWith(PAYER)
                                    .signedBy(PAYER)
                                    .via("singleInnerTxn")
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR)
                            .signedBy(BATCH_OPERATOR)
                            .via("singleInnerBatchTxn"),
                    validateChargedAccount("singleInnerBatchTxn", BATCH_OPERATOR),
                    validateChargedAccount("singleInnerTxn", PAYER),

                    // validate first outer batch fee
                    validateChargedUsdWithinWithTxnSize(
                            "singleInnerBatchTxn",
                            txnSize -> expectedAtomicBatchFullFeeUsd(
                                    Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),

                    // batch 2 - 10 inner txns, same inner txn payer = PAYER, same outer batch payer = BATCH_OPERATOR
                    atomicBatch(
                                    cryptoCreate("innerAccount1")
                                            .payingWith(PAYER)
                                            .signedBy(PAYER)
                                            .via("innerTxn1")
                                            .batchKey(BATCH_OPERATOR),
                                    cryptoCreate("innerAccount2")
                                            .payingWith(PAYER)
                                            .signedBy(PAYER)
                                            .via("innerTxn2")
                                            .batchKey(BATCH_OPERATOR),
                                    cryptoCreate("innerAccount3")
                                            .payingWith(PAYER)
                                            .signedBy(PAYER)
                                            .via("innerTxn3")
                                            .batchKey(BATCH_OPERATOR),
                                    cryptoCreate("innerAccount4")
                                            .payingWith(PAYER)
                                            .signedBy(PAYER)
                                            .via("innerTxn4")
                                            .batchKey(BATCH_OPERATOR),
                                    cryptoCreate("innerAccount5")
                                            .payingWith(PAYER)
                                            .signedBy(PAYER)
                                            .via("innerTxn5")
                                            .batchKey(BATCH_OPERATOR),
                                    cryptoCreate("innerAccount6")
                                            .payingWith(PAYER)
                                            .signedBy(PAYER)
                                            .via("innerTxn6")
                                            .batchKey(BATCH_OPERATOR),
                                    cryptoCreate("innerAccount7")
                                            .payingWith(PAYER)
                                            .signedBy(PAYER)
                                            .via("innerTxn7")
                                            .batchKey(BATCH_OPERATOR),
                                    cryptoCreate("innerAccount8")
                                            .payingWith(PAYER)
                                            .signedBy(PAYER)
                                            .via("innerTxn8")
                                            .batchKey(BATCH_OPERATOR),
                                    cryptoCreate("innerAccount9")
                                            .payingWith(PAYER)
                                            .signedBy(PAYER)
                                            .via("innerTxn9")
                                            .batchKey(BATCH_OPERATOR),
                                    cryptoCreate("innerAccount10")
                                            .payingWith(PAYER)
                                            .signedBy(PAYER)
                                            .via("innerTxn10")
                                            .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR)
                            .signedBy(BATCH_OPERATOR)
                            .via("tenInnerBatchTxn"),
                    validateChargedAccount("tenInnerBatchTxn", BATCH_OPERATOR),
                    validateChargedAccount("innerTxn10", PAYER),

                    // validate second outer batch fee
                    validateChargedUsdWithinWithTxnSize(
                            "tenInnerBatchTxn",
                            txnSize -> expectedAtomicBatchFullFeeUsd(
                                    Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),

                    // assert the 10-inner batch body is larger than the 1-inner batch body,
                    // and that the expected outer fee is higher
                    assertionsHold((spec, assertLog) -> {
                        final int singleInnerSize = signedTxnSizeFor(spec, "singleInnerBatchTxn");
                        final int tenInnerSize = signedTxnSizeFor(spec, "tenInnerBatchTxn");
                        assertLog.info(
                                "1-inner outer batch signed size: {} bytes, 10-inner outer batch signed size: {} bytes",
                                singleInnerSize,
                                tenInnerSize);
                        assertTrue(
                                tenInnerSize > singleInnerSize,
                                "10-inner outer batch txn (" + tenInnerSize + " bytes) should be larger than 1-inner ("
                                        + singleInnerSize + " bytes)");

                        final double fee1 = expectedAtomicBatchFullFeeUsd(
                                Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) singleInnerSize));
                        final double fee2 = expectedAtomicBatchFullFeeUsd(
                                Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) tenInnerSize));
                        assertLog.info("Expected outer fee — 1-inner: {} USD, 10-inner: {} USD", fee1, fee2);
                        assertTrue(
                                fee2 > fee1,
                                "Expected outer fee for 10-inner batch (" + fee2 + ") should exceed 1-inner batch fee ("
                                        + fee1 + ")");
                    })));
        }

        @HapiTest
        @DisplayName(
                "Atomic Batch outer txn with more than 1025 bytes txn body — outer fee includes extra PROCESSING_BYTES - batch payer charged correctly")
        final Stream<DynamicTest> outerBatchFeeIncludesExtraProcessingBytesWhenBodyExceedsThreshold() {
            return hapiTest(flattened(
                    createAccountsAndKeys(),
                    // AtomicBatch txn with 5 inner txns above the NODE_INCLUDED_BYTES (1024) threshold;
                    // inner payer = PAYER, batch payer = BATCH_OPERATOR (different accounts)
                    atomicBatch(
                                    cryptoCreate("largeInnerAccount1")
                                            .payingWith(PAYER)
                                            .signedBy(PAYER)
                                            .via("largeInner1")
                                            .batchKey(BATCH_OPERATOR),
                                    cryptoCreate("largeInnerAccount2")
                                            .payingWith(PAYER)
                                            .signedBy(PAYER)
                                            .via("largeInner2")
                                            .batchKey(BATCH_OPERATOR),
                                    cryptoCreate("largeInnerAccount3")
                                            .payingWith(PAYER)
                                            .signedBy(PAYER)
                                            .via("largeInner3")
                                            .batchKey(BATCH_OPERATOR),
                                    cryptoCreate("largeInnerAccount4")
                                            .payingWith(PAYER)
                                            .signedBy(PAYER)
                                            .via("largeInner4")
                                            .batchKey(BATCH_OPERATOR),
                                    cryptoCreate("largeInnerAccount5")
                                            .payingWith(PAYER)
                                            .signedBy(PAYER)
                                            .via("largeInner5")
                                            .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR)
                            .signedBy(BATCH_OPERATOR)
                            .via("largeBatchTxn"),
                    validateChargedAccount("largeBatchTxn", BATCH_OPERATOR),
                    validateChargedAccount("largeInner1", PAYER),

                    // assert the outer signed txn has crossed the PROCESSING_BYTES threshold
                    assertionsHold((spec, assertLog) -> {
                        final int outerSize = signedTxnSizeFor(spec, "largeBatchTxn");
                        assertLog.info(
                                "Outer batch signed size: {} bytes (NODE_INCLUDED_BYTES threshold: {})",
                                outerSize,
                                NODE_INCLUDED_BYTES);
                        assertTrue(
                                outerSize > NODE_INCLUDED_BYTES,
                                "Expected outer batch txn size (" + outerSize
                                        + ") to exceed NODE_INCLUDED_BYTES threshold (" + NODE_INCLUDED_BYTES + ")");
                    }),

                    // validate outer batch fee includes the PROCESSING_BYTES extra
                    validateChargedUsdWithinWithTxnSize(
                            "largeBatchTxn",
                            txnSize -> expectedAtomicBatchFullFeeUsd(
                                    Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),

                    // validate inner payer (PAYER) is charged only the inner txn fee
                    validateInnerChargedUsdWithinWithTxnSize(
                            "largeInner1",
                            "largeBatchTxn",
                            txnSize -> expectedCryptoCreateFullFeeUsd(
                                    Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                            0.1)));
        }
    }

    @Nested
    @DisplayName("Inner Batch STATE_BYTES Boundaries - File Inner Scenarios")
    class InnerBatchStateBytesBoundariesFileInnerScenarios {
        @HapiTest
        @DisplayName("File Create inner txn with 999 bytes — no STATE_BYTES extra - Base fees charged only")
        final Stream<DynamicTest> fileCreateInner999BytesNoStateBytesExtraBaseFeesCharged() {
            final byte[] content = new byte[999];
            Arrays.fill(content, (byte) 'a');
            return hapiTest(flattened(
                    createAccountsAndKeys(),
                    newKeyListNamed("WACL", List.of(PAYER)),
                    atomicBatch(fileCreate("file999")
                                    .key("WACL")
                                    .contents(content)
                                    .payingWith(PAYER)
                                    .signedBy(PAYER)
                                    .via("innerFileCreate999")
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR)
                            .signedBy(BATCH_OPERATOR)
                            .via("batchTxn999"),
                    validateChargedAccount("batchTxn999", BATCH_OPERATOR),
                    validateChargedAccount("innerFileCreate999", PAYER),
                    validateChargedUsdWithinWithTxnSize(
                            "batchTxn999",
                            txnSize -> expectedAtomicBatchFullFeeUsd(
                                    Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateInnerChargedUsdWithinWithTxnSize(
                            "innerFileCreate999",
                            "batchTxn999",
                            txnSize -> expectedFileCreateFullFeeUsd(Map.of(
                                    SIGNATURES, 1L,
                                    STATE_BYTES, 999L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1)));
        }

        @HapiTest
        @DisplayName("File Create inner txn with 1000 bytes — at STATE_BYTES threshold - Base fees charged only")
        final Stream<DynamicTest> fileCreateInner1000BytesAtStateBytesExtraThresholdBaseFeesCharged() {
            final byte[] content = new byte[1000];
            Arrays.fill(content, (byte) 'a');
            return hapiTest(flattened(
                    createAccountsAndKeys(),
                    newKeyListNamed("WACL", List.of(PAYER)),
                    atomicBatch(fileCreate("file1000")
                                    .key("WACL")
                                    .contents(content)
                                    .payingWith(PAYER)
                                    .signedBy(PAYER)
                                    .via("innerFileCreate1000")
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR)
                            .signedBy(BATCH_OPERATOR)
                            .via("batchTxn1000"),
                    validateChargedAccount("batchTxn1000", BATCH_OPERATOR),
                    validateChargedAccount("innerFileCreate1000", PAYER),
                    validateChargedUsdWithinWithTxnSize(
                            "batchTxn1000",
                            txnSize -> expectedAtomicBatchFullFeeUsd(
                                    Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateInnerChargedUsdWithinWithTxnSize(
                            "innerFileCreate1000",
                            "batchTxn1000",
                            txnSize -> expectedFileCreateFullFeeUsd(Map.of(
                                    SIGNATURES, 1L,
                                    STATE_BYTES, 1000L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1)));
        }

        @HapiTest
        @DisplayName("File Create inner txn with 1001 bytes — 1 extra STATE_BYTE charged")
        final Stream<DynamicTest> fileCreateInner1001BytesStateBytesExtraCharged() {
            final byte[] content = new byte[1001];
            Arrays.fill(content, (byte) 'a');
            return hapiTest(flattened(
                    createAccountsAndKeys(),
                    newKeyListNamed("WACL", List.of(PAYER)),
                    atomicBatch(fileCreate("file1001")
                                    .key("WACL")
                                    .contents(content)
                                    .payingWith(PAYER)
                                    .signedBy(PAYER)
                                    .via("innerFileCreate1001")
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR)
                            .signedBy(BATCH_OPERATOR)
                            .via("batchTxn1001"),
                    validateChargedAccount("batchTxn1001", BATCH_OPERATOR),
                    validateChargedAccount("innerFileCreate1001", PAYER),
                    validateChargedUsdWithinWithTxnSize(
                            "batchTxn1001",
                            txnSize -> expectedAtomicBatchFullFeeUsd(
                                    Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateInnerChargedUsdWithinWithTxnSize(
                            "innerFileCreate1001",
                            "batchTxn1001",
                            txnSize -> expectedFileCreateFullFeeUsd(Map.of(
                                    SIGNATURES, 1L,
                                    STATE_BYTES, 1001L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1)));
        }

        @HapiTest
        @DisplayName("File Update inner txn with 1001 bytes — 1 extra STATE_BYTE charged")
        final Stream<DynamicTest> fileUpdateInner1001BytesStateBytesExtraCharged() {
            final byte[] content = new byte[1001];
            Arrays.fill(content, (byte) 'a');
            return hapiTest(flattened(
                    createAccountsAndKeys(),
                    newKeyListNamed("WACL", List.of(PAYER)),
                    fileCreate("fileToUpdate")
                            .key("WACL")
                            .contents("original")
                            .payingWith(PAYER)
                            .signedBy(PAYER),
                    atomicBatch(fileUpdate("fileToUpdate")
                                    .contents(content)
                                    .payingWith(PAYER)
                                    .signedBy(PAYER)
                                    .via("innerFileUpdate1001")
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR)
                            .signedBy(BATCH_OPERATOR)
                            .via("batchTxnUpdate1001"),
                    validateChargedAccount("batchTxnUpdate1001", BATCH_OPERATOR),
                    validateChargedAccount("innerFileUpdate1001", PAYER),
                    validateChargedUsdWithinWithTxnSize(
                            "batchTxnUpdate1001",
                            txnSize -> expectedAtomicBatchFullFeeUsd(
                                    Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateInnerChargedUsdWithinWithTxnSize(
                            "innerFileUpdate1001",
                            "batchTxnUpdate1001",
                            txnSize -> expectedFileUpdateFullFeeUsd(Map.of(
                                    SIGNATURES, 1L,
                                    STATE_BYTES, 1001L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            1)));
        }

        @HapiTest
        @DisplayName("File Append inner txn with 1001 bytes — 1 extra STATE_BYTE charged")
        final Stream<DynamicTest> fileAppendInner1001BytesStateBytesExtraCharged() {
            final byte[] content = new byte[1001];
            Arrays.fill(content, (byte) 'a');
            return hapiTest(flattened(
                    createAccountsAndKeys(),
                    newKeyListNamed("WACL", List.of(PAYER)),
                    fileCreate("fileToAppend")
                            .key("WACL")
                            .contents("original")
                            .payingWith(PAYER)
                            .signedBy(PAYER),
                    atomicBatch(fileAppend("fileToAppend")
                                    .content(content)
                                    .payingWith(PAYER)
                                    .signedBy(PAYER)
                                    .via("innerFileAppend1001")
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR)
                            .signedBy(BATCH_OPERATOR)
                            .via("batchTxnAppend1001"),
                    validateChargedAccount("batchTxnAppend1001", BATCH_OPERATOR),
                    validateChargedAccount("innerFileAppend1001", PAYER),
                    validateChargedUsdWithinWithTxnSize(
                            "batchTxnAppend1001",
                            txnSize -> expectedAtomicBatchFullFeeUsd(
                                    Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateInnerChargedUsdWithinWithTxnSize(
                            "innerFileAppend1001",
                            "batchTxnAppend1001",
                            txnSize -> expectedFileAppendFullFeeUsd(Map.of(
                                    SIGNATURES, 1L,
                                    STATE_BYTES, 1001L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1)));
        }
    }

    @Nested
    @DisplayName("Inner Batch PROCESSING_BYTES Boundaries - Inner Scenarios")
    class InnerBatchProcessingBytesBoundariesInnerScenarios {
        @HapiTest
        @DisplayName(
                "Inner txn just below PROCESSING_BYTES threshold (< 1024 bytes) — no PROCESSING_BYTES extra on inner payer; batch payer unaffected")
        final Stream<DynamicTest> innerTxnJustBelowProcessingBytesThresholdNoExtraCharge() {
            final long TARGET = NODE_INCLUDED_BYTES - 1; // 1023
            return hapiTest(flattened(
                    createAccountsAndKeys(),
                    newKeyNamed(adminKey),
                    newKeyNamed(submitKey),
                    createTopic("processingBytesTopic1").adminKeyName(adminKey).submitKeyName(submitKey),
                    withOpContext((spec, log) -> {
                        // Create reference inner transaction with base inner txn size
                        allRunFor(
                                spec,
                                atomicBatch(submitMessageTo("processingBytesTopic1")
                                                .message("probe")
                                                .payingWith(PAYER)
                                                .signedBy(PAYER, submitKey)
                                                .via("baseFeeTxn")
                                                .batchKey(BATCH_OPERATOR))
                                        .payingWith(BATCH_OPERATOR)
                                        .signedBy(BATCH_OPERATOR)
                                        .via("baseFeeBatch"));
                        final int baseSize = signedInnerTxnSizeFor(spec, "baseFeeTxn");

                        // Create inner txn with txn size right below the threshold
                        final int probeMessageLength = "probe".length();
                        final int paddingLength = Math.toIntExact(Math.max(0, TARGET - baseSize - 3));
                        final String message = "X".repeat(paddingLength + probeMessageLength);

                        allRunFor(
                                spec,
                                atomicBatch(submitMessageTo("processingBytesTopic1")
                                                .message(message)
                                                .payingWith(PAYER)
                                                .signedBy(PAYER, submitKey)
                                                .via("innerBelowThreshold")
                                                .batchKey(BATCH_OPERATOR))
                                        .payingWith(BATCH_OPERATOR)
                                        .signedBy(BATCH_OPERATOR)
                                        .via("batchBelowThreshold"));

                        final int actualInnerSize = signedInnerTxnSizeFor(spec, "innerBelowThreshold");
                        log.info(
                                "Below-threshold inner size: {} bytes (target < {})",
                                actualInnerSize,
                                NODE_INCLUDED_BYTES);
                        assertTrue(
                                actualInnerSize < NODE_INCLUDED_BYTES,
                                "Expected inner size (" + actualInnerSize + ") to be below NODE_INCLUDED_BYTES ("
                                        + NODE_INCLUDED_BYTES + ")");

                        // Validate fees charged
                        allRunFor(
                                spec,
                                validateChargedAccount("batchBelowThreshold", BATCH_OPERATOR),
                                validateChargedAccount("innerBelowThreshold", PAYER),
                                validateChargedUsdWithinWithTxnSize(
                                        "batchBelowThreshold",
                                        txnSize -> expectedAtomicBatchFullFeeUsd(
                                                Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                        0.1),
                                validateInnerChargedUsdWithinWithTxnSize(
                                        "innerBelowThreshold",
                                        "batchBelowThreshold",
                                        txnSize -> expectedTopicSubmitMessageFullFeeUsd(Map.of(
                                                SIGNATURES, 2L,
                                                STATE_BYTES, (long) message.length(),
                                                PROCESSING_BYTES, (long) txnSize)),
                                        0.1));
                    })));
        }

        @HapiTest
        @DisplayName(
                "Inner txn at PROCESSING_BYTES threshold — boundary behavior; no PROCESSING_BYTES extra; batch payer unaffected")
        final Stream<DynamicTest> innerTxnAtProcessingBytesThresholdBoundaryBehavior() {
            return hapiTest(flattened(
                    createAccountsAndKeys(),
                    newKeyNamed(adminKey),
                    newKeyNamed(submitKey),
                    createTopic("processingBytesTopic2").adminKeyName(adminKey).submitKeyName(submitKey),
                    withOpContext((spec, log) -> {
                        // Create reference inner transaction with base inner txn size
                        allRunFor(
                                spec,
                                atomicBatch(submitMessageTo("processingBytesTopic2")
                                                .message("probe")
                                                .payingWith(PAYER)
                                                .signedBy(PAYER, submitKey)
                                                .via("baseFeeTxn")
                                                .batchKey(BATCH_OPERATOR))
                                        .payingWith(BATCH_OPERATOR)
                                        .signedBy(BATCH_OPERATOR)
                                        .via("baseFeeBatch"));
                        final int baseSize = signedInnerTxnSizeFor(spec, "baseFeeTxn");

                        // Create inner txn with txn size at the threshold
                        final int probeMessageLength = "probe".length();
                        final int paddingLength = Math.toIntExact(Math.max(0, NODE_INCLUDED_BYTES - baseSize - 3));
                        final String message = "X".repeat(paddingLength + probeMessageLength);

                        allRunFor(
                                spec,
                                atomicBatch(submitMessageTo("processingBytesTopic2")
                                                .message(message)
                                                .payingWith(PAYER)
                                                .signedBy(PAYER, submitKey)
                                                .via("innerAtThreshold")
                                                .batchKey(BATCH_OPERATOR))
                                        .payingWith(BATCH_OPERATOR)
                                        .signedBy(BATCH_OPERATOR)
                                        .via("batchAtThreshold"));

                        final int actualInnerSize = signedInnerTxnSizeFor(spec, "innerAtThreshold");
                        log.info(
                                "At-threshold inner size: {} bytes (target = {})",
                                actualInnerSize,
                                NODE_INCLUDED_BYTES);
                        assertTrue(
                                actualInnerSize <= NODE_INCLUDED_BYTES,
                                "Expected inner size (" + actualInnerSize + ") to be at or below NODE_INCLUDED_BYTES ("
                                        + NODE_INCLUDED_BYTES + ")");

                        allRunFor(
                                spec,
                                validateChargedAccount("batchAtThreshold", BATCH_OPERATOR),
                                validateChargedAccount("innerAtThreshold", PAYER),
                                validateChargedUsdWithinWithTxnSize(
                                        "batchAtThreshold",
                                        txnSize -> expectedAtomicBatchFullFeeUsd(
                                                Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                        0.1),
                                validateInnerChargedUsdWithinWithTxnSize(
                                        "innerAtThreshold",
                                        "batchAtThreshold",
                                        txnSize -> expectedTopicSubmitMessageFullFeeUsd(Map.of(
                                                SIGNATURES, 2L,
                                                STATE_BYTES, (long) message.length(),
                                                PROCESSING_BYTES, (long) txnSize)),
                                        0.1));
                    })));
        }

        @HapiTest
        @DisplayName(
                "Inner txn above PROCESSING_BYTES threshold charges more to inner payer only - batch payer not affected")
        final Stream<DynamicTest> innerTxnAboveProcessingBytesThresholdChargesMoreInnerPayerOnly() {
            final long BELOW_TARGET = NODE_INCLUDED_BYTES - 10;
            final long ABOVE_TARGET = NODE_INCLUDED_BYTES + 20;

            return hapiTest(flattened(
                    createAccountsAndKeys(),
                    newKeyNamed(adminKey),
                    newKeyNamed(submitKey),
                    createTopic("processingBytesTopic3").adminKeyName(adminKey).submitKeyName(submitKey),
                    withOpContext((spec, log) -> {
                        // Create reference inner transaction with base inner txn size
                        allRunFor(
                                spec,
                                atomicBatch(submitMessageTo("processingBytesTopic3")
                                                .message("probe")
                                                .payingWith(PAYER)
                                                .signedBy(PAYER, submitKey)
                                                .via("baseFeeTxn")
                                                .batchKey(BATCH_OPERATOR))
                                        .payingWith(BATCH_OPERATOR)
                                        .signedBy(BATCH_OPERATOR)
                                        .via("baseFeeBatch"));
                        final int baseSize = signedInnerTxnSizeFor(spec, "baseFeeTxn");

                        final int probeMessageLength = "probe".length();

                        final int belowPadding =
                                Math.toIntExact(Math.max(0, BELOW_TARGET - baseSize - probeMessageLength));
                        final String belowMessage = "X".repeat(belowPadding + probeMessageLength);

                        final int abovePadding =
                                Math.toIntExact(Math.max(0, ABOVE_TARGET - baseSize - probeMessageLength));
                        final String aboveMessage = "X".repeat(abovePadding + probeMessageLength);

                        allRunFor(
                                spec,
                                atomicBatch(submitMessageTo("processingBytesTopic3")
                                                .message(belowMessage)
                                                .payingWith(PAYER)
                                                .signedBy(PAYER, submitKey)
                                                .via("innerBelowThreshold")
                                                .batchKey(BATCH_OPERATOR))
                                        .payingWith(BATCH_OPERATOR)
                                        .signedBy(BATCH_OPERATOR)
                                        .via("batchBelowThreshold"),
                                atomicBatch(submitMessageTo("processingBytesTopic3")
                                                .message(aboveMessage)
                                                .payingWith(PAYER)
                                                .signedBy(PAYER, submitKey)
                                                .via("innerAboveThreshold")
                                                .batchKey(BATCH_OPERATOR))
                                        .payingWith(BATCH_OPERATOR)
                                        .signedBy(BATCH_OPERATOR)
                                        .via("batchAboveThreshold"));

                        final int belowSize = signedInnerTxnSizeFor(spec, "innerBelowThreshold");
                        final int aboveSize = signedInnerTxnSizeFor(spec, "innerAboveThreshold");

                        log.info("Below-threshold inner size: {}", belowSize);
                        log.info("Above-threshold inner size: {}", aboveSize);

                        assertTrue(
                                belowSize < NODE_INCLUDED_BYTES,
                                "Expected below-threshold inner txn to stay below threshold");
                        assertTrue(
                                aboveSize > NODE_INCLUDED_BYTES,
                                "Expected above-threshold inner txn to exceed threshold");

                        allRunFor(
                                spec,
                                validateChargedAccount("batchBelowThreshold", BATCH_OPERATOR),
                                validateChargedAccount("batchAboveThreshold", BATCH_OPERATOR),
                                validateChargedAccount("innerBelowThreshold", PAYER),
                                validateChargedAccount("innerAboveThreshold", PAYER),
                                validateChargedUsdWithinWithTxnSize(
                                        "batchBelowThreshold",
                                        txnSize -> expectedAtomicBatchFullFeeUsd(
                                                Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                        0.1),
                                validateChargedUsdWithinWithTxnSize(
                                        "batchAboveThreshold",
                                        txnSize -> expectedAtomicBatchFullFeeUsd(
                                                Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                        0.1));

                        final double belowFee =
                                getChargedUsedForInnerTxn(spec, "batchBelowThreshold", "innerBelowThreshold");
                        final double aboveFee =
                                getChargedUsedForInnerTxn(spec, "batchAboveThreshold", "innerAboveThreshold");

                        log.info("Below-threshold inner fee: {}", belowFee);
                        log.info("Above-threshold inner fee: {}", aboveFee);

                        assertTrue(
                                aboveFee > belowFee,
                                "Above-threshold inner fee should be greater than below-threshold inner fee");
                    })));
        }
    }

    @Nested
    @DisplayName("Inner Batch NFT Mint Scenarios")
    class InnerBatchNFTMintScenarios {
        @HapiTest
        @DisplayName("NFT mint inner 1 serial — inner payer charged base fees and batch payer charged outer fee only")
        final Stream<DynamicTest> nftMintInnerOneSerialInnerPayerChargedBaseFee() {
            return hapiTest(flattened(
                    createAccountsAndKeys(),
                    createNonFungibleTokenWithoutCustomFees(NON_FUNGIBLE_TOKEN, OWNER, supplyKey, adminKey),
                    atomicBatch(mintToken(NON_FUNGIBLE_TOKEN, List.of(ByteString.copyFromUtf8("nft1")))
                                    .payingWith(PAYER)
                                    .signedBy(PAYER, supplyKey)
                                    .via("innerMint1")
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR)
                            .signedBy(BATCH_OPERATOR)
                            .via("batchTxnMint1"),
                    validateChargedAccount("batchTxnMint1", BATCH_OPERATOR),
                    validateChargedAccount("innerMint1", PAYER),
                    // outer batch payer is charged base fee only
                    validateChargedUsdWithinWithTxnSize(
                            "batchTxnMint1",
                            txnSize -> expectedAtomicBatchFullFeeUsd(
                                    Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),

                    // inner payer is charged base NFT fee only
                    validateInnerChargedUsdWithinWithTxnSize(
                            "innerMint1",
                            "batchTxnMint1",
                            txnSize -> expectedTokenMintNftFullFeeUsd(Map.of(
                                    SIGNATURES, 2L,
                                    TOKEN_MINT_NFT, 1L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1)));
        }

        @HapiTest
        @DisplayName("NFT mint inner 5 serials — inner payer charged extra fees and batch payer charged outer fee only")
        final Stream<DynamicTest>
                nftMintInnerMultipleSerialsWithDifferentInnerAndOuterPayersInnerPayerChargedExtraFee() {
            return hapiTest(flattened(
                    createAccountsAndKeys(),
                    createNonFungibleTokenWithoutCustomFees(NON_FUNGIBLE_TOKEN, OWNER, supplyKey, adminKey),
                    atomicBatch(mintToken(
                                            NON_FUNGIBLE_TOKEN,
                                            List.of(
                                                    ByteString.copyFromUtf8("nft1"),
                                                    ByteString.copyFromUtf8("nft2"),
                                                    ByteString.copyFromUtf8("nft3"),
                                                    ByteString.copyFromUtf8("nft4"),
                                                    ByteString.copyFromUtf8("nft5")))
                                    .payingWith(PAYER)
                                    .signedBy(PAYER, supplyKey)
                                    .via("innerMint5")
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR)
                            .signedBy(BATCH_OPERATOR)
                            .via("batchTxnMint5"),
                    validateChargedAccount("batchTxnMint5", BATCH_OPERATOR),
                    validateChargedAccount("innerMint5", PAYER),
                    validateChargedUsdWithinWithTxnSize(
                            "batchTxnMint5",
                            txnSize -> expectedAtomicBatchFullFeeUsd(
                                    Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),

                    // inner payer charged extra fees
                    validateInnerChargedUsdWithinWithTxnSize(
                            "innerMint5",
                            "batchTxnMint5",
                            txnSize -> expectedTokenMintNftFullFeeUsd(Map.of(
                                    SIGNATURES, 2L,
                                    TOKEN_MINT_NFT, 5L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1)));
        }

        @HapiTest
        @DisplayName("NFT mint inner 10 serials with the same inner and outer payer - extra fees charged")
        final Stream<DynamicTest> nftMintInnerMultipleSerialsInnerPayerEqualsBatchPayerExtraFeesCharged() {
            return hapiTest(flattened(
                    createAccountsAndKeys(),
                    createNonFungibleTokenWithoutCustomFees(NON_FUNGIBLE_TOKEN, OWNER, supplyKey, adminKey),
                    atomicBatch(mintToken(
                                            NON_FUNGIBLE_TOKEN,
                                            List.of(
                                                    ByteString.copyFromUtf8("nft1"),
                                                    ByteString.copyFromUtf8("nft2"),
                                                    ByteString.copyFromUtf8("nft3"),
                                                    ByteString.copyFromUtf8("nft4"),
                                                    ByteString.copyFromUtf8("nft5"),
                                                    ByteString.copyFromUtf8("nft6"),
                                                    ByteString.copyFromUtf8("nft7"),
                                                    ByteString.copyFromUtf8("nft8"),
                                                    ByteString.copyFromUtf8("nft9"),
                                                    ByteString.copyFromUtf8("nft10")))
                                    .payingWith(PAYER)
                                    .signedBy(PAYER, supplyKey)
                                    .via("innerMint10")
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(PAYER)
                            .signedBy(PAYER, BATCH_OPERATOR)
                            .via("batchTxnMint10"),
                    validateChargedAccount("batchTxnMint10", PAYER),
                    validateChargedAccount("innerMint10", PAYER),

                    // all fees charged to PAYER
                    validateChargedUsdWithinWithTxnSize(
                            "batchTxnMint10",
                            txnSize -> expectedAtomicBatchFullFeeUsd(
                                    Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateInnerChargedUsdWithinWithTxnSize(
                            "innerMint10",
                            "batchTxnMint10",
                            txnSize -> expectedTokenMintNftFullFeeUsd(Map.of(
                                    SIGNATURES, 2L,
                                    TOKEN_MINT_NFT, 10L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1)));
        }
    }

    @Nested
    @DisplayName("Multiple Inner Batch Scenarios")
    class MultipleInnerBatchScenarios {
        @HapiTest
        @DisplayName(
                "Multiple File Create inner txns with the same inner payer and separate batch payer — Fees charged correctly")
        final Stream<DynamicTest>
                multipleFileCreateInnerTxnsWithSameInnerPayerAndSeparateBatchPayerFeesChargedCorrectly() {
            final byte[] content = new byte[500];
            Arrays.fill(content, (byte) 'a');
            return hapiTest(flattened(
                    createAccountsAndKeys(),
                    newKeyListNamed("WACL", List.of(PAYER)),
                    atomicBatch(
                                    fileCreate("file1")
                                            .key("WACL")
                                            .contents(content)
                                            .payingWith(PAYER)
                                            .signedBy(PAYER)
                                            .via("innerFileCreate1")
                                            .batchKey(BATCH_OPERATOR),
                                    fileCreate("file2")
                                            .key("WACL")
                                            .contents(content)
                                            .payingWith(PAYER)
                                            .signedBy(PAYER)
                                            .via("innerFileCreate2")
                                            .batchKey(BATCH_OPERATOR),
                                    fileCreate("file3")
                                            .key("WACL")
                                            .contents(content)
                                            .payingWith(PAYER)
                                            .signedBy(PAYER)
                                            .via("innerFileCreate3")
                                            .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR)
                            .signedBy(BATCH_OPERATOR)
                            .via("batchTxn3FileCreate"),
                    validateChargedAccount("batchTxn3FileCreate", BATCH_OPERATOR),
                    validateChargedAccount("innerFileCreate1", PAYER),
                    validateChargedAccount("innerFileCreate2", PAYER),
                    validateChargedAccount("innerFileCreate3", PAYER),

                    // validate batch txn fees
                    validateChargedUsdWithinWithTxnSize(
                            "batchTxn3FileCreate",
                            txnSize -> expectedAtomicBatchFullFeeUsd(
                                    Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),

                    // validate inner txn fees
                    validateInnerChargedUsdWithinWithTxnSize(
                            "innerFileCreate1",
                            "batchTxn3FileCreate",
                            txnSize -> expectedFileCreateFullFeeUsd(Map.of(
                                    SIGNATURES, 1L,
                                    STATE_BYTES, 500L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateInnerChargedUsdWithinWithTxnSize(
                            "innerFileCreate2",
                            "batchTxn3FileCreate",
                            txnSize -> expectedFileCreateFullFeeUsd(Map.of(
                                    SIGNATURES, 1L,
                                    STATE_BYTES, 500L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateInnerChargedUsdWithinWithTxnSize(
                            "innerFileCreate3",
                            "batchTxn3FileCreate",
                            txnSize -> expectedFileCreateFullFeeUsd(Map.of(
                                    SIGNATURES, 1L,
                                    STATE_BYTES, 500L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    assertionsHold((spec, assertLog) -> {
                        final int size1 = signedInnerTxnSizeFor(spec, "innerFileCreate1");
                        final int size2 = signedInnerTxnSizeFor(spec, "innerFileCreate2");
                        final int size3 = signedInnerTxnSizeFor(spec, "innerFileCreate3");

                        final double expectedFee1 = expectedFileCreateFullFeeUsd(
                                Map.of(SIGNATURES, 1L, STATE_BYTES, 500L, PROCESSING_BYTES, (long) size1));
                        final double expectedFee2 = expectedFileCreateFullFeeUsd(
                                Map.of(SIGNATURES, 1L, STATE_BYTES, 500L, PROCESSING_BYTES, (long) size2));
                        final double expectedFee3 = expectedFileCreateFullFeeUsd(
                                Map.of(SIGNATURES, 1L, STATE_BYTES, 500L, PROCESSING_BYTES, (long) size3));

                        final double expectedTotal = expectedFee1 + expectedFee2 + expectedFee3;

                        final double actualFee1 =
                                getChargedUsedForInnerTxn(spec, "batchTxn3FileCreate", "innerFileCreate1");
                        final double actualFee2 =
                                getChargedUsedForInnerTxn(spec, "batchTxn3FileCreate", "innerFileCreate2");
                        final double actualFee3 =
                                getChargedUsedForInnerTxn(spec, "batchTxn3FileCreate", "innerFileCreate3");

                        final double actualTotal = actualFee1 + actualFee2 + actualFee3;

                        assertLog.info(
                                "Inner FileCreate sizes: [{}, {}, {}], expected fees: [{}, {}, {}], expected total: {}, actual total: {}",
                                size1,
                                size2,
                                size3,
                                expectedFee1,
                                expectedFee2,
                                expectedFee3,
                                expectedTotal,
                                actualTotal);

                        final double tolerance = expectedTotal * 0.001; // 0.1%
                        assertEquals(
                                expectedTotal,
                                actualTotal,
                                tolerance,
                                "Total inner fees should equal the sum of the expected fees for all 3 inner txns");
                    })));
        }

        @HapiTest
        @DisplayName(
                "Multiple File Create inner txns, with the same inner and outer batch payer — Fees charged correctly to one payer")
        final Stream<DynamicTest> multipleFileCreateInnerTxnsWithSameInnerAndOuterPayerFeesChargedCorrectly() {
            final byte[] content = new byte[500];
            Arrays.fill(content, (byte) 'a');
            return hapiTest(flattened(
                    createAccountsAndKeys(),
                    newKeyListNamed("WACL", List.of(PAYER)),
                    atomicBatch(
                                    fileCreate("sharedFile1")
                                            .key("WACL")
                                            .contents(content)
                                            .payingWith(PAYER)
                                            .signedBy(PAYER)
                                            .via("sharedInnerFileCreate1")
                                            .batchKey(BATCH_OPERATOR),
                                    fileCreate("sharedFile2")
                                            .key("WACL")
                                            .contents(content)
                                            .payingWith(PAYER)
                                            .signedBy(PAYER)
                                            .via("sharedInnerFileCreate2")
                                            .batchKey(BATCH_OPERATOR),
                                    fileCreate("sharedFile3")
                                            .key("WACL")
                                            .contents(content)
                                            .payingWith(PAYER)
                                            .signedBy(PAYER)
                                            .via("sharedInnerFileCreate3")
                                            .batchKey(BATCH_OPERATOR))
                            // PAYER pays the outer batch fee too
                            .payingWith(PAYER)
                            .signedBy(PAYER, BATCH_OPERATOR)
                            .via("batchTxnShared3FileCreate"),
                    validateChargedAccount("batchTxnShared3FileCreate", PAYER),
                    validateChargedAccount("sharedInnerFileCreate1", PAYER),
                    validateChargedAccount("sharedInnerFileCreate2", PAYER),
                    validateChargedAccount("sharedInnerFileCreate3", PAYER),

                    // validate batch txn fees
                    validateChargedUsdWithinWithTxnSize(
                            "batchTxnShared3FileCreate",
                            txnSize -> expectedAtomicBatchFullFeeUsd(
                                    Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),

                    // validate inner txn fees
                    validateInnerChargedUsdWithinWithTxnSize(
                            "sharedInnerFileCreate1",
                            "batchTxnShared3FileCreate",
                            txnSize -> expectedFileCreateFullFeeUsd(Map.of(
                                    SIGNATURES, 1L,
                                    STATE_BYTES, 500L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateInnerChargedUsdWithinWithTxnSize(
                            "sharedInnerFileCreate2",
                            "batchTxnShared3FileCreate",
                            txnSize -> expectedFileCreateFullFeeUsd(Map.of(
                                    SIGNATURES, 1L,
                                    STATE_BYTES, 500L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateInnerChargedUsdWithinWithTxnSize(
                            "sharedInnerFileCreate3",
                            "batchTxnShared3FileCreate",
                            txnSize -> expectedFileCreateFullFeeUsd(Map.of(
                                    SIGNATURES, 1L,
                                    STATE_BYTES, 500L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1)));
        }

        @HapiTest
        @DisplayName("Multiple CryptoCreate inner txns, each with a different inner payer — Fees charged correctly")
        final Stream<DynamicTest> multipleCryptoCreateInnerTxnsWithDifferentInnerPayersEachFeesChargedCorrectly() {
            return hapiTest(flattened(
                    createAccountsAndKeys(),
                    atomicBatch(
                                    cryptoCreate("newAccount1")
                                            .payingWith(PAYER)
                                            .signedBy(PAYER)
                                            .via("innerCryptoCreate1")
                                            .batchKey(BATCH_OPERATOR),
                                    cryptoCreate("newAccount2")
                                            .payingWith(OWNER)
                                            .signedBy(OWNER)
                                            .via("innerCryptoCreate2")
                                            .batchKey(BATCH_OPERATOR),
                                    cryptoCreate("newAccount3")
                                            .payingWith(RECEIVER_ASSOCIATED_FIRST)
                                            .signedBy(RECEIVER_ASSOCIATED_FIRST)
                                            .via("innerCryptoCreate3")
                                            .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR)
                            .signedBy(BATCH_OPERATOR)
                            .via("batchTxn3DiffPayers"),
                    validateChargedAccount("batchTxn3DiffPayers", BATCH_OPERATOR),
                    validateChargedAccount("innerCryptoCreate1", PAYER),
                    validateChargedAccount("innerCryptoCreate2", OWNER),
                    validateChargedAccount("innerCryptoCreate3", RECEIVER_ASSOCIATED_FIRST),

                    // validate batch txn fees
                    validateChargedUsdWithinWithTxnSize(
                            "batchTxn3DiffPayers",
                            txnSize -> expectedAtomicBatchFullFeeUsd(
                                    Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),

                    // validate inner txn fees
                    validateInnerChargedUsdWithinWithTxnSize(
                            "innerCryptoCreate1",
                            "batchTxn3DiffPayers",
                            txnSize -> expectedCryptoCreateFullFeeUsd(
                                    Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateInnerChargedUsdWithinWithTxnSize(
                            "innerCryptoCreate2",
                            "batchTxn3DiffPayers",
                            txnSize -> expectedCryptoCreateFullFeeUsd(
                                    Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateInnerChargedUsdWithinWithTxnSize(
                            "innerCryptoCreate3",
                            "batchTxn3DiffPayers",
                            txnSize -> expectedCryptoCreateFullFeeUsd(
                                    Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                            0.1)));
        }

        @HapiTest
        @DisplayName("Multiple TopicSubmitMessage inner txns, with the same inner payer — Fees charged correctly")
        final Stream<DynamicTest> multipleTopicSubmitMessageInnerTxnsWithSameInnerPayerFeesChargedCorrectly() {
            final String message = "M".repeat(100);
            return hapiTest(flattened(
                    createAccountsAndKeys(),
                    newKeyNamed(adminKey),
                    newKeyNamed(submitKey),
                    createTopic("testTopic").adminKeyName(adminKey).submitKeyName(submitKey),
                    atomicBatch(
                                    submitMessageTo("testTopic")
                                            .message(message)
                                            .payingWith(PAYER)
                                            .signedBy(PAYER, submitKey)
                                            .via("innerSubmit1")
                                            .batchKey(BATCH_OPERATOR),
                                    submitMessageTo("testTopic")
                                            .message(message)
                                            .payingWith(PAYER)
                                            .signedBy(PAYER, submitKey)
                                            .via("innerSubmit2")
                                            .batchKey(BATCH_OPERATOR),
                                    submitMessageTo("testTopic")
                                            .message(message)
                                            .payingWith(PAYER)
                                            .signedBy(PAYER, submitKey)
                                            .via("innerSubmit3")
                                            .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR)
                            .signedBy(BATCH_OPERATOR)
                            .via("batchTxn3Submit"),
                    validateChargedAccount("batchTxn3Submit", BATCH_OPERATOR),
                    validateChargedAccount("innerSubmit1", PAYER),
                    validateChargedAccount("innerSubmit2", PAYER),
                    validateChargedAccount("innerSubmit3", PAYER),

                    // validate batch txn fees
                    validateChargedUsdWithinWithTxnSize(
                            "batchTxn3Submit",
                            txnSize -> expectedAtomicBatchFullFeeUsd(
                                    Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),

                    // validate inner txn fees
                    validateInnerChargedUsdWithinWithTxnSize(
                            "innerSubmit1",
                            "batchTxn3Submit",
                            txnSize -> expectedTopicSubmitMessageFullFeeUsd(Map.of(
                                    SIGNATURES, 2L,
                                    STATE_BYTES, (long) message.length(),
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateInnerChargedUsdWithinWithTxnSize(
                            "innerSubmit2",
                            "batchTxn3Submit",
                            txnSize -> expectedTopicSubmitMessageFullFeeUsd(Map.of(
                                    SIGNATURES, 2L,
                                    STATE_BYTES, (long) message.length(),
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateInnerChargedUsdWithinWithTxnSize(
                            "innerSubmit3",
                            "batchTxn3Submit",
                            txnSize -> expectedTopicSubmitMessageFullFeeUsd(Map.of(
                                    SIGNATURES, 2L,
                                    STATE_BYTES, (long) message.length(),
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1),

                    // assert all 3 inner txns have the same fee
                    assertionsHold((spec, assertLog) -> {
                        final int s1 = signedInnerTxnSizeFor(spec, "innerSubmit1");
                        final int s2 = signedInnerTxnSizeFor(spec, "innerSubmit2");
                        final int s3 = signedInnerTxnSizeFor(spec, "innerSubmit3");
                        assertTrue(s1 == s2 && s2 == s3, "All 3 submit inner txns should have identical signed sizes");
                        final double singleFee = expectedTopicSubmitMessageFullFeeUsd(Map.of(
                                SIGNATURES, 2L, STATE_BYTES, (long) message.length(), PROCESSING_BYTES, (long) s1));
                        assertLog.info(
                                "Single submit fee (with STATE_BYTES): {} USD, total for 3: {} USD",
                                singleFee,
                                3 * singleFee);
                    })));
        }
    }

    @Nested
    @DisplayName("Extra Signatures And Keys Inner Batch Scenarios")
    class ExtraSignaturesAndKeysInnerBatchScenarios {
        @HapiTest
        @DisplayName(
                "Inner txn with extra unnecessary signatures — all verified signatures counted in inner payer fee - Fees charged correctly")
        final Stream<DynamicTest> innerTxnWithExtraUnnecessarySignaturesAllCountedInInnerPayerFee() {
            return hapiTest(flattened(
                    createAccountsAndKeys(),
                    atomicBatch(cryptoCreate("newAccountExtraSig")
                                    .payingWith(PAYER)
                                    .signedBy(PAYER, OWNER)
                                    .via("innerExtraSig")
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR)
                            .signedBy(BATCH_OPERATOR)
                            .via("batchTxnExtraSig"),
                    validateChargedAccount("batchTxnExtraSig", BATCH_OPERATOR),
                    validateChargedAccount("innerExtraSig", PAYER),
                    validateChargedUsdWithinWithTxnSize(
                            "batchTxnExtraSig",
                            txnSize -> expectedAtomicBatchFullFeeUsd(
                                    Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateInnerChargedUsdWithinWithTxnSize(
                            "innerExtraSig",
                            "batchTxnExtraSig",
                            txnSize -> expectedCryptoCreateFullFeeUsd(
                                    Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                            0.1)));
        }

        @HapiTest
        @DisplayName("Inner txn with threshold key with different inner and outer payers - Fees charged correctly")
        final Stream<DynamicTest> innerTxnWithThresholdKeyWithDifferentInnerAndOuterPayerFeesChargedCorrectly() {
            return hapiTest(flattened(
                    createAccountsAndKeys(),
                    newKeyNamed("threshKey").shape(threshOf(2, 3)),
                    cryptoCreate("threshPayer").key("threshKey").balance(ONE_HUNDRED_HBARS),
                    atomicBatch(cryptoCreate("newAccountThresh")
                                    .payingWith("threshPayer")
                                    .signedBy("threshKey")
                                    .via("innerThresh")
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR)
                            .signedBy(BATCH_OPERATOR)
                            .via("batchTxnThresh"),
                    validateChargedAccount("batchTxnThresh", BATCH_OPERATOR),
                    validateChargedAccount("innerThresh", "threshPayer"),
                    validateChargedUsdWithinWithTxnSize(
                            "batchTxnThresh",
                            txnSize -> expectedAtomicBatchFullFeeUsd(
                                    Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateInnerChargedUsdWithinWithTxnSize(
                            "innerThresh",
                            "batchTxnThresh",
                            txnSize -> expectedCryptoCreateFullFeeUsd(
                                    Map.of(SIGNATURES, 3L, PROCESSING_BYTES, (long) txnSize)),
                            0.1)));
        }

        @HapiTest
        @DisplayName("Inner txn with threshold key with the same inner and outer batch payer — Fees charged correctly")
        final Stream<DynamicTest> innerTxnWithThresholdKeyInnerPayerEqualsBatchPayerNoDoubleCounting() {
            return hapiTest(flattened(
                    createAccountsAndKeys(),
                    newKeyNamed("threshKey2").shape(threshOf(2, 3)),
                    cryptoCreate("threshPayerShared").key("threshKey2").balance(ONE_HUNDRED_HBARS),
                    atomicBatch(cryptoCreate("newAccountThreshShared")
                                    .payingWith("threshPayerShared")
                                    .signedBy("threshKey2")
                                    .via("innerThreshShared")
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith("threshPayerShared")
                            .signedBy("threshKey2", BATCH_OPERATOR)
                            .via("batchTxnThreshShared"),
                    validateChargedAccount("batchTxnThreshShared", "threshPayerShared"),
                    validateChargedAccount("innerThreshShared", "threshPayerShared"),
                    validateChargedUsdWithinWithTxnSize(
                            "batchTxnThreshShared",
                            txnSize -> expectedAtomicBatchFullFeeUsd(
                                    Map.of(SIGNATURES, 4L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateInnerChargedUsdWithinWithTxnSize(
                            "innerThreshShared",
                            "batchTxnThreshShared",
                            txnSize -> expectedCryptoCreateFullFeeUsd(
                                    Map.of(SIGNATURES, 3L, PROCESSING_BYTES, (long) txnSize)),
                            0.1)));
        }
    }

    @Nested
    @DisplayName("Atomic Batch Corner Cases")
    class AtomicBatchCornerCases {
        @HapiTest
        @DisplayName("Atomic batch inner txn with large memo - Fees charged correctly")
        final Stream<DynamicTest> largeMemoOnInnerTxnProcessingBytesExtraToInnerPayerOnly() {
            final String largeMemo = "M".repeat(100);
            return hapiTest(flattened(
                    createAccountsAndKeys(),
                    atomicBatch(cryptoCreate("largeMemoAccount")
                                    .payingWith(PAYER)
                                    .signedBy(PAYER)
                                    .memo(largeMemo)
                                    .via("innerLargeMemo")
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR)
                            .signedBy(BATCH_OPERATOR)
                            .via("batchTxnLargeMemo"),
                    validateChargedAccount("batchTxnLargeMemo", BATCH_OPERATOR),
                    validateChargedAccount("innerLargeMemo", PAYER),
                    validateChargedUsdWithinWithTxnSize(
                            "batchTxnLargeMemo",
                            txnSize -> expectedAtomicBatchFullFeeUsd(
                                    Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateInnerChargedUsdWithinWithTxnSize(
                            "innerLargeMemo",
                            "batchTxnLargeMemo",
                            txnSize -> expectedCryptoCreateFullFeeUsd(
                                    Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                            0.1)));
        }

        @HapiTest
        @DisplayName(
                "Atomic Batch txn with same payer for outer and one inner txn and separate payer for all other inner txns - Fees Charged Correctly")
        final Stream<DynamicTest> sharedAccountChargedOuterFeeAndOneInnerFeeOnlySeparateAccountChargedOtherInnerOnly() {
            return hapiTest(flattened(
                    createAccountsAndKeys(),
                    atomicBatch(
                                    cryptoCreate("sharedPayerAccount")
                                            .payingWith(PAYER)
                                            .signedBy(PAYER)
                                            .via("innerSharedPayer")
                                            .batchKey(BATCH_OPERATOR),
                                    cryptoCreate("separatePayerAccount")
                                            .payingWith(OWNER)
                                            .signedBy(OWNER)
                                            .via("innerSeparatePayer")
                                            .batchKey(BATCH_OPERATOR))
                            .payingWith(PAYER)
                            .signedBy(PAYER, BATCH_OPERATOR)
                            .via("batchTxnMixedPayers"),
                    validateChargedAccount("batchTxnMixedPayers", PAYER),
                    validateChargedAccount("innerSharedPayer", PAYER),
                    validateChargedAccount("innerSeparatePayer", OWNER),
                    validateChargedUsdWithinWithTxnSize(
                            "batchTxnMixedPayers",
                            txnSize -> expectedAtomicBatchFullFeeUsd(
                                    Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateInnerChargedUsdWithinWithTxnSize(
                            "innerSharedPayer",
                            "batchTxnMixedPayers",
                            txnSize -> expectedCryptoCreateFullFeeUsd(
                                    Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateInnerChargedUsdWithinWithTxnSize(
                            "innerSeparatePayer",
                            "batchTxnMixedPayers",
                            txnSize -> expectedCryptoCreateFullFeeUsd(
                                    Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                            0.1)));
        }

        @LeakyHapiTest(
                requirement = {PROPERTY_OVERRIDES},
                overrides = {"atomicBatch.maxNumberOfTransactions"})
        @DisplayName(
                "Atomic Batch with maximum allowed inner transactions, with the same inner payer — Fees charged correctly")
        final Stream<DynamicTest> batchWithMaxAllowedInnerTransactionsVerifyTotalFeeChargedCorrectly() {
            final int MAX_INNER = 10;
            return hapiTest(flattened(
                    createAccountsAndKeys(),
                    overriding("atomicBatch.maxNumberOfTransactions", String.valueOf(MAX_INNER)),
                    atomicBatch(
                                    cryptoCreate("maxInner1")
                                            .payingWith(PAYER)
                                            .signedBy(PAYER)
                                            .via("maxInnerTxn1")
                                            .batchKey(BATCH_OPERATOR),
                                    cryptoCreate("maxInner2")
                                            .payingWith(PAYER)
                                            .signedBy(PAYER)
                                            .via("maxInnerTxn2")
                                            .batchKey(BATCH_OPERATOR),
                                    cryptoCreate("maxInner3")
                                            .payingWith(PAYER)
                                            .signedBy(PAYER)
                                            .via("maxInnerTxn3")
                                            .batchKey(BATCH_OPERATOR),
                                    cryptoCreate("maxInner4")
                                            .payingWith(PAYER)
                                            .signedBy(PAYER)
                                            .via("maxInnerTxn4")
                                            .batchKey(BATCH_OPERATOR),
                                    cryptoCreate("maxInner5")
                                            .payingWith(PAYER)
                                            .signedBy(PAYER)
                                            .via("maxInnerTxn5")
                                            .batchKey(BATCH_OPERATOR),
                                    cryptoCreate("maxInner6")
                                            .payingWith(PAYER)
                                            .signedBy(PAYER)
                                            .via("maxInnerTxn6")
                                            .batchKey(BATCH_OPERATOR),
                                    cryptoCreate("maxInner7")
                                            .payingWith(PAYER)
                                            .signedBy(PAYER)
                                            .via("maxInnerTxn7")
                                            .batchKey(BATCH_OPERATOR),
                                    cryptoCreate("maxInner8")
                                            .payingWith(PAYER)
                                            .signedBy(PAYER)
                                            .via("maxInnerTxn8")
                                            .batchKey(BATCH_OPERATOR),
                                    cryptoCreate("maxInner9")
                                            .payingWith(PAYER)
                                            .signedBy(PAYER)
                                            .via("maxInnerTxn9")
                                            .batchKey(BATCH_OPERATOR),
                                    cryptoCreate("maxInner10")
                                            .payingWith(PAYER)
                                            .signedBy(PAYER)
                                            .via("maxInnerTxn10")
                                            .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR)
                            .signedBy(BATCH_OPERATOR)
                            .via("batchTxnMax"),
                    validateChargedAccount("batchTxnMax", BATCH_OPERATOR),
                    validateChargedAccount("maxInnerTxn1", PAYER),
                    validateChargedUsdWithinWithTxnSize(
                            "batchTxnMax",
                            txnSize -> expectedAtomicBatchFullFeeUsd(
                                    Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateInnerChargedUsdWithinWithTxnSize(
                            "maxInnerTxn1",
                            "batchTxnMax",
                            txnSize -> expectedCryptoCreateFullFeeUsd(
                                    Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateInnerChargedUsdWithinWithTxnSize(
                            "maxInnerTxn10",
                            "batchTxnMax",
                            txnSize -> expectedCryptoCreateFullFeeUsd(
                                    Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),

                    // assert total inner fees = MAX_INNER × single inner fee
                    assertionsHold((spec, assertLog) -> {
                        final int singleSize = signedInnerTxnSizeFor(spec, "maxInnerTxn1");
                        final double singleFee = expectedCryptoCreateFullFeeUsd(
                                Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) singleSize));
                        final double expectedTotal = MAX_INNER * singleFee;

                        // verify all inner txns have the same signed sizes
                        for (int i = 2; i <= MAX_INNER; i++) {
                            final int size = signedInnerTxnSizeFor(spec, "maxInnerTxn" + i);
                            assertEquals(
                                    singleSize,
                                    size,
                                    "maxInnerTxn" + i + " size (" + size + ") " + "should equal maxInnerTxn1 size ("
                                            + singleSize + ")");
                        }

                        // sum actual fees for all inner txns and assert the total equals MAX_INNER × single fee
                        final double totalActual = IntStream.rangeClosed(1, MAX_INNER)
                                .mapToDouble(i -> getChargedUsedForInnerTxn(spec, "batchTxnMax", "maxInnerTxn" + i))
                                .sum();

                        assertLog.info(
                                "Single inner fee: {} USD × {} txns = {} USD expected total, {} USD actual total",
                                singleFee,
                                MAX_INNER,
                                expectedTotal,
                                totalActual);

                        final double tolerance = expectedTotal * 0.001; // 0.1%
                        assertEquals(
                                expectedTotal,
                                totalActual,
                                tolerance,
                                "Total inner fees should equal MAX_INNER × single fee");
                    })));
        }

        @HapiTest
        @DisplayName("Atomic Batch with nested batch txn — fails on Ingest")
        final Stream<DynamicTest> atomicBatchTxnWithNestedBatchInnerTxnFailsOnIngest() {
            return hapiTest(flattened(
                    createAccountsAndKeys(),
                    atomicBatch(atomicBatch(cryptoCreate("deepInner")
                                            .payingWith(PAYER)
                                            .signedBy(PAYER)
                                            .via("deepInnerTxn")
                                            .batchKey(BATCH_OPERATOR))
                                    .payingWith(PAYER)
                                    .signedBy(PAYER, BATCH_OPERATOR)
                                    .via("innerBatch")
                                    .batchKey(BATCH_OPERATOR))
                            .hasKnownStatus(BATCH_TRANSACTION_IN_BLACKLIST)
                            .payingWith(PAYER)
                            .signedBy(PAYER, BATCH_OPERATOR)
                            .via("outerBatch")
                            .hasKnownStatus(BATCH_TRANSACTION_IN_BLACKLIST),
                    validateChargedAccount("outerBatch", PAYER),
                    validateChargedUsdWithinWithTxnSize(
                            "outerBatch",
                            txnSize -> expectedAtomicBatchFullFeeUsd(
                                    Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),

                    // no record is created on precheck failure
                    getTxnRecord("innerBatch").logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND)));
        }
    }

    private HapiTokenCreate createNonFungibleTokenWithoutCustomFees(
            String tokenName, String treasury, String supplyKey, String adminKey) {
        return tokenCreate(tokenName)
                .initialSupply(0)
                .treasury(treasury)
                .tokenType(NON_FUNGIBLE_UNIQUE)
                .supplyKey(supplyKey)
                .adminKey(adminKey);
    }

    private List<SpecOperation> createAccountsAndKeys() {
        return List.of(
                cryptoCreate(PAYER).balance(ONE_MILLION_HBARS),
                cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(BATCH_OPERATOR).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER_ASSOCIATED_FIRST).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER_ASSOCIATED_SECOND).balance(ONE_HBAR),
                newKeyNamed(adminKey),
                newKeyNamed(submitKey),
                newKeyNamed(supplyKey));
    }
}
