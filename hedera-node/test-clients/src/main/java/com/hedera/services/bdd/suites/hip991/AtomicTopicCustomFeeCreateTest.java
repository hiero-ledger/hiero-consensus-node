// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip991;

import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.keys.KeyShape.SIMPLE;
import static com.hedera.services.bdd.spec.keys.KeyShape.threshOf;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAliasedAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTopicInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenFreeze;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenPause;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedConsensusHbarFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedConsensusHtsFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeTests.expectedConsensusFixedHTSFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeTests.expectedConsensusFixedHbarFee;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_BILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SHAPE;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static com.hedera.services.bdd.suites.crypto.AutoCreateUtils.createHollowAccountFrom;
import static com.hedera.services.bdd.suites.crypto.AutoCreateUtils.updateSpecFor;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_FROZEN_FOR_TOKEN;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.AMOUNT_EXCEEDS_TOKEN_MAX_SUPPLY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CUSTOM_FEES_LIST_TOO_LONG;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CUSTOM_FEE_MUST_BE_POSITIVE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CUSTOM_FEE_NOT_FULLY_SPECIFIED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.FEE_EXEMPT_KEY_LIST_CONTAINS_DUPLICATED_KEYS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_CUSTOM_FEE_SCHEDULE_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_KEY_IN_FEE_EXEMPT_KEY_LIST;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_ID_IN_CUSTOM_FEES;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MAX_ENTRIES_FOR_FEE_EXEMPT_KEY_LIST_EXCEEDED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.keys.SigControl;
import com.hederahashgraph.api.proto.java.FixedCustomFee;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.TokenSupplyType;
import com.hederahashgraph.api.proto.java.TokenType;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;

// This test cases are direct copies of TopicCustomFeeCreateTest. The difference here is that
// we are wrapping the operations in an atomic batch to confirm the fees are the same
@DisplayName("Topic custom fees")
class AtomicTopicCustomFeeCreateTest extends TopicCustomFeeBase {

    private static final String BATCH_OPERATOR = "batchOperator";

    @Nested
    @DisplayName("Positive scenarios")
    class TopicCreatePositiveScenarios {

        static final String COLLECTOR = "collector";

        @BeforeAll
        static void beforeAll(@NonNull final TestLifecycle lifecycle) {
            lifecycle.doAdhoc(setupBaseKeys());
        }

        @HapiTest
        @DisplayName("Create topic with all keys")
        // TOPIC_FEE_001
        final Stream<DynamicTest> createTopicWithAllKeys() {
            return hapiTest(flattened(
                    cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                    atomicBatch(createTopic(TOPIC)
                                    .adminKeyName(ADMIN_KEY)
                                    .submitKeyName(SUBMIT_KEY)
                                    .feeScheduleKeyName(FEE_SCHEDULE_KEY)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR),
                    getTopicInfo(TOPIC)
                            .hasAdminKey(ADMIN_KEY)
                            .hasSubmitKey(SUBMIT_KEY)
                            .hasFeeScheduleKey(FEE_SCHEDULE_KEY)));
        }

        @HapiTest
        @DisplayName("Create topic with submitKey and feeScheduleKey")
        // TOPIC_FEE_002
        final Stream<DynamicTest> createTopicWithSubmitKeyAndFeeScheduleKey() {
            return hapiTest(
                    cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                    atomicBatch(createTopic(TOPIC)
                                    .submitKeyName(SUBMIT_KEY)
                                    .feeScheduleKeyName(FEE_SCHEDULE_KEY)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR),
                    getTopicInfo(TOPIC).hasSubmitKey(SUBMIT_KEY).hasFeeScheduleKey(FEE_SCHEDULE_KEY));
        }

        @HapiTest
        @DisplayName("Create topic with only feeScheduleKey")
        // TOPIC_FEE_003
        final Stream<DynamicTest> createTopicWithOnlyFeeScheduleKey() {
            return hapiTest(
                    cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                    atomicBatch(createTopic(TOPIC)
                                    .feeScheduleKeyName(FEE_SCHEDULE_KEY)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR),
                    getTopicInfo(TOPIC).hasFeeScheduleKey(FEE_SCHEDULE_KEY));
        }

        @HapiTest
        @DisplayName("Create topic with ECDSA feeScheduleKey")
        // TOPIC_FEE_005
        final Stream<DynamicTest> createTopicWithECDSAFeeScheduleKey() {
            return hapiTest(
                    cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                    atomicBatch(createTopic(TOPIC)
                                    .feeScheduleKeyName(FEE_SCHEDULE_KEY_ECDSA)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR),
                    getTopicInfo(TOPIC).hasFeeScheduleKey(FEE_SCHEDULE_KEY_ECDSA));
        }

        @HapiTest
        @DisplayName("Create topic with threshold feeScheduleKey")
        // TOPIC_FEE_006
        final Stream<DynamicTest> createTopicWithThresholdFeeScheduleKey() {
            final var threshKey = "threshKey";
            return hapiTest(
                    cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                    newKeyNamed(threshKey).shape(KeyShape.threshOf(1, KeyShape.SIMPLE, KeyShape.SIMPLE)),
                    atomicBatch(createTopic(TOPIC).feeScheduleKeyName(threshKey).batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR),
                    getTopicInfo(TOPIC).hasFeeScheduleKey(threshKey));
        }

