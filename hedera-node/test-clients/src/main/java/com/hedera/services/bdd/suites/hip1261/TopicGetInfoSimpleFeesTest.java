// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1261;

import static com.hedera.services.bdd.junit.TestTags.SIMPLE_FEES;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.keys.ControlForKey.forKey;
import static com.hedera.services.bdd.spec.keys.KeyShape.SIMPLE;
import static com.hedera.services.bdd.spec.keys.KeyShape.sigs;
import static com.hedera.services.bdd.spec.keys.KeyShape.threshOf;
import static com.hedera.services.bdd.spec.keys.SigControl.ON;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTopicInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.deleteTopic;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedAccount;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedGetTopicInfoFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.validateChargedUsdWithinWithTxnSize;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOPIC_ID;
import static org.hiero.hapi.support.fees.Extra.PROCESSING_BYTES;
import static org.hiero.hapi.support.fees.Extra.SIGNATURES;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.keys.SigControl;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;

/**
 * Tests for GetTopicInfo simple fees.
 * Validates that query fees are correctly calculated.
 */
@Tag(SIMPLE_FEES)
@HapiTestLifecycle
public class TopicGetInfoSimpleFeesTest {

    private static final String PAYER = "payer";
    private static final String ADMIN_KEY = "adminKey";
    private static final String TOPIC = "testTopic";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of("fees.simpleFeesEnabled", "true"));
    }

    @Nested
    @DisplayName("GetTopicInfo Simple Fees Positive Test Cases")
    class GetTopicInfoSimpleFeesPositiveTestCases {

        @HapiTest
        @DisplayName("GetTopicInfo - base query fee")
        final Stream<DynamicTest> getTopicInfoBaseFee() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    createTopic(TOPIC).payingWith(PAYER).signedBy(PAYER),
                    getTopicInfo(TOPIC).payingWith(PAYER).via("getTopicInfoQuery"),
                    validateChargedUsdWithinWithTxnSize(
                            "getTopicInfoQuery",
                            txnSize -> expectedGetTopicInfoFullFeeUsd(
                                    Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount("getTopicInfoQuery", PAYER));
        }

        @HapiTest
        @DisplayName("GetTopicInfo - topic with admin and submit keys - same base fee")
        final Stream<DynamicTest> getTopicInfoWithAllTopicKeysFee() {
            final String SUBMIT_KEY = "submitKey";
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    newKeyNamed(ADMIN_KEY),
                    newKeyNamed(SUBMIT_KEY),
                    createTopic(TOPIC)
                            .adminKeyName(ADMIN_KEY)
                            .submitKeyName(SUBMIT_KEY)
                            .payingWith(PAYER)
                            .signedBy(PAYER, ADMIN_KEY),
                    getTopicInfo(TOPIC).payingWith(PAYER).via("getTopicInfoQuery"),
                    validateChargedUsdWithinWithTxnSize(
                            "getTopicInfoQuery",
                            txnSize -> expectedGetTopicInfoFullFeeUsd(
                                    Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount("getTopicInfoQuery", PAYER));
        }

        @HapiTest
        @DisplayName("GetTopicInfo - large payer key charges extra signatures fee")
        final Stream<DynamicTest> getTopicInfoLargePayerKeyExtraFee() {
            final String PAYER_KEY = "payerKey";
            KeyShape largeKeyShape = threshOf(
                    1, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE,
                    SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE);
            SigControl allSigning = largeKeyShape.signedWith(
                    sigs(ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON));

            return hapiTest(
                    newKeyNamed(PAYER_KEY).shape(largeKeyShape),
                    cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                    createTopic(TOPIC).payingWith(PAYER).signedBy(PAYER),
                    getTopicInfo(TOPIC)
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, allSigning))
                            .via("getTopicInfoQuery"),
                    validateChargedUsdWithinWithTxnSize(
                            "getTopicInfoQuery",
                            txnSize -> expectedGetTopicInfoFullFeeUsd(
                                    Map.of(SIGNATURES, 20L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount("getTopicInfoQuery", PAYER));
        }
    }

    @Nested
    @DisplayName("GetTopicInfo Simple Fees Negative Test Cases")
    class GetTopicInfoSimpleFeesNegativeTestCases {

        @HapiTest
        @DisplayName("GetTopicInfo - invalid topic fails - no fee charged")
        final Stream<DynamicTest> getTopicInfoInvalidTopicFails() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    getTopicInfo("0.0.99999999") // Invalid topic
                            .payingWith(PAYER)
                            .hasCostAnswerPrecheck(INVALID_TOPIC_ID));
        }

        @HapiTest
        @DisplayName("GetTopicInfo - deleted topic fails - no fee charged")
        final Stream<DynamicTest> getTopicInfoDeletedTopicFails() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    newKeyNamed(ADMIN_KEY),
                    createTopic(TOPIC).adminKeyName(ADMIN_KEY).payingWith(PAYER).signedBy(PAYER, ADMIN_KEY),
                    deleteTopic(TOPIC).payingWith(PAYER).signedBy(PAYER, ADMIN_KEY),
                    getTopicInfo(TOPIC).payingWith(PAYER).hasCostAnswerPrecheck(INVALID_TOPIC_ID));
        }
    }
}
