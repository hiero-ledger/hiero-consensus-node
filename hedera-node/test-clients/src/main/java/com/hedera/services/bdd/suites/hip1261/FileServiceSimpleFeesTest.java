// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1261;

import static com.hedera.services.bdd.junit.TestTags.ONLY_SUBPROCESS;
import static com.hedera.services.bdd.junit.TestTags.SIMPLE_FEES;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.keys.ControlForKey.forKey;
import static com.hedera.services.bdd.spec.keys.KeyShape.listOf;
import static com.hedera.services.bdd.spec.keys.KeyShape.sigs;
import static com.hedera.services.bdd.spec.keys.KeyShape.threshOf;
import static com.hedera.services.bdd.spec.keys.SigControl.OFF;
import static com.hedera.services.bdd.spec.keys.SigControl.ON;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getFileContents;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getFileInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileAppend;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileUpdate;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyListNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.usableTxnIdNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsdForQueries;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateNonZeroNodePaymentForQuery;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedFileAppendFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedFileCreateFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedFileDeleteFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.nodeFeeFromBytesUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.validateChargedUsdWithinWithTxnSize;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.FILE_APPEND_BASE_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.FILE_DELETE_BASE_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.FILE_GET_CONTENTS_INCLUDED_PROCESSING_BYTES;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.FILE_GET_CONTENTS_QUERY_BASE_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.FILE_GET_INFO_QUERY_BASE_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.FILE_UPDATE_BASE_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.FILE_UPDATE_INCLUDED_KEYS;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.KEYS_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.NETWORK_MULTIPLIER;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.NODE_BASE_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.NODE_INCLUDED_SIGNATURES;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.PROCESSING_BYTES_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.SIGNATURE_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.STATE_BYTES_FEE_USD;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.DUPLICATE_TRANSACTION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.FILE_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TX_FEE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_FILE_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TRANSACTION_DURATION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TRANSACTION_START;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MAX_FILE_SIZE_EXCEEDED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MEMO_TOO_LONG;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.RECORD_NOT_FOUND;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSACTION_EXPIRED;
import static org.hiero.hapi.support.fees.Extra.KEYS;
import static org.hiero.hapi.support.fees.Extra.PROCESSING_BYTES;
import static org.hiero.hapi.support.fees.Extra.SIGNATURES;
import static org.hiero.hapi.support.fees.Extra.STATE_BYTES;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.keys.SigControl;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.hiero.hapi.support.fees.Extra;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;

@Tag(SIMPLE_FEES)
@HapiTestLifecycle
public class FileServiceSimpleFeesTest {
    private static final String PAYER = "payer";
    private static final String PAYER_KEY = "payerKey";
    private static final int KILOBYTE = 1_024;
    private static final int SERVICE_STATE_BYTES_THRESHOLD = 1_000;
    private static final int NODE_PROCESSING_BYTES_THRESHOLD = KILOBYTE;
    private static final int FILE_MEMO_MAX_BYTES = KILOBYTE;
    private static final int FILE_MEMO_EXCEEDS_MAX_BYTES = FILE_MEMO_MAX_BYTES + 1;
    private static final int LARGE_FILE_MAX_SIZE_KB = 128;
    private static final int SMALL_FILE_MAX_SIZE_KB = 1;
    private static final String FILE = "testFile";