        @HapiTest
        @DisplayName("Create topic with 1 Hbar fixed fee")
        // TOPIC_FEE_008
        final Stream<DynamicTest> createTopicWithOneHbarFixedFee() {
            return hapiTest(
                    cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                    cryptoCreate(COLLECTOR),
                    atomicBatch(createTopic(TOPIC)
                                    .adminKeyName(ADMIN_KEY)
                                    .submitKeyName(SUBMIT_KEY)
                                    .feeScheduleKeyName(FEE_SCHEDULE_KEY)
                                    .withConsensusCustomFee(fixedConsensusHbarFee(ONE_HBAR, COLLECTOR))
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR),
                    getTopicInfo(TOPIC)
                            .hasAdminKey(ADMIN_KEY)
                            .hasSubmitKey(SUBMIT_KEY)
                            .hasFeeScheduleKey(FEE_SCHEDULE_KEY)
                            .hasCustomFee(expectedConsensusFixedHbarFee(ONE_HBAR, COLLECTOR)));
        }

        @HapiTest
        @DisplayName("Create topic with 1 HTS fixed fee")
        // TOPIC_FEE_009
        final Stream<DynamicTest> createTopicWithOneHTSFixedFee() {
            return hapiTest(
                    cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                    cryptoCreate(COLLECTOR),
                    tokenCreate("testToken")
                            .tokenType(TokenType.FUNGIBLE_COMMON)
                            .initialSupply(500),
                    tokenAssociate(COLLECTOR, "testToken"),
                    atomicBatch(createTopic(TOPIC)
                                    .adminKeyName(ADMIN_KEY)
                                    .submitKeyName(SUBMIT_KEY)
                                    .feeScheduleKeyName(FEE_SCHEDULE_KEY)
                                    .withConsensusCustomFee(fixedConsensusHtsFee(1, "testToken", COLLECTOR))
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR),
                    getTopicInfo(TOPIC)
                            .hasAdminKey(ADMIN_KEY)
                            .hasSubmitKey(SUBMIT_KEY)
                            .hasFeeScheduleKey(FEE_SCHEDULE_KEY)
                            .hasCustomFee(expectedConsensusFixedHTSFee(1, "testToken", COLLECTOR)));
        }

        @HapiTest
        @DisplayName("Create topic with 1 HBAR fixed fee with max amount")
        // TOPIC_FEE_010
        final Stream<DynamicTest> createTopicMaxHbarAmount() {
            return hapiTest(
                    cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                    cryptoCreate(COLLECTOR),
                    atomicBatch(createTopic(TOPIC)
                                    .adminKeyName(ADMIN_KEY)
                                    .submitKeyName(SUBMIT_KEY)
                                    .feeScheduleKeyName(FEE_SCHEDULE_KEY)
                                    .withConsensusCustomFee(fixedConsensusHbarFee(50 * ONE_BILLION_HBARS, COLLECTOR))
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR),
                    getTopicInfo(TOPIC)
                            .hasAdminKey(ADMIN_KEY)
                            .hasSubmitKey(SUBMIT_KEY)
                            .hasFeeScheduleKey(FEE_SCHEDULE_KEY)
                            .hasCustomFee(expectedConsensusFixedHbarFee(50 * ONE_BILLION_HBARS, COLLECTOR)));
        }

        @HapiTest
        @DisplayName("Create topic with 1 HTS fixed with max supply")
        // TOPIC_FEE_011
        final Stream<DynamicTest> createTopicMaxTokenSupplyFee() {
            var maxSupply = 10_000;
            return hapiTest(
                    cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                    cryptoCreate(COLLECTOR),
                    tokenCreate("finiteToken")
                            .supplyType(TokenSupplyType.FINITE)
                            .tokenType(TokenType.FUNGIBLE_COMMON)
                            .initialSupply(500)
                            .maxSupply(maxSupply),
                    tokenAssociate(COLLECTOR, "finiteToken"),
                    atomicBatch(createTopic(TOPIC)
                                    .adminKeyName(ADMIN_KEY)
                                    .submitKeyName(SUBMIT_KEY)
                                    .feeScheduleKeyName(FEE_SCHEDULE_KEY)
                                    .withConsensusCustomFee(fixedConsensusHtsFee(maxSupply, "finiteToken", COLLECTOR))
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR),
                    getTopicInfo(TOPIC)
                            .hasAdminKey(ADMIN_KEY)
                            .hasSubmitKey(SUBMIT_KEY)
                            .hasFeeScheduleKey(FEE_SCHEDULE_KEY)
                            .hasCustomFee(expectedConsensusFixedHTSFee(maxSupply, "finiteToken", COLLECTOR)));
        }

