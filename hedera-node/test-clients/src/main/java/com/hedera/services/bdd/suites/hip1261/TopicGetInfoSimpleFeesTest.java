// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1261;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.SIMPLE_FEES;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTopicInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.deleteTopic;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsdForQueries;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOPIC_ID;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
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
@Tag(MATS)
@Tag(SIMPLE_FEES)
@HapiTestLifecycle
public class TopicGetInfoSimpleFeesTest {

    private static final double EXPECTED_CRYPTO_TRANSFER_FEE = 0.0001;
    private static final String PAYER = "payer";
    private static final String ADMIN_KEY = "adminKey";
    private static final String SUBMIT_KEY = "submitKey";
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
                    createTopic(TOPIC).payingWith(PAYER).signedBy(PAYER).fee(ONE_HUNDRED_HBARS),
                    getTopicInfo(TOPIC).payingWith(PAYER).via("getTopicInfoQuery"),
                    validateChargedUsdForQueries("getTopicInfoQuery", EXPECTED_CRYPTO_TRANSFER_FEE, 1.0));
        }

        @HapiTest
        @DisplayName("GetTopicInfo - topic with admin key")
        final Stream<DynamicTest> getTopicInfoWithAdminKey() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    newKeyNamed(ADMIN_KEY),
                    createTopic(TOPIC)
                            .adminKeyName(ADMIN_KEY)
                            .payingWith(PAYER)
                            .signedBy(PAYER, ADMIN_KEY)
                            .fee(ONE_HUNDRED_HBARS),
                    getTopicInfo(TOPIC).payingWith(PAYER).via("getTopicInfoQuery"),
                    validateChargedUsdForQueries("getTopicInfoQuery", EXPECTED_CRYPTO_TRANSFER_FEE, 1.0));
        }

        @HapiTest
        @DisplayName("GetTopicInfo - topic with submit key")
        final Stream<DynamicTest> getTopicInfoWithSubmitKey() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    newKeyNamed(SUBMIT_KEY),
                    createTopic(TOPIC)
                            .submitKeyName(SUBMIT_KEY)
                            .payingWith(PAYER)
                            .signedBy(PAYER)
                            .fee(ONE_HUNDRED_HBARS),
                    getTopicInfo(TOPIC).payingWith(PAYER).via("getTopicInfoQuery"),
                    validateChargedUsdForQueries("getTopicInfoQuery", EXPECTED_CRYPTO_TRANSFER_FEE, 1.0));
        }

        @HapiTest
        @DisplayName("GetTopicInfo - topic with admin and submit keys")
        final Stream<DynamicTest> getTopicInfoWithBothKeys() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    newKeyNamed(ADMIN_KEY),
                    newKeyNamed(SUBMIT_KEY),
                    createTopic(TOPIC)
                            .adminKeyName(ADMIN_KEY)
                            .submitKeyName(SUBMIT_KEY)
                            .payingWith(PAYER)
                            .signedBy(PAYER, ADMIN_KEY)
                            .fee(ONE_HUNDRED_HBARS),
                    getTopicInfo(TOPIC).payingWith(PAYER).via("getTopicInfoQuery"),
                    validateChargedUsdForQueries("getTopicInfoQuery", EXPECTED_CRYPTO_TRANSFER_FEE, 1.0));
        }

        @HapiTest
        @DisplayName("GetTopicInfo - topic with memo")
        final Stream<DynamicTest> getTopicInfoWithMemo() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    createTopic(TOPIC)
                            .memo("This is a test topic memo")
                            .payingWith(PAYER)
                            .signedBy(PAYER)
                            .fee(ONE_HUNDRED_HBARS),
                    getTopicInfo(TOPIC).payingWith(PAYER).via("getTopicInfoQuery"),
                    validateChargedUsdForQueries("getTopicInfoQuery", EXPECTED_CRYPTO_TRANSFER_FEE, 1.0));
        }
    }

    @Nested
    @DisplayName("GetTopicInfo Simple Fees Negative Test Cases")
    class GetTopicInfoSimpleFeesNegativeTestCases {

        @HapiTest
        @DisplayName("GetTopicInfo - invalid topic fails")
        final Stream<DynamicTest> getTopicInfoInvalidTopicFails() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    getTopicInfo("0.0.99999999") // Invalid topic
                            .payingWith(PAYER)
                            .hasCostAnswerPrecheck(INVALID_TOPIC_ID));
        }

        @HapiTest
        @DisplayName("GetTopicInfo - deleted topic fails")
        final Stream<DynamicTest> getTopicInfoDeletedTopicFails() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    newKeyNamed(ADMIN_KEY),
                    createTopic(TOPIC)
                            .adminKeyName(ADMIN_KEY)
                            .payingWith(PAYER)
                            .signedBy(PAYER, ADMIN_KEY)
                            .fee(ONE_HUNDRED_HBARS),
                    deleteTopic(TOPIC)
                            .payingWith(PAYER)
                            .signedBy(PAYER, ADMIN_KEY)
                            .fee(ONE_HUNDRED_HBARS),
                    getTopicInfo(TOPIC).payingWith(PAYER).hasCostAnswerPrecheck(INVALID_TOPIC_ID));
        }
    }
}
