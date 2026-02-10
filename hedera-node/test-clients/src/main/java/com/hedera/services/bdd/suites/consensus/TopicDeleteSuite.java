// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.consensus;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTopicInfo;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.asTopicId;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.deleteTopic;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doWithStartupConfig;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.submitModified;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsd;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateInnerTxnChargedUsd;
import static com.hedera.services.bdd.spec.utilops.mod.ModificationUtils.withSuccessivelyVariedBodyIds;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.FeeParam.SIGNATURES;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.FeeParam.TXN_SIZE;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTopicDeleteFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.validateChargedUsdWithinWithTxnSize;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.validateInnerChargedUsdWithinWithTxnSize;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOPIC_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.UNAUTHORIZED;

import com.hedera.services.bdd.junit.HapiTest;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;

import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

public class TopicDeleteSuite {
    @HapiTest
    final Stream<DynamicTest> idVariantsTreatedAsExpected() {
        return hapiTest(
                newKeyNamed("adminKey"),
                createTopic("topic").adminKeyName("adminKey"),
                submitModified(withSuccessivelyVariedBodyIds(), () -> deleteTopic("topic")));
    }

    @HapiTest
    final Stream<DynamicTest> pureCheckFails() {
        return hapiTest(
                cryptoCreate("nonTopicId"),
                deleteTopic(spec -> asTopicId(spec.registry().getAccountID("nonTopicId")))
                        .hasPrecheck(INVALID_TOPIC_ID),
                deleteTopic((String) null).hasPrecheck(INVALID_TOPIC_ID));
    }

    @HapiTest
    final Stream<DynamicTest> cannotDeleteAccountAsTopic() {
        return hapiTest(
                cryptoCreate("nonTopicId"),
                deleteTopic(spec -> asTopicId(spec.registry().getAccountID("nonTopicId")))
                        .hasKnownStatus(INVALID_TOPIC_ID));
    }

    @HapiTest
    final Stream<DynamicTest> topicIdIsValidated() {
        return hapiTest(
                deleteTopic((String) null).hasKnownStatus(INVALID_TOPIC_ID),
                deleteTopic("100.232.4534") // non-existent id
                        .hasKnownStatus(INVALID_TOPIC_ID));
    }

    @HapiTest
    final Stream<DynamicTest> noAdminKeyCannotDelete() {
        return hapiTest(createTopic("testTopic"), deleteTopic("testTopic").hasKnownStatus(UNAUTHORIZED));
    }

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> deleteWithAdminKey() {
        return hapiTest(
                newKeyNamed("adminKey"),
                createTopic("testTopic").adminKeyName("adminKey"),
                deleteTopic("testTopic").hasPrecheck(ResponseCodeEnum.OK),
                getTopicInfo("testTopic").hasCostAnswerPrecheck(INVALID_TOPIC_ID));
    }

    @HapiTest
    final Stream<DynamicTest> deleteFailedWithWrongKey() {
        long PAYER_BALANCE = 1_999_999_999L;
        return hapiTest(
                newKeyNamed("adminKey"),
                newKeyNamed("wrongKey"),
                cryptoCreate("payer").balance(PAYER_BALANCE),
                createTopic("testTopic").adminKeyName("adminKey"),
                deleteTopic("testTopic")
                        .payingWith("payer")
                        .signedBy("payer", "wrongKey")
                        .hasKnownStatus(ResponseCodeEnum.INVALID_SIGNATURE));
    }

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> feeAsExpected() {
        return hapiTest(
                cryptoCreate("payer"),
                createTopic("testTopic").adminKeyName("payer"),
                deleteTopic("testTopic").blankMemo().payingWith("payer").via("topicDelete"),
                doWithStartupConfig("fees.simpleFeesEnabled", flag -> {
                    if ("true".equals(flag)) {
                        return validateChargedUsdWithinWithTxnSize(
                                "topicDelete",
                                txnSize -> expectedTopicDeleteFullFeeUsd(Map.of(
                                        SIGNATURES, 1,
                                        TXN_SIZE, txnSize)), 0.001);
                    } else {
                        return validateChargedUsd("topicDelete", 0.005);
                    }
                }));
    }
}