        @HapiTest
        @DisplayName("Create topic with 1 HTS fixed with max supply")
        // TOPIC_FEE_012
        final Stream<DynamicTest> createTopicTokenFeeMaxLong() {
            return hapiTest(
                    cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                    cryptoCreate(COLLECTOR),
                    tokenCreate("finiteToken")
                            .tokenType(TokenType.FUNGIBLE_COMMON)
                            .initialSupply(500),
                    tokenAssociate(COLLECTOR, "finiteToken"),
                    atomicBatch(createTopic(TOPIC)
                                    .adminKeyName(ADMIN_KEY)
                                    .submitKeyName(SUBMIT_KEY)
                                    .feeScheduleKeyName(FEE_SCHEDULE_KEY)
                                    .withConsensusCustomFee(
                                            fixedConsensusHtsFee(Long.MAX_VALUE, "finiteToken", COLLECTOR))
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR),
                    getTopicInfo(TOPIC)
                            .hasAdminKey(ADMIN_KEY)
                            .hasSubmitKey(SUBMIT_KEY)
                            .hasFeeScheduleKey(FEE_SCHEDULE_KEY)
                            .hasCustomFee(expectedConsensusFixedHTSFee(Long.MAX_VALUE, "finiteToken", COLLECTOR)));
        }

        @HapiTest
        @DisplayName("Create topic smallest fee")
        // TOPIC_FEE_015
        final Stream<DynamicTest> createTopicSmallestFee() {
            return hapiTest(
                    cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                    cryptoCreate(COLLECTOR),
                    atomicBatch(createTopic(TOPIC)
                                    .adminKeyName(ADMIN_KEY)
                                    .submitKeyName(SUBMIT_KEY)
                                    .feeScheduleKeyName(FEE_SCHEDULE_KEY)
                                    .withConsensusCustomFee(fixedConsensusHbarFee(1, COLLECTOR))
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR),
                    getTopicInfo(TOPIC)
                            .hasAdminKey(ADMIN_KEY)
                            .hasSubmitKey(SUBMIT_KEY)
                            .hasFeeScheduleKey(FEE_SCHEDULE_KEY)
                            .hasCustomFee(expectedConsensusFixedHbarFee(1, COLLECTOR)));
        }

        @HapiTest
        @DisplayName("Create topic with HBAR fee above supply")
        // TOPIC_FEE_016
        final Stream<DynamicTest> createTopicHbarFeeAboveSupply() {
            var aboveSupply = 50 * ONE_BILLION_HBARS + 1;
            return hapiTest(
                    cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                    cryptoCreate(COLLECTOR),
                    atomicBatch(createTopic(TOPIC)
                                    .adminKeyName(ADMIN_KEY)
                                    .submitKeyName(SUBMIT_KEY)
                                    .feeScheduleKeyName(FEE_SCHEDULE_KEY)
                                    .withConsensusCustomFee(fixedConsensusHbarFee(aboveSupply, COLLECTOR))
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR),
                    getTopicInfo(TOPIC)
                            .hasAdminKey(ADMIN_KEY)
                            .hasSubmitKey(SUBMIT_KEY)
                            .hasFeeScheduleKey(FEE_SCHEDULE_KEY)
                            .hasCustomFee(expectedConsensusFixedHbarFee(aboveSupply, COLLECTOR)));
        }

        @HapiTest
        @DisplayName("topic create with hollow account for collector")
        final Stream<DynamicTest> topicWithHollowCollector() {
            // TOPIC_FEE_017
            final var validAlias = "validAlias";
            return hapiTest(flattened(
                    cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                    createHollowAccountOperations(validAlias),
                    atomicBatch(createTopic(TOPIC)
                                    .adminKeyName(ADMIN_KEY)
                                    .submitKeyName(SUBMIT_KEY)
                                    .feeScheduleKeyName(FEE_SCHEDULE_KEY)
                                    .withConsensusCustomFee(fixedConsensusHbarFee(1, validAlias))
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR),
                    getTopicInfo(TOPIC)
                            .hasAdminKey(ADMIN_KEY)
                            .hasSubmitKey(SUBMIT_KEY)
                            .hasFeeScheduleKey(FEE_SCHEDULE_KEY)
                            .hasCustomFee(expectedConsensusFixedHbarFee(1, validAlias)),
                    // check if account is hollow (has empty key)
                    getAliasedAccountInfo(validAlias).isHollow()));
        }

        @HapiTest
        @DisplayName("topic create with contract collector")
        final Stream<DynamicTest> topicWithContractCollector() {
            // TOPIC_FEE_019
            var mutableContract = "PayReceivable";
            return hapiTest(flattened(
                    cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                    deployMutableContract(mutableContract),
                    atomicBatch(createTopic(TOPIC)
                                    .adminKeyName(ADMIN_KEY)
                                    .submitKeyName(SUBMIT_KEY)
                                    .feeScheduleKeyName(FEE_SCHEDULE_KEY)
                                    .withConsensusCustomFee(fixedConsensusHbarFee(1, mutableContract))
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR),
                    getTopicInfo(TOPIC)
                            .hasAdminKey(ADMIN_KEY)
                            .hasSubmitKey(SUBMIT_KEY)
                            .hasFeeScheduleKey(FEE_SCHEDULE_KEY)
                            .hasCustomFee(expectedConsensusFixedHbarFee(1, mutableContract))));
        }