    private static final double ALLOWED_PERCENT_DIFF = 0.1;
    private static final int LARGE_CONTENT_BYTES = 5_000;
    private static final int SERVICE_STATE_BYTES_BELOW_THRESHOLD = SERVICE_STATE_BYTES_THRESHOLD - 1;
    private static final int SERVICE_STATE_BYTES_ABOVE_THRESHOLD = SERVICE_STATE_BYTES_THRESHOLD + 1;
    private static final int NODE_PROCESSING_BYTES_BELOW_THRESHOLD = NODE_PROCESSING_BYTES_THRESHOLD - 1;
    private static final int NODE_PROCESSING_BYTES_ABOVE_THRESHOLD = NODE_PROCESSING_BYTES_THRESHOLD + 1;

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of("fees.simpleFeesEnabled", "true"));
    }

    @Nested
    @DisplayName("File Service Simple Fees Positive Test Cases")
    class FileServiceSimpleFeesPositiveTestCases {

        @Nested
        @DisplayName("Extras Charging Scenarios")
        class ExtrasChargingScenarios {
            @HapiTest
            @DisplayName("FileUpdate - extra bytes above service threshold")
            Stream<DynamicTest> fileUpdateExtraBytesAboveThreshold() {
                final var contentBytes = 4_000;
                return hapiTest(
                        newKeyNamed(PAYER_KEY),
                        newKeyListNamed("wacl", List.of(PAYER_KEY)),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        fileCreate(FILE)
                                .key("wacl")
                                .contents("abc")
                                .payingWith(PAYER)
                                .signedBy(PAYER),
                        fileUpdate(FILE)
                                .contents(bytesWithLength(contentBytes))
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .via("fileUpdateExtraBytesTxn"),
                        validateChargedUsdWithinWithTxnSize(
                                "fileUpdateExtraBytesTxn",
                                txnSize -> expectedFileUpdateFullFeeUsd(Map.of(
                                        SIGNATURES,
                                        1L,
                                        KEYS,
                                        1L,
                                        STATE_BYTES,
                                        (long) contentBytes,
                                        PROCESSING_BYTES,
                                        (long) txnSize)),
                                ALLOWED_PERCENT_DIFF));
            }

            @HapiTest
            @DisplayName("FileAppend - extra bytes above service threshold")
            Stream<DynamicTest> fileAppendExtraBytesAboveThreshold() {
                final var appendBytes = 4_000;
                return hapiTest(
                        newKeyNamed(PAYER_KEY),
                        newKeyListNamed("wacl", List.of(PAYER_KEY)),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        fileCreate(FILE)
                                .key("wacl")
                                .contents("abc")
                                .payingWith(PAYER)
                                .signedBy(PAYER),
                        fileAppend(FILE)
                                .content(bytesWithLength(appendBytes))
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .via("fileAppendExtraBytesTxn"),
                        validateChargedUsdWithinWithTxnSize(
                                "fileAppendExtraBytesTxn",
                                txnSize -> expectedFileAppendFullFeeUsd(
                                        Map.of(SIGNATURES, 1L, STATE_BYTES, (long) appendBytes, PROCESSING_BYTES, (long)
                                                txnSize)),
                                ALLOWED_PERCENT_DIFF));
            }

            @HapiTest
            @DisplayName("FileUpdate - extra WACL keys (per-key + per-signature charging)")
            Stream<DynamicTest> fileUpdateExtraWaclKeys() {
                return hapiTest(
                        newKeyNamed(PAYER_KEY),
                        newKeyListNamed("wacl", List.of(PAYER_KEY)),
                        newKeyNamed("k1"),
                        newKeyNamed("k2"),
                        newKeyNamed("k3"),
                        newKeyNamed("k4"),
                        newKeyNamed("k5"),
                        newKeyListNamed("fiveKeys", List.of("k1", "k2", "k3", "k4", "k5")),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        fileCreate(FILE)
                                .key("wacl")
                                .contents("abc")
                                .payingWith(PAYER)
                                .signedBy(PAYER),
                        fileUpdate(FILE)
                                .wacl("fiveKeys")
                                .payingWith(PAYER)
                                .signedBy(PAYER, "k1", "k2", "k3", "k4", "k5")
                                .via("fileUpdateExtraKeysTxn"),
                        validateChargedUsdWithinWithTxnSize(
                                "fileUpdateExtraKeysTxn",
                                txnSize -> expectedFileUpdateFullFeeUsd(Map.of(
                                        SIGNATURES, 6L, KEYS, 5L, STATE_BYTES, 0L, PROCESSING_BYTES, (long) txnSize)),
                                ALLOWED_PERCENT_DIFF));
            }

            @HapiTest
            @DisplayName("FileUpdate - WACL-only change, no content update")
            Stream<DynamicTest> fileUpdateWaclOnlyChangeBaseFeeStillCharged() {
                return hapiTest(
                        newKeyNamed(PAYER_KEY),
                        newKeyNamed("oldWaclKey"),
                        newKeyNamed("newWaclKey"),
                        newKeyListNamed("oldWacl", List.of("oldWaclKey")),
                        newKeyListNamed("newWacl", List.of("newWaclKey")),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        fileCreate(FILE)
                                .key("oldWacl")
                                .contents("abc")
                                .payingWith(PAYER)
                                .signedBy(PAYER, "oldWaclKey"),
                        fileUpdate(FILE)
                                .wacl("newWacl")
                                .payingWith(PAYER)
                                .signedBy(PAYER, "oldWaclKey", "newWaclKey")
                                .via("fileUpdateWaclOnlyTxn"),
                        validateChargedUsdWithinWithTxnSize(
                                "fileUpdateWaclOnlyTxn",
                                txnSize -> expectedFileUpdateFullFeeUsd(Map.of(
                                        SIGNATURES, 3L, KEYS, 1L, STATE_BYTES, 0L, PROCESSING_BYTES, (long) txnSize)),
                                ALLOWED_PERCENT_DIFF));
            }
        }

        @Nested
        @DisplayName("Boundary Value Scenarios")
        class BoundaryValueScenarios {
            @Nested
            @DisplayName("FileCreate")
            class FileCreateBoundaries {
                @HapiTest
                @DisplayName("FileCreate - just below service threshold")
                Stream<DynamicTest> fileCreate999Bytes() {
                    return fileCreateBoundaryWithExpectedFee(
                            SERVICE_STATE_BYTES_BELOW_THRESHOLD, "fileCreate999BytesTxn");
                }

                @HapiTest
                @DisplayName("FileCreate - at service threshold")
                Stream<DynamicTest> fileCreate1000Bytes() {
                    return fileCreateBoundaryWithExpectedFee(SERVICE_STATE_BYTES_THRESHOLD, "fileCreate1000BytesTxn");
                }

                @HapiTest
                @DisplayName("FileCreate - just above service threshold")
                Stream<DynamicTest> fileCreate1001Bytes() {
                    return fileCreateBoundaryWithExpectedFee(
                            SERVICE_STATE_BYTES_ABOVE_THRESHOLD, "fileCreate1001BytesTxn");
                }

                @HapiTest
                @DisplayName("FileCreate - just below node bytes threshold")
                Stream<DynamicTest> fileCreate1023Bytes() {
                    return fileCreateBoundaryWithExpectedFee(
                            NODE_PROCESSING_BYTES_BELOW_THRESHOLD, "fileCreate1023BytesTxn");
                }

                @HapiTest
                @DisplayName("FileCreate - at node bytes threshold")
                Stream<DynamicTest> fileCreate1024Bytes() {
                    return fileCreateBoundaryWithExpectedFee(NODE_PROCESSING_BYTES_THRESHOLD, "fileCreate1024BytesTxn");
                }

                @HapiTest
                @DisplayName("FileCreate - just above node bytes threshold")
                Stream<DynamicTest> fileCreate1025Bytes() {
                    return fileCreateBoundaryWithExpectedFee(
                            NODE_PROCESSING_BYTES_ABOVE_THRESHOLD, "fileCreate1025BytesTxn");
                }
            }

            @Nested
            @DisplayName("FileUpdate")
            class FileUpdateBoundaries {
                @HapiTest
                @DisplayName("FileUpdate - just below service threshold")
                Stream<DynamicTest> fileUpdate999Bytes() {
                    return fileUpdateBoundaryWithExpectedFee(
                            SERVICE_STATE_BYTES_BELOW_THRESHOLD, "fileUpdate999BytesTxn");
                }

                @HapiTest
                @DisplayName("FileUpdate - at service threshold")
                Stream<DynamicTest> fileUpdate1000Bytes() {
                    return fileUpdateBoundaryWithExpectedFee(SERVICE_STATE_BYTES_THRESHOLD, "fileUpdate1000BytesTxn");
                }

                @HapiTest
                @DisplayName("FileUpdate - just above service threshold")
                Stream<DynamicTest> fileUpdate1001Bytes() {
                    return fileUpdateBoundaryWithExpectedFee(
                            SERVICE_STATE_BYTES_ABOVE_THRESHOLD, "fileUpdate1001BytesTxn");
                }
            }

            @Nested
            @DisplayName("FileAppend")
            class FileAppendBoundaries {
                @HapiTest
                @DisplayName("FileAppend - just below service threshold")
                Stream<DynamicTest> fileAppend999Bytes() {
                    return fileAppendBoundaryWithExpectedFee(
                            SERVICE_STATE_BYTES_BELOW_THRESHOLD, "fileAppend999BytesTxn");
                }

                @HapiTest
                @DisplayName("FileAppend - at service threshold")
                Stream<DynamicTest> fileAppend1000Bytes() {
                    return fileAppendBoundaryWithExpectedFee(SERVICE_STATE_BYTES_THRESHOLD, "fileAppend1000BytesTxn");
                }

                @HapiTest
                @DisplayName("FileAppend - just above service threshold")
                Stream<DynamicTest> fileAppend1001Bytes() {
                    return fileAppendBoundaryWithExpectedFee(
                            SERVICE_STATE_BYTES_ABOVE_THRESHOLD, "fileAppend1001BytesTxn");
                }
            }

            @Nested
            @DisplayName("FileGetContent")
            class FileGetContentBoundaries {
                @HapiTest
                @DisplayName("FileGetContent - file just below service threshold")
                Stream<DynamicTest> fileGetContent999Bytes() {
                    return fileGetContentsBoundaryWithExpectedFee(
                            SERVICE_STATE_BYTES_BELOW_THRESHOLD, "getFileContents999BytesQuery");
                }

                @HapiTest
                @DisplayName("FileGetContent - file at service threshold")
                Stream<DynamicTest> fileGetContent1000Bytes() {
                    return fileGetContentsBoundaryWithExpectedFee(
                            SERVICE_STATE_BYTES_THRESHOLD, "getFileContents1000BytesQuery");
                }

                @HapiTest
                @DisplayName("FileGetContent - file just above service threshold")
                Stream<DynamicTest> fileGetContent1001Bytes() {
                    return fileGetContentsBoundaryWithExpectedFee(
                            SERVICE_STATE_BYTES_ABOVE_THRESHOLD, "getFileContents1001BytesQuery");
                }
            }
        }

        @Nested
        @DisplayName("Large File Scenarios")
        class LargeFileScenarios {
            @LeakyHapiTest(overrides = {"files.maxSizeKb"})
            @DisplayName("FileCreate - large file near max allowed size scales linearly")
            @HapiTest
            Stream<DynamicTest> fileCreateNearMaxAllowedSizeScalesLinearly() {
                final int largeSize = LARGE_CONTENT_BYTES;
                return hapiTest(
                        overriding("files.maxSizeKb", String.valueOf(LARGE_FILE_MAX_SIZE_KB)),
                        newKeyNamed(PAYER_KEY),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_MILLION_HBARS),
                        fileCreate("largeCreateFile")
                                .key(PAYER_KEY)
                                .contents(bytesWithLength(largeSize))
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .via("fileCreateLargeTxn"),
                        validateChargedUsdWithinWithTxnSize(
                                "fileCreateLargeTxn",
                                txnSize -> expectedFileCreateFullFeeUsd(Map.of(
                                        SIGNATURES,
                                        1L,
                                        KEYS,
                                        1L,
                                        STATE_BYTES,
                                        (long) largeSize,
                                        PROCESSING_BYTES,
                                        (long) txnSize)),
                                ALLOWED_PERCENT_DIFF));
            }

            @HapiTest
            @DisplayName("FileUpdate - update large file content scales by STATE_BYTES")
            Stream<DynamicTest> fileUpdateLargeFileScalesByStateBytes() {
                final int largeSize = LARGE_CONTENT_BYTES;
                return hapiTest(
                        newKeyNamed(PAYER_KEY),
                        newKeyListNamed("wacl", List.of(PAYER_KEY)),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_MILLION_HBARS),
                        fileCreate("largeUpdateFile")
                                .key("wacl")
                                .contents("abc")
                                .payingWith(PAYER)
                                .signedBy(PAYER),
                        fileUpdate("largeUpdateFile")
                                .contents(bytesWithLength(largeSize))
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .via("fileUpdateLargeTxn"),
                        validateChargedUsdWithinWithTxnSize(
                                "fileUpdateLargeTxn",
                                txnSize -> expectedFileUpdateFullFeeUsd(Map.of(
                                        SIGNATURES,
                                        1L,
                                        KEYS,
                                        1L,
                                        STATE_BYTES,
                                        (long) largeSize,
                                        PROCESSING_BYTES,
                                        (long) txnSize)),
                                ALLOWED_PERCENT_DIFF));
            }

            @HapiTest
            @DisplayName("FileAppend - fee based on appended bytes only, not total file size")
            Stream<DynamicTest> fileAppendLargeFileChargedByAppendedBytesOnly() {
                final int appendSize = 2 * KILOBYTE;
                return hapiTest(
                        newKeyNamed(PAYER_KEY),
                        newKeyListNamed("wacl", List.of(PAYER_KEY)),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_MILLION_HBARS),
                        fileCreate("largeAppendFile")
                                .key("wacl")
                                .contents(bytesWithLength(LARGE_CONTENT_BYTES))
                                .payingWith(PAYER)
                                .signedBy(PAYER),
                        fileAppend("largeAppendFile")
                                .content(bytesWithLength(appendSize))
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .via("fileAppendLargeTxn"),
                        validateChargedUsdWithinWithTxnSize(
                                "fileAppendLargeTxn",
                                txnSize -> expectedFileAppendFullFeeUsd(
                                        Map.of(SIGNATURES, 1L, STATE_BYTES, (long) appendSize, PROCESSING_BYTES, (long)
                                                txnSize)),
                                ALLOWED_PERCENT_DIFF));
            }

            @HapiTest
            @DisplayName("FileGetContent - fee based on total file size returned")
            Stream<DynamicTest> fileGetContentsLargeFileChargedByReturnedSize() {
                final int size = LARGE_CONTENT_BYTES;
                final double expected = FILE_GET_CONTENTS_QUERY_BASE_FEE_USD
                        + Math.max(0, size - FILE_GET_CONTENTS_INCLUDED_PROCESSING_BYTES) * PROCESSING_BYTES_FEE_USD;
                return hapiTest(
                        newKeyNamed(PAYER_KEY),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_MILLION_HBARS),
                        fileCreate("largeQueryFile")
                                .key(PAYER_KEY)
                                .contents(bytesWithLength(size))
                                .payingWith(PAYER)
                                .signedBy(PAYER),
                        getFileContents("largeQueryFile")
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .via("getLargeFileContentsQuery"),
                        validateChargedUsdForQueries("getLargeFileContentsQuery", expected, ALLOWED_PERCENT_DIFF),
                        validateNonZeroNodePaymentForQuery("getLargeFileContentsQuery"));
            }

            @HapiTest
            @DisplayName("FileDelete - deleting a large file is charged flat fee")
            Stream<DynamicTest> fileDeleteLargeFileChargedFlatFee() {
                return hapiTest(
                        newKeyNamed(PAYER_KEY),
                        newKeyListNamed("wacl", List.of(PAYER_KEY)),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_MILLION_HBARS),
                        fileCreate("largeDeleteFile")
                                .key("wacl")
                                .contents(bytesWithLength(LARGE_CONTENT_BYTES))
                                .payingWith(PAYER)
                                .signedBy(PAYER),
                        fileDelete("largeDeleteFile")
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .via("fileDeleteLargeTxn"),
                        validateChargedUsdWithinWithTxnSize(
                                "fileDeleteLargeTxn",
                                txnSize -> expectedFileDeleteFullFeeUsd(
                                        Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                ALLOWED_PERCENT_DIFF));
            }
        }
    }

    @Nested
    @DisplayName("File Service Simple Fees Negative and Corner Test Cases")
    class FileServiceSimpleFeesNegativeAndCornerTestCases {

        @Nested
        @DisplayName("Negative Scenarios - Failures on Ingest")
        class FileServiceSimpleFeesFailuresOnIngest {
            @HapiTest
            @DisplayName("FileCreate - invalid WACL signature fails on ingest")
            Stream<DynamicTest> fileCreateInvalidWaclSignatureFailsOnIngest() {
                final KeyShape payerKeyShape = threshOf(2, 3);
                final KeyShape waclShape = listOf(threshOf(2, 3));
                final SigControl invalidPayerSig = payerKeyShape.signedWith(sigs(ON, OFF, OFF));
                final SigControl invalidWaclSig = waclShape.signedWith(sigs(sigs(ON, OFF, OFF)));

                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(payerKeyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        fileCreate(FILE)
                                .waclShape(waclShape)
                                .sigControl(forKey(PAYER_KEY, invalidPayerSig), forKey(FILE, invalidWaclSig))
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .via("fileCreateInvalidSigTxn")
                                .hasPrecheck(INVALID_SIGNATURE),
                        getTxnRecord("fileCreateInvalidSigTxn").hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("FileCreate - insufficient tx fee fails on ingest")
            Stream<DynamicTest> fileCreateInsufficientTxFeeFailsOnIngest() {
                return hapiTest(
                        newKeyNamed(PAYER_KEY),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        fileCreate(FILE)
                                .key(PAYER_KEY)
                                .contents("abc")
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .fee(ONE_HBAR / 100_000)
                                .via("fileCreateInsufficientFeeTxn")
                                .hasPrecheck(INSUFFICIENT_TX_FEE),
                        getTxnRecord("fileCreateInsufficientFeeTxn").hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("FileCreate - insufficient payer balance fails on ingest")
            Stream<DynamicTest> fileCreateInsufficientPayerBalanceFailsOnIngest() {
                return hapiTest(
                        newKeyNamed(PAYER_KEY),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HBAR / 100_000),
                        fileCreate(FILE)
                                .key(PAYER_KEY)
                                .contents("abc")
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .via("fileCreateInsufficientPayerBalanceTxn")
                                .hasPrecheck(INSUFFICIENT_PAYER_BALANCE),
                        getTxnRecord("fileCreateInsufficientPayerBalanceTxn")
                                .hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("FileCreate - memo exceeds max bytes fails on ingest")
            Stream<DynamicTest> fileCreateMemoTooLongFailsOnIngest() {
                return hapiTest(
                        newKeyNamed(PAYER_KEY),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        fileCreate(FILE)
                                .key(PAYER_KEY)
                                .memo("x".repeat(FILE_MEMO_EXCEEDS_MAX_BYTES))
                                .contents("abc")
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .via("fileCreateMemoTooLongTxn")
                                .hasPrecheck(MEMO_TOO_LONG),
                        getTxnRecord("fileCreateMemoTooLongTxn").hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("FileCreate - expired transaction fails on ingest")
            Stream<DynamicTest> fileCreateExpiredTransactionFailsOnIngest() {
                final var expiredTxnId = "expiredFileCreateTxnId";

                return hapiTest(
                        newKeyNamed(PAYER_KEY),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        usableTxnIdNamed(expiredTxnId).modifyValidStart(-3_600).payerId(PAYER),
                        fileCreate(FILE)
                                .key(PAYER_KEY)
                                .contents("abc")
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .txnId(expiredTxnId)
                                .via("fileCreateExpiredTxn")
                                .hasPrecheck(TRANSACTION_EXPIRED),
                        getTxnRecord("fileCreateExpiredTxn").hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("FileCreate - transaction start too far in the future fails on ingest")
            Stream<DynamicTest> fileCreateFutureTransactionStartFailsOnIngest() {
                final var futureTxnId = "futureFileCreateTxnId";

                return hapiTest(
                        newKeyNamed(PAYER_KEY),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        usableTxnIdNamed(futureTxnId).modifyValidStart(3_600).payerId(PAYER),
                        fileCreate(FILE)
                                .key(PAYER_KEY)
                                .contents("abc")
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .txnId(futureTxnId)
                                .via("fileCreateFutureStartTxn")
                                .hasPrecheck(INVALID_TRANSACTION_START),
                        getTxnRecord("fileCreateFutureStartTxn").hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("FileCreate - invalid transaction duration fails on ingest")
            Stream<DynamicTest> fileCreateInvalidTransactionDurationFailsOnIngest() {
                return hapiTest(
                        newKeyNamed(PAYER_KEY),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        fileCreate(FILE)
                                .key(PAYER_KEY)
                                .contents("abc")
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .validDurationSecs(0)
                                .via("fileCreateInvalidDurationTxn")
                                .hasPrecheck(INVALID_TRANSACTION_DURATION),
                        getTxnRecord("fileCreateInvalidDurationTxn").hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("FileCreate - duplicate transaction fails on ingest")
            Stream<DynamicTest> fileCreateDuplicateTransactionFailsOnIngest() {
                return hapiTest(
                        newKeyNamed(PAYER_KEY),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        fileCreate("firstFile")
                                .key(PAYER_KEY)
                                .contents("abc")
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .via("firstFileCreateTxn"),
                        fileCreate("duplicateFile")
                                .key(PAYER_KEY)
                                .contents("abc")
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .txnId("firstFileCreateTxn")
                                .via("duplicateFileCreateTxn")
                                .hasPrecheck(DUPLICATE_TRANSACTION));
            }

            @HapiTest
            @DisplayName("FileUpdate - missing WACL signature fails on ingest")
            Stream<DynamicTest> fileUpdateMissingWaclSignatureFailsOnIngest() {
                final KeyShape payerKeyShape = threshOf(2, 3);
                final KeyShape waclShape = listOf(threshOf(2, 3));
                final SigControl validPayerSig = payerKeyShape.signedWith(sigs(ON, ON, OFF));
                final SigControl invalidPayerSig = payerKeyShape.signedWith(sigs(ON, OFF, OFF));
                final SigControl validWaclSig = waclShape.signedWith(sigs(sigs(ON, ON, OFF)));
                final SigControl invalidWaclSig = waclShape.signedWith(sigs(sigs(ON, OFF, OFF)));

                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(payerKeyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        fileCreate(FILE)
                                .waclShape(waclShape)
                                .sigControl(forKey(PAYER_KEY, validPayerSig), forKey(FILE, validWaclSig))
                                .contents("abc")
                                .payingWith(PAYER),
                        fileUpdate(FILE)
                                .contents("def")
                                .sigControl(forKey(PAYER_KEY, invalidPayerSig), forKey(FILE, invalidWaclSig))
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .via("fileUpdateInvalidSigTxn")
                                .hasPrecheck(INVALID_SIGNATURE),
                        getTxnRecord("fileUpdateInvalidSigTxn").hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("FileUpdate - insufficient tx fee fails on ingest")
            Stream<DynamicTest> fileUpdateInsufficientTxFeeFailsOnIngest() {
                return hapiTest(
                        newKeyNamed(PAYER_KEY),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        fileCreate(FILE)
                                .key(PAYER_KEY)
                                .contents("abc")
                                .payingWith(PAYER)
                                .signedBy(PAYER),
                        fileUpdate(FILE)
                                .contents("def")
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .fee(ONE_HBAR / 100_000)
                                .via("fileUpdateInsufficientFeeTxn")
                                .hasPrecheck(INSUFFICIENT_TX_FEE),
                        getTxnRecord("fileUpdateInsufficientFeeTxn").hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("FileDelete - missing WACL signature fails on ingest")
            Stream<DynamicTest> fileDeleteMissingWaclSignatureFailsOnIngest() {
                final KeyShape payerKeyShape = threshOf(2, 3);
                final KeyShape waclShape = listOf(threshOf(2, 3));
                final SigControl validPayerSig = payerKeyShape.signedWith(sigs(ON, ON, OFF));
                final SigControl invalidPayerSig = payerKeyShape.signedWith(sigs(ON, OFF, OFF));
                final SigControl validWaclSig = waclShape.signedWith(sigs(sigs(ON, ON, OFF)));
                final SigControl invalidWaclSig = waclShape.signedWith(sigs(sigs(ON, OFF, OFF)));

                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(payerKeyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        fileCreate(FILE)
                                .waclShape(waclShape)
                                .sigControl(forKey(PAYER_KEY, validPayerSig), forKey(FILE, validWaclSig))
                                .contents("abc")
                                .payingWith(PAYER),
                        fileDelete(FILE)
                                .sigControl(forKey(PAYER_KEY, invalidPayerSig), forKey(FILE, invalidWaclSig))
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .via("fileDeleteInvalidSigTxn")
                                .hasPrecheck(INVALID_SIGNATURE),
                        getTxnRecord("fileDeleteInvalidSigTxn").hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("FileAppend - missing WACL signature fails on ingest")
            Stream<DynamicTest> fileAppendMissingWaclSignatureFailsOnIngest() {
                final KeyShape payerKeyShape = threshOf(2, 3);
                final KeyShape waclShape = listOf(threshOf(2, 3));
                final SigControl validPayerSig = payerKeyShape.signedWith(sigs(ON, ON, OFF));
                final SigControl invalidPayerSig = payerKeyShape.signedWith(sigs(ON, OFF, OFF));
                final SigControl validWaclSig = waclShape.signedWith(sigs(sigs(ON, ON, OFF)));
                final SigControl invalidWaclSig = waclShape.signedWith(sigs(sigs(ON, OFF, OFF)));

                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(payerKeyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        fileCreate(FILE)
                                .waclShape(waclShape)
                                .sigControl(forKey(PAYER_KEY, validPayerSig), forKey(FILE, validWaclSig))
                                .contents("abc")
                                .payingWith(PAYER),
                        fileAppend(FILE)
                                .content("def")
                                .sigControl(forKey(PAYER_KEY, invalidPayerSig), forKey(FILE, invalidWaclSig))
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .via("fileAppendInvalidSigTxn")
                                .hasPrecheck(INVALID_SIGNATURE),
                        getTxnRecord("fileAppendInvalidSigTxn").hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }
        }

        @Nested
        @Tag(ONLY_SUBPROCESS)
        @DisplayName("Negative Scenarios - Failures on Handle")
        class FileServiceSimpleFeesFailuresOnHandle {
            @LeakyHapiTest
            @HapiTest
            @DisplayName("FileCreate - duplicate transaction reaches handle and payer is charged full fee")
            Stream<DynamicTest> fileCreateDuplicateTransactionFailsOnHandlePayerCharged() {
                return hapiTest(
                        newKeyNamed(PAYER_KEY),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        usableTxnIdNamed("duplicateFileCreateId").payerId(PAYER),
                        fileCreate("firstHandleDuplicateFile")
                                .key(PAYER_KEY)
                                .contents("abc")
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .setNode(4)
                                .txnId("duplicateFileCreateId")
                                .via("fileCreateDuplicateHandleTxn"),
                        fileCreate("secondHandleDuplicateFile")
                                .key(PAYER_KEY)
                                .contents("abc")
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .setNode(3)
                                .txnId("duplicateFileCreateId")
                                .via("fileCreateDuplicateHandleTxnTwo")
                                .hasPrecheck(DUPLICATE_TRANSACTION),
                        validateChargedUsdWithinWithTxnSize(
                                "fileCreateDuplicateHandleTxn",
                                txnSize -> expectedFileCreateFullFeeUsd(Map.of(
                                        SIGNATURES, 1L, KEYS, 1L, STATE_BYTES, 3L, PROCESSING_BYTES, (long) txnSize)),
                                ALLOWED_PERCENT_DIFF));
            }

            @HapiTest
            @DisplayName("FileUpdate - file does not exist charged base fee")
            Stream<DynamicTest> fileUpdateInvalidFileIdFailsOnHandleAndChargesBaseFee() {
                return hapiTest(
                        newKeyNamed(PAYER_KEY),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        fileUpdate("1.2.3")
                                .contents("abc")
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .via("fileUpdateInvalidFileIdTxn")
                                .hasKnownStatus(INVALID_FILE_ID),
                        validateChargedUsdWithinWithTxnSize(
                                "fileUpdateInvalidFileIdTxn", ignored -> 0.0, ALLOWED_PERCENT_DIFF));
            }

            @HapiTest
            @DisplayName("FileUpdate - file already deleted charged base fee")
            Stream<DynamicTest> fileUpdateDeletedFileFailsOnHandleAndChargesBaseFee() {
                return hapiTest(
                        newKeyNamed(PAYER_KEY),
                        newKeyListNamed("wacl", List.of(PAYER_KEY)),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        fileCreate(FILE)
                                .key("wacl")
                                .contents("abc")
                                .payingWith(PAYER)
                                .signedBy(PAYER),
                        fileDelete(FILE).payingWith(PAYER).signedBy(PAYER),
                        fileUpdate(FILE)
                                .contents("def")
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .via("fileUpdateDeletedFileTxn")
                                .hasKnownStatus(FILE_DELETED),
                        validateChargedUsdWithinWithTxnSize(
                                "fileUpdateDeletedFileTxn",
                                FileServiceSimpleFeesTest::expectedFileUpdateBaseOnlyFeeUsd,
                                ALLOWED_PERCENT_DIFF));
            }

            @HapiTest
            @DisplayName("FileDelete - file already deleted charged base fee")
            Stream<DynamicTest> fileDeleteDeletedFileFailsOnHandleAndChargesBaseFee() {
                return hapiTest(
                        newKeyNamed(PAYER_KEY),
                        newKeyListNamed("wacl", List.of(PAYER_KEY)),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        fileCreate(FILE)
                                .key("wacl")
                                .contents("abc")
                                .payingWith(PAYER)
                                .signedBy(PAYER),
                        fileDelete(FILE).payingWith(PAYER).signedBy(PAYER),
                        fileDelete(FILE)
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .via("fileDeleteDeletedFileTxn")
                                .hasKnownStatus(FILE_DELETED),
                        validateChargedUsdWithinWithTxnSize(
                                "fileDeleteDeletedFileTxn",
                                txnSize -> expectedNodeAndNetworkFeeUsd(1L, txnSize) + FILE_DELETE_BASE_FEE_USD,
                                ALLOWED_PERCENT_DIFF));
            }

            @HapiTest
            @DisplayName("FileAppend - file already deleted charged base fee")
            Stream<DynamicTest> fileAppendDeletedFileFailsOnHandleAndChargesBaseFee() {
                return hapiTest(
                        newKeyNamed(PAYER_KEY),
                        newKeyListNamed("wacl", List.of(PAYER_KEY)),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        fileCreate(FILE)
                                .key("wacl")
                                .contents("abc")
                                .payingWith(PAYER)
                                .signedBy(PAYER),
                        fileDelete(FILE).payingWith(PAYER).signedBy(PAYER),
                        fileAppend(FILE)
                                .content("def")
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .via("fileAppendDeletedFileTxn")
                                .hasKnownStatus(FILE_DELETED),
                        validateChargedUsdWithinWithTxnSize(
                                "fileAppendDeletedFileTxn",
                                txnSize -> expectedNodeAndNetworkFeeUsd(1L, txnSize) + FILE_APPEND_BASE_FEE_USD,
                                ALLOWED_PERCENT_DIFF));
            }

            @LeakyHapiTest(overrides = {"files.maxSizeKb"})
            @HapiTest
            @DisplayName("FileCreate - content exceeds max allowed size charged base fee")
            Stream<DynamicTest> fileCreateMaxSizeExceededFailsOnHandleAndChargesBaseFee() {
                final int oversizedCreateBytes = 2 * KILOBYTE;
                return hapiTest(
                        overriding("files.maxSizeKb", String.valueOf(SMALL_FILE_MAX_SIZE_KB)),
                        newKeyNamed(PAYER_KEY),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        fileCreate("oversizedCreateFile")
                                .key(PAYER_KEY)
                                .contents(bytesWithLength(oversizedCreateBytes))
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .via("fileCreateMaxSizeExceededTxn")
                                .hasKnownStatus(MAX_FILE_SIZE_EXCEEDED),
                        validateChargedUsdWithinWithTxnSize(
                                "fileCreateMaxSizeExceededTxn",
                                txnSize -> expectedFileCreateFullFeeUsd(Map.of(
                                        SIGNATURES,
                                        1L,
                                        KEYS,
                                        1L,
                                        STATE_BYTES,
                                        (long) oversizedCreateBytes,
                                        PROCESSING_BYTES,
                                        (long) txnSize)),
                                ALLOWED_PERCENT_DIFF));
            }

            @LeakyHapiTest(overrides = {"files.maxSizeKb"})
            @HapiTest
            @DisplayName("FileAppend - append exceeding max file size charged base fee")
            Stream<DynamicTest> fileAppendMaxSizeExceededFailsOnHandleAndChargesBaseFee() {
                return hapiTest(
                        overriding("files.maxSizeKb", String.valueOf(SMALL_FILE_MAX_SIZE_KB)),
                        newKeyNamed(PAYER_KEY),
                        newKeyListNamed("wacl", List.of(PAYER_KEY)),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        fileCreate("appendLimitFile")
                                .key("wacl")
                                .contents(bytesWithLength(KILOBYTE - 1))
                                .payingWith(PAYER)
                                .signedBy(PAYER),
                        fileAppend("appendLimitFile")
                                .content(bytesWithLength(2))
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .via("fileAppendMaxSizeExceededTxn")
                                .hasKnownStatus(MAX_FILE_SIZE_EXCEEDED),
                        validateChargedUsdWithinWithTxnSize(
                                "fileAppendMaxSizeExceededTxn",
                                txnSize -> expectedNodeAndNetworkFeeUsd(1L, txnSize) + FILE_APPEND_BASE_FEE_USD,
                                ALLOWED_PERCENT_DIFF));
            }
        }

        @Nested
        @DisplayName("Corner Cases for File Service Simple Fees")
        class CornerCasesForFileServiceSimpleFees {
            @HapiTest
            @DisplayName("FileCreate - empty content still charges base fee")
            Stream<DynamicTest> fileCreateEmptyContentStillChargesBaseFee() {
                return hapiTest(
                        newKeyNamed(PAYER_KEY),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        fileCreate("emptyContentFile")
                                .key(PAYER_KEY)
                                .contents(bytesWithLength(0))
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .via("fileCreateEmptyContentTxn"),
                        validateChargedUsdWithinWithTxnSize(
                                "fileCreateEmptyContentTxn",
                                txnSize -> expectedFileCreateFullFeeUsd(Map.of(
                                        SIGNATURES, 1L, KEYS, 1L, STATE_BYTES, 0L, PROCESSING_BYTES, (long) txnSize)),
                                ALLOWED_PERCENT_DIFF));
            }

            @HapiTest
            @DisplayName("FileGetInfo - large file query remains flat-fee")
            Stream<DynamicTest> fileGetInfoLargeFileIsFlatFee() {
                return hapiTest(
                        newKeyNamed(PAYER_KEY),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_MILLION_HBARS),
                        fileCreate("largeInfoFile")
                                .key(PAYER_KEY)
                                .contents(bytesWithLength(LARGE_CONTENT_BYTES))
                                .payingWith(PAYER)
                                .signedBy(PAYER),
                        getFileInfo("largeInfoFile")
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .via("getLargeFileInfoQuery"),
                        validateChargedUsdForQueries(
                                "getLargeFileInfoQuery", FILE_GET_INFO_QUERY_BASE_FEE_USD, ALLOWED_PERCENT_DIFF),
                        validateNonZeroNodePaymentForQuery("getLargeFileInfoQuery"));
            }
        }
    }

    private Stream<DynamicTest> fileCreateBoundaryWithExpectedFee(final int contentBytes, final String txnName) {
        return hapiTest(
                newKeyNamed(PAYER_KEY),
                cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                fileCreate(FILE)
                        .key(PAYER_KEY)
                        .contents(bytesWithLength(contentBytes))
                        .payingWith(PAYER)
                        .signedBy(PAYER)
                        .via(txnName),
                validateChargedUsdWithinWithTxnSize(
                        txnName,
                        txnSize -> expectedFileCreateFullFeeUsd(Map.of(
                                SIGNATURES, 1L, KEYS, 1L, STATE_BYTES, (long) contentBytes, PROCESSING_BYTES, (long)
                                        txnSize)),
                        ALLOWED_PERCENT_DIFF));
    }

    private Stream<DynamicTest> fileUpdateBoundaryWithExpectedFee(final int contentBytes, final String txnName) {
        return hapiTest(
                newKeyNamed(PAYER_KEY),
                newKeyListNamed("wacl", List.of(PAYER_KEY)),
                cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                fileCreate(FILE).key("wacl").contents("abc").payingWith(PAYER).signedBy(PAYER),
                fileUpdate(FILE)
                        .contents(bytesWithLength(contentBytes))
                        .payingWith(PAYER)
                        .signedBy(PAYER)
                        .via(txnName),
                validateChargedUsdWithinWithTxnSize(
                        txnName,
                        txnSize -> expectedFileUpdateFullFeeUsd(Map.of(
                                SIGNATURES, 1L, KEYS, 1L, STATE_BYTES, (long) contentBytes, PROCESSING_BYTES, (long)
                                        txnSize)),
                        ALLOWED_PERCENT_DIFF));
    }

    private Stream<DynamicTest> fileAppendBoundaryWithExpectedFee(final int appendBytes, final String txnName) {
        return hapiTest(
                newKeyNamed(PAYER_KEY),
                newKeyListNamed("wacl", List.of(PAYER_KEY)),
                cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                fileCreate(FILE).key("wacl").contents("abc").payingWith(PAYER).signedBy(PAYER),
                fileAppend(FILE)
                        .content(bytesWithLength(appendBytes))
                        .payingWith(PAYER)
                        .signedBy(PAYER)
                        .via(txnName),
                validateChargedUsdWithinWithTxnSize(
                        txnName,
                        txnSize -> expectedFileAppendFullFeeUsd(Map.of(
                                SIGNATURES, 1L, STATE_BYTES, (long) appendBytes, PROCESSING_BYTES, (long) txnSize)),
                        ALLOWED_PERCENT_DIFF));
    }

    private Stream<DynamicTest> fileGetContentsBoundaryWithExpectedFee(final int contentBytes, final String queryName) {
        final double expected = FILE_GET_CONTENTS_QUERY_BASE_FEE_USD
                + Math.max(0, contentBytes - FILE_GET_CONTENTS_INCLUDED_PROCESSING_BYTES) * PROCESSING_BYTES_FEE_USD;

        return hapiTest(
                newKeyNamed(PAYER_KEY),
                cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                fileCreate(FILE)
                        .key(PAYER_KEY)
                        .contents(bytesWithLength(contentBytes))
                        .payingWith(PAYER)
                        .signedBy(PAYER),
                getFileContents(FILE).payingWith(PAYER).signedBy(PAYER).via(queryName),
                validateChargedUsdForQueries(queryName, expected, ALLOWED_PERCENT_DIFF),
                validateNonZeroNodePaymentForQuery(queryName));
    }

    private static double expectedFileUpdateFullFeeUsd(final Map<Extra, Long> extras) {
        final long sigs = extras.getOrDefault(SIGNATURES, 0L);
        final long keys = extras.getOrDefault(KEYS, 0L);
        final long stateBytes = extras.getOrDefault(STATE_BYTES, 0L);
        final int txnSize = Math.toIntExact(extras.getOrDefault(PROCESSING_BYTES, 0L));
        final double nodeAndNetwork = expectedNodeAndNetworkFeeUsd(sigs, txnSize);
        final double serviceBytesExtras = Math.max(0, stateBytes - SERVICE_STATE_BYTES_THRESHOLD) * STATE_BYTES_FEE_USD;
        final double serviceKeysExtras = Math.max(0, keys - FILE_UPDATE_INCLUDED_KEYS) * KEYS_FEE_USD;
        return nodeAndNetwork + FILE_UPDATE_BASE_FEE_USD + serviceBytesExtras + serviceKeysExtras;
    }

    private static double expectedFileUpdateBaseOnlyFeeUsd(final int txnSize) {
        return expectedNodeAndNetworkFeeUsd(1L, txnSize) + FILE_UPDATE_BASE_FEE_USD;
    }

    private static double expectedNodeAndNetworkFeeUsd(final long sigs, final int txnSize) {
        final long sigExtrasNode = Math.max(0, sigs - NODE_INCLUDED_SIGNATURES);
        final double nodeFee = NODE_BASE_FEE_USD + sigExtrasNode * SIGNATURE_FEE_USD + nodeFeeFromBytesUsd(txnSize);
        return nodeFee * (NETWORK_MULTIPLIER + 1);
    }

    private static byte[] bytesWithLength(final int length) {
        final var result = new byte[length];
        Arrays.fill(result, (byte) 'a');
        return result;
    }
}