        @HapiTest
        @DisplayName("Create topic with keys")
        // TOPIC_FEE_020/21/23/24
        final Stream<DynamicTest> topicCreateWithFEKL() {
            final var collector = "collector";
            final var key = "key";
            return hapiTest(
                    cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                    // create topic with 1 FEKL and no custom fee
                    newKeyNamed(key),
                    createTopic(TOPIC).adminKeyName(ADMIN_KEY).feeExemptKeys(key),
                    getTopicInfo(TOPIC).hasAdminKey(ADMIN_KEY).hasFeeExemptKeys(List.of(key)),
                    // create topic with 1 FEKL and custom fee
                    cryptoCreate(collector),
                    atomicBatch(createTopic(TOPIC + "2")
                                    .withConsensusCustomFee(fixedConsensusHbarFee(1, collector))
                                    .feeScheduleKeyName(FEE_SCHEDULE_KEY)
                                    .adminKeyName(ADMIN_KEY)
                                    .feeExemptKeys(key)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR),
                    getTopicInfo(TOPIC + "2")
                            .hasCustomFee(expectedConsensusFixedHbarFee(1, collector))
                            .hasFeeScheduleKey(FEE_SCHEDULE_KEY)
                            .hasAdminKey(ADMIN_KEY)
                            .hasFeeExemptKeys(List.of(key)));
        }

        @HapiTest
        @DisplayName("Create topic with 10 keys in FEKL")
        // TOPIC_FEE_022
        final Stream<DynamicTest> createTopicWithFEKL() {
            final var collector = "collector";
            return hapiTest(flattened(
                    cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                    // create 10 keys
                    newNamedKeysForFEKL(10),
                    cryptoCreate(collector),
                    atomicBatch(createTopic(TOPIC)
                                    .adminKeyName(ADMIN_KEY)
                                    .submitKeyName(SUBMIT_KEY)
                                    .feeScheduleKeyName(FEE_SCHEDULE_KEY)
                                    .withConsensusCustomFee(fixedConsensusHbarFee(5, collector))
                                    // set list of 10 keys
                                    .feeExemptKeys(feeExemptKeyNames(10))
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR),
                    getTopicInfo(TOPIC)
                            .hasAdminKey(ADMIN_KEY)
                            .hasSubmitKey(SUBMIT_KEY)
                            .hasFeeScheduleKey(FEE_SCHEDULE_KEY)
                            // assert the list
                            .hasFeeExemptKeys(List.of(feeExemptKeyNames(10)))));
        }

        @HapiTest
        @DisplayName("Topic Create with different keys")
        // TOPIC_FEE_025
        final Stream<DynamicTest> topicCreateWithDifferentKeys() {
            final var ecdsaKey = "ecdsaKey";
            final var ed25519Key = "ed25519Key";
            final var threshKey = "threshKey";
            return hapiTest(
                    cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                    newKeyNamed(ecdsaKey).shape(SigControl.SECP256K1_ON),
                    newKeyNamed(ed25519Key).shape(SigControl.ED25519_ON),
                    newKeyNamed(threshKey).shape(threshOf(1, SIMPLE, SIMPLE)),
                    atomicBatch(createTopic(TOPIC)
                                    .feeExemptKeys(ecdsaKey, ed25519Key, threshKey)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR),
                    getTopicInfo(TOPIC).hasFeeExemptKeys(List.of(ecdsaKey, ed25519Key, threshKey)));
        }

        @HapiTest
        @DisplayName("Create topic with keys")
        // TOPIC_FEE_026
        final Stream<DynamicTest> topicCreateWithFeklDeletedAccount() {
            final var key = "key";
            return hapiTest(
                    cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                    newKeyNamed(key),
                    cryptoCreate("deleted").key(key),
                    cryptoDelete("deleted"),
                    atomicBatch(createTopic(TOPIC).feeExemptKeys(key).batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR),
                    getTopicInfo(TOPIC).hasFeeExemptKeys(List.of(key)));
        }

        @HapiTest
        @DisplayName("Delete custom fee collector")
        // TOPIC_FEE_028
        final Stream<DynamicTest> deleteCollector() {
            final var collector = "collector";
            return hapiTest(
                    cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                    cryptoCreate(collector),
                    atomicBatch(createTopic(TOPIC)
                                    .withConsensusCustomFee(fixedConsensusHbarFee(1, collector))
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR),
                    cryptoDelete(collector).hasKnownStatus(SUCCESS));
        }

        @HapiTest
        @DisplayName("Create with 10 fees with the same token and the same collector")
        // TOPIC_FEE_017
        final Stream<DynamicTest> createWith10FeesWithSameTokenAndCollector() {
            final var collector = "collector";
            return hapiTest(
                    cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                    cryptoCreate(collector),
                    tokenCreate("testToken")
                            .tokenType(TokenType.FUNGIBLE_COMMON)
                            .initialSupply(500),
                    tokenAssociate(collector, "testToken"),
                    atomicBatch(createTopic(TOPIC)
                                    .adminKeyName(ADMIN_KEY)
                                    .withConsensusCustomFee(fixedConsensusHtsFee(1, "testToken", "collector"))
                                    .withConsensusCustomFee(fixedConsensusHtsFee(2, "testToken", "collector"))
                                    .withConsensusCustomFee(fixedConsensusHtsFee(3, "testToken", "collector"))
                                    .withConsensusCustomFee(fixedConsensusHtsFee(4, "testToken", "collector"))
                                    .withConsensusCustomFee(fixedConsensusHtsFee(5, "testToken", "collector"))
                                    .withConsensusCustomFee(fixedConsensusHtsFee(6, "testToken", "collector"))
                                    .withConsensusCustomFee(fixedConsensusHtsFee(7, "testToken", "collector"))
                                    .withConsensusCustomFee(fixedConsensusHtsFee(8, "testToken", "collector"))
                                    .withConsensusCustomFee(fixedConsensusHtsFee(9, "testToken", "collector"))
                                    .withConsensusCustomFee(fixedConsensusHtsFee(10, "testToken", "collector"))
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR),
                    getTopicInfo(TOPIC)
                            .hasAdminKey(ADMIN_KEY)
                            .hasCustomFee(expectedConsensusFixedHTSFee(1, "testToken", "collector"))
                            .hasCustomFee(expectedConsensusFixedHTSFee(2, "testToken", "collector"))
                            .hasCustomFee(expectedConsensusFixedHTSFee(3, "testToken", "collector"))
                            .hasCustomFee(expectedConsensusFixedHTSFee(4, "testToken", "collector"))
                            .hasCustomFee(expectedConsensusFixedHTSFee(5, "testToken", "collector"))
                            .hasCustomFee(expectedConsensusFixedHTSFee(6, "testToken", "collector"))
                            .hasCustomFee(expectedConsensusFixedHTSFee(7, "testToken", "collector"))
                            .hasCustomFee(expectedConsensusFixedHTSFee(8, "testToken", "collector"))
                            .hasCustomFee(expectedConsensusFixedHTSFee(9, "testToken", "collector"))
                            .hasCustomFee(expectedConsensusFixedHTSFee(10, "testToken", "collector")));
        }
    }

    @Nested
    @DisplayName("Negative scenarios")
    class TopicCreateNegativeScenarios {

        @BeforeAll
        static void beforeAll(@NonNull final TestLifecycle lifecycle) {
            lifecycle.doAdhoc(setupBaseKeys());
        }

        @HapiTest
        @DisplayName("Create topic with duplicated signatures in FEKL")
        // TOPIC_FEE_029
        final Stream<DynamicTest> createTopicWithDuplicateSignatures() {
            final var testKey = "testKey";
            return hapiTest(flattened(
                    cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                    newKeyNamed(testKey),
                    atomicBatch(createTopic(TOPIC)
                                    .adminKeyName(ADMIN_KEY)
                                    .submitKeyName(SUBMIT_KEY)
                                    .feeScheduleKeyName(FEE_SCHEDULE_KEY)
                                    .feeExemptKeys(testKey, testKey)
                                    .hasPrecheck(FEE_EXEMPT_KEY_LIST_CONTAINS_DUPLICATED_KEYS)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR)
                            .hasPrecheck(FEE_EXEMPT_KEY_LIST_CONTAINS_DUPLICATED_KEYS)));
        }

        @HapiTest
        @DisplayName("Create topic with 0 Hbar fixed fee")
        // TOPIC_FEE_030
        final Stream<DynamicTest> createTopicWithZeroHbarFixedFee() {
            final var collector = "collector";
            return hapiTest(
                    cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                    cryptoCreate(collector),
                    atomicBatch(createTopic(TOPIC)
                                    .adminKeyName(ADMIN_KEY)
                                    .submitKeyName(SUBMIT_KEY)
                                    .feeScheduleKeyName(FEE_SCHEDULE_KEY)
                                    .withConsensusCustomFee(fixedConsensusHbarFee(0, collector))
                                    .hasKnownStatus(CUSTOM_FEE_MUST_BE_POSITIVE)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR)
                            .hasKnownStatus(INNER_TRANSACTION_FAILED));
        }

        @HapiTest
        @DisplayName("Create topic with 0 HTS fixed fee")
        // TOPIC_FEE_031
        final Stream<DynamicTest> createTopicWithZeroHTSFixedFee() {
            final var collector = "collector";
            return hapiTest(
                    cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                    cryptoCreate(collector),
                    tokenCreate("testToken")
                            .tokenType(TokenType.FUNGIBLE_COMMON)
                            .initialSupply(500),
                    tokenAssociate(collector, "testToken"),
                    atomicBatch(createTopic(TOPIC)
                                    .adminKeyName(ADMIN_KEY)
                                    .submitKeyName(SUBMIT_KEY)
                                    .feeScheduleKeyName(FEE_SCHEDULE_KEY)
                                    .withConsensusCustomFee(fixedConsensusHtsFee(0, "testToken", collector))
                                    .hasKnownStatus(CUSTOM_FEE_MUST_BE_POSITIVE)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR)
                            .hasKnownStatus(INNER_TRANSACTION_FAILED));
        }

        @HapiTest
        @DisplayName("Create topic with invalid fee schedule key")
        // TOPIC_FEE_032
        final Stream<DynamicTest> createTopicWithInvalidFeeScheduleKey() {
            final var invalidKey = "invalidKey";
            return hapiTest(
                    cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                    withOpContext((spec, opLog) -> spec.registry().saveKey(invalidKey, STRUCTURALLY_INVALID_KEY)),
                    atomicBatch(createTopic(TOPIC)
                                    .adminKeyName(ADMIN_KEY)
                                    .submitKeyName(SUBMIT_KEY)
                                    .feeScheduleKeyName(invalidKey)
                                    .hasKnownStatus(INVALID_CUSTOM_FEE_SCHEDULE_KEY)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR)
                            .hasKnownStatus(INNER_TRANSACTION_FAILED));
        }

        @HapiTest
        @DisplayName("Create topic with custom fee and deleted collector")
        // TOPIC_FEE_033
        final Stream<DynamicTest> createTopicWithCustomFeeAndDeletedCollector() {
            final var collector = "collector";
            return hapiTest(
                    cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                    cryptoCreate(collector),
                    cryptoDelete(collector),
                    atomicBatch(createTopic(TOPIC)
                                    .adminKeyName(ADMIN_KEY)
                                    .submitKeyName(SUBMIT_KEY)
                                    .feeScheduleKeyName(FEE_SCHEDULE_KEY)
                                    .withConsensusCustomFee(fixedConsensusHbarFee(ONE_HBAR, collector))
                                    .hasKnownStatus(ACCOUNT_DELETED)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR)
                            .hasKnownStatus(INNER_TRANSACTION_FAILED));
        }

        @HapiTest
        @DisplayName("Create topic with custom fee above max FT supply")
        // TOPIC_FEE_034
        final Stream<DynamicTest> createTopicCustomFeeAboveSupply() {
            final var collector = "collector";
            return hapiTest(
                    cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                    cryptoCreate(collector),
                    tokenCreate("fungibleToken")
                            .tokenType(TokenType.FUNGIBLE_COMMON)
                            .supplyType(TokenSupplyType.FINITE)
                            .initialSupply(500)
                            .maxSupply(1000),
                    atomicBatch(createTopic(TOPIC)
                                    .withConsensusCustomFee(fixedConsensusHtsFee(1001, "fungibleToken", "collector"))
                                    .hasKnownStatus(AMOUNT_EXCEEDS_TOKEN_MAX_SUPPLY)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR)
                            .hasKnownStatus(INNER_TRANSACTION_FAILED));
        }

        @HapiTest
        @DisplayName("Create topic with negative custom fee")
        // TOPIC_FEE_037
        final Stream<DynamicTest> createTopicNegativeFee() {
            return hapiTest(
                    cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                    cryptoCreate("collector"),
                    atomicBatch(createTopic(TOPIC)
                                    .withConsensusCustomFee(fixedConsensusHbarFee(-1, "collector"))
                                    .hasKnownStatus(CUSTOM_FEE_MUST_BE_POSITIVE)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR)
                            .hasKnownStatus(INNER_TRANSACTION_FAILED));
        }

        @HapiTest
        @DisplayName("Create topic with not specified custom fee")
        // TOPIC_FEE_038
        final Stream<DynamicTest> createTopicNotSpecifiedFee() {
            final var collector = "collector";
            return hapiTest(
                    cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                    cryptoCreate(collector),
                    atomicBatch(createTopic(TOPIC)
                                    .withConsensusCustomFee(spec -> FixedCustomFee.newBuilder()
                                            .setFeeCollectorAccountId(
                                                    spec.registry().getAccountID(collector))
                                            .build())
                                    .hasKnownStatus(CUSTOM_FEE_NOT_FULLY_SPECIFIED)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR)
                            .hasKnownStatus(INNER_TRANSACTION_FAILED));
        }

        @HapiTest
        @DisplayName("Create topic with invalid FT for custom fee")
        // TOPIC_FEE_039/40/41
        final Stream<DynamicTest> createTopicInvalidToken() {
            return hapiTest(
                    cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                    cryptoCreate("collector"),
                    newKeyNamed("pauseKey"),
                    tokenCreate("pausedToken").pauseKey("pauseKey"),
                    tokenPause("pausedToken"),
                    tokenCreate("deletedToken").adminKey("adminKey"),
                    atomicBatch(createTopic(TOPIC)
                                    .withConsensusCustomFee(fixedConsensusHtsFee(1, "pausedToken", "collector"))
                                    .hasKnownStatus(INVALID_TOKEN_ID_IN_CUSTOM_FEES)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR)
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),
                    tokenDelete("deletedToken").hasKnownStatus(SUCCESS),
                    atomicBatch(createTopic(TOPIC)
                                    .withConsensusCustomFee(fixedConsensusHtsFee(1, "deletedToken", "collector"))
                                    .hasKnownStatus(INVALID_TOKEN_ID_IN_CUSTOM_FEES)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR)
                            .hasKnownStatus(INNER_TRANSACTION_FAILED));
        }

        @HapiTest
        @DisplayName("Create topic with above FEKL limit")
        // TOPIC_FEE_042
        final Stream<DynamicTest> createTopicAboveKeyLimit() {
            return hapiTest(flattened(
                    cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                    newNamedKeysForFEKL(11),
                    atomicBatch(createTopic(TOPIC)
                                    .feeExemptKeys(feeExemptKeyNames(11))
                                    .hasKnownStatus(MAX_ENTRIES_FOR_FEE_EXEMPT_KEY_LIST_EXCEEDED)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR)
                            .hasKnownStatus(INNER_TRANSACTION_FAILED)));
        }

        @HapiTest
        @DisplayName("Create topic with invalid EcdsaKey fee exempt key")
        // TOPIC_FEE_043
        final Stream<DynamicTest> createTopicInvalidFeeExemptKeyEcdsaKey() {
            var invalidEcdsaKey = Key.newBuilder()
                    .setECDSASecp256K1(ByteString.fromHex("0000000000000000000000000000000000000000"))
                    .build();
            return hapiTest(
                    cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                    atomicBatch(createTopic(TOPIC)
                                    .feeExemptKeys(invalidEcdsaKey)
                                    .hasKnownStatus(INVALID_KEY_IN_FEE_EXEMPT_KEY_LIST)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR)
                            .hasKnownStatus(INNER_TRANSACTION_FAILED));
        }

        @HapiTest
        @DisplayName("Create topic with invalid Ed25519 fee exempt key")
        // TOPIC_FEE_043
        final Stream<DynamicTest> createTopicInvalidFeeExemptKeyEd25519() {
            var invalidEd25519Key = Key.newBuilder()
                    .setEd25519(ByteString.fromHex("0000000000000000000000000000000000000000"))
                    .build();
            return hapiTest(
                    cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                    atomicBatch(createTopic(TOPIC)
                                    .feeExemptKeys(invalidEd25519Key)
                                    .hasKnownStatus(INVALID_KEY_IN_FEE_EXEMPT_KEY_LIST)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR)
                            .hasKnownStatus(INNER_TRANSACTION_FAILED));
        }

        @HapiTest
        @DisplayName("Create topic with frozen token for collector")
        // TOPIC_FEE_044
        final Stream<DynamicTest> createTopicFrozenTokenForCollector() {
            return hapiTest(
                    cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                    newKeyNamed("freezeKey"),
                    cryptoCreate("collector"),
                    tokenCreate("frozenToken").freezeKey("freezeKey"),
                    tokenAssociate("collector", "frozenToken"),
                    tokenFreeze("frozenToken", "collector"),
                    atomicBatch(createTopic(TOPIC)
                                    .withConsensusCustomFee(fixedConsensusHtsFee(1, "frozenToken", "collector"))
                                    .hasKnownStatus(ACCOUNT_FROZEN_FOR_TOKEN)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR)
                            .hasKnownStatus(INNER_TRANSACTION_FAILED));
        }

        @HapiTest
        @DisplayName("Create with 11 fees with the same token and the same collector")
        // TOPIC_FEE_034
        final Stream<DynamicTest> createWith11FeesWithSameTokenAndCollector() {
            final var collector = "collector";
            return hapiTest(
                    cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                    cryptoCreate(collector),
                    tokenCreate("testToken")
                            .tokenType(TokenType.FUNGIBLE_COMMON)
                            .initialSupply(500),
                    tokenAssociate(collector, "testToken"),
                    atomicBatch(createTopic(TOPIC)
                                    .adminKeyName(ADMIN_KEY)
                                    .withConsensusCustomFee(fixedConsensusHtsFee(1, "testToken", "collector"))
                                    .withConsensusCustomFee(fixedConsensusHtsFee(2, "testToken", "collector"))
                                    .withConsensusCustomFee(fixedConsensusHtsFee(3, "testToken", "collector"))
                                    .withConsensusCustomFee(fixedConsensusHtsFee(4, "testToken", "collector"))
                                    .withConsensusCustomFee(fixedConsensusHtsFee(5, "testToken", "collector"))
                                    .withConsensusCustomFee(fixedConsensusHtsFee(6, "testToken", "collector"))
                                    .withConsensusCustomFee(fixedConsensusHtsFee(7, "testToken", "collector"))
                                    .withConsensusCustomFee(fixedConsensusHtsFee(8, "testToken", "collector"))
                                    .withConsensusCustomFee(fixedConsensusHtsFee(9, "testToken", "collector"))
                                    .withConsensusCustomFee(fixedConsensusHtsFee(10, "testToken", "collector"))
                                    .withConsensusCustomFee(fixedConsensusHtsFee(11, "testToken", "collector"))
                                    .hasKnownStatus(CUSTOM_FEES_LIST_TOO_LONG)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR)
                            .hasKnownStatus(INNER_TRANSACTION_FAILED));
        }

        @HapiTest
        @DisplayName("Create with 11 HBAR fees and the same collector")
        // TOPIC_FEE_035
        final Stream<DynamicTest> createWith11HBARFeesAndTheSameCollector() {
            final var collector = "collector";
            return hapiTest(
                    cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                    cryptoCreate(collector),
                    tokenCreate("testToken")
                            .tokenType(TokenType.FUNGIBLE_COMMON)
                            .initialSupply(500),
                    tokenAssociate(collector, "testToken"),
                    atomicBatch(createTopic(TOPIC)
                                    .adminKeyName(ADMIN_KEY)
                                    .withConsensusCustomFee(fixedConsensusHbarFee(1, "collector"))
                                    .withConsensusCustomFee(fixedConsensusHbarFee(2, "collector"))
                                    .withConsensusCustomFee(fixedConsensusHbarFee(3, "collector"))
                                    .withConsensusCustomFee(fixedConsensusHbarFee(4, "collector"))
                                    .withConsensusCustomFee(fixedConsensusHbarFee(5, "collector"))
                                    .withConsensusCustomFee(fixedConsensusHbarFee(6, "collector"))
                                    .withConsensusCustomFee(fixedConsensusHbarFee(7, "collector"))
                                    .withConsensusCustomFee(fixedConsensusHbarFee(8, "collector"))
                                    .withConsensusCustomFee(fixedConsensusHbarFee(9, "collector"))
                                    .withConsensusCustomFee(fixedConsensusHbarFee(10, "collector"))
                                    .withConsensusCustomFee(fixedConsensusHbarFee(11, "collector"))
                                    .hasKnownStatus(CUSTOM_FEES_LIST_TOO_LONG)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR)
                            .hasKnownStatus(INNER_TRANSACTION_FAILED));
        }

        @HapiTest
        @DisplayName("Create with 11 fees with the same token and the different collectors")
        // TOPIC_FEE_036
        final Stream<DynamicTest> createWith11FeesWithSameTokenAndDifferentCollectors() {
            final var collector1 = "collector1";
            final var collector2 = "collector2";
            final var collector3 = "collector3";
            final var collector4 = "collector4";
            final var collector5 = "collector5";
            final var collector6 = "collector6";
            final var collector7 = "collector7";
            final var collector8 = "collector8";
            final var collector9 = "collector9";
            final var collector10 = "collector10";
            final var collector11 = "collector11";
            return hapiTest(
                    cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                    cryptoCreate(collector1),
                    cryptoCreate(collector2),
                    cryptoCreate(collector3),
                    cryptoCreate(collector4),
                    cryptoCreate(collector5),
                    cryptoCreate(collector6),
                    cryptoCreate(collector7),
                    cryptoCreate(collector8),
                    cryptoCreate(collector9),
                    cryptoCreate(collector10),
                    cryptoCreate(collector11),
                    tokenCreate("testToken")
                            .tokenType(TokenType.FUNGIBLE_COMMON)
                            .initialSupply(500),
                    tokenAssociate(collector1, "testToken"),
                    tokenAssociate(collector2, "testToken"),
                    tokenAssociate(collector3, "testToken"),
                    tokenAssociate(collector4, "testToken"),
                    tokenAssociate(collector5, "testToken"),
                    tokenAssociate(collector6, "testToken"),
                    tokenAssociate(collector7, "testToken"),
                    tokenAssociate(collector8, "testToken"),
                    tokenAssociate(collector9, "testToken"),
                    tokenAssociate(collector10, "testToken"),
                    tokenAssociate(collector11, "testToken"),
                    atomicBatch(createTopic(TOPIC)
                                    .adminKeyName(ADMIN_KEY)
                                    .withConsensusCustomFee(fixedConsensusHtsFee(1, "testToken", "collector1"))
                                    .withConsensusCustomFee(fixedConsensusHtsFee(2, "testToken", "collector2"))
                                    .withConsensusCustomFee(fixedConsensusHtsFee(3, "testToken", "collector3"))
                                    .withConsensusCustomFee(fixedConsensusHtsFee(4, "testToken", "collector4"))
                                    .withConsensusCustomFee(fixedConsensusHtsFee(5, "testToken", "collector5"))
                                    .withConsensusCustomFee(fixedConsensusHtsFee(6, "testToken", "collector6"))
                                    .withConsensusCustomFee(fixedConsensusHtsFee(7, "testToken", "collector7"))
                                    .withConsensusCustomFee(fixedConsensusHtsFee(8, "testToken", "collector8"))
                                    .withConsensusCustomFee(fixedConsensusHtsFee(9, "testToken", "collector9"))
                                    .withConsensusCustomFee(fixedConsensusHtsFee(10, "testToken", "collector10"))
                                    .withConsensusCustomFee(fixedConsensusHtsFee(11, "testToken", "collector11"))
                                    .hasKnownStatus(CUSTOM_FEES_LIST_TOO_LONG)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR)
                            .hasKnownStatus(INNER_TRANSACTION_FAILED));
        }
    }

    private SpecOperation[] createHollowAccountOperations(String alias) {
        final var t = new ArrayList<SpecOperation>(List.of(newKeyNamed(alias).shape(SECP_256K1_SHAPE)));
        t.addAll(Arrays.stream(createHollowAccountFrom(alias)).toList());
        t.add(withOpContext((spec, opLog) -> updateSpecFor(spec, alias)));
        return t.toArray(new SpecOperation[0]);
    }

    protected SpecOperation[] deployMutableContract(String name) {
        var t = List.of(
                newKeyNamed(name),
                uploadInitCode(name),
                contractCreate(name)
                        .maxAutomaticTokenAssociations(0)
                        .adminKey(name)
                        .gas(500_000L));

        return t.toArray(new SpecOperation[0]);
    }
}
