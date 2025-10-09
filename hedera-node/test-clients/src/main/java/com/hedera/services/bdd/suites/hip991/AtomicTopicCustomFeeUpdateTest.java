// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip991;

import static com.hedera.node.app.hapi.utils.EthSigsUtils.recoverAddressFromPubKey;
import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
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
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.updateTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedConsensusHbarFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedConsensusHbarFeeNoCollector;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedConsensusHtsFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeTests.expectedConsensusFixedHTSFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeTests.expectedConsensusFixedHbarFee;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.createHollow;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_FROZEN_FOR_TOKEN;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.AMOUNT_EXCEEDS_TOKEN_MAX_SUPPLY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CUSTOM_FEES_LIST_TOO_LONG;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CUSTOM_FEE_DENOMINATION_MUST_BE_FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CUSTOM_FEE_MUST_BE_POSITIVE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.FEE_EXEMPT_KEY_LIST_CONTAINS_DUPLICATED_KEYS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.FEE_SCHEDULE_KEY_CANNOT_BE_UPDATED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.FEE_SCHEDULE_KEY_NOT_SET;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_CUSTOM_FEE_COLLECTOR;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_ID_IN_CUSTOM_FEES;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_NOT_ASSOCIATED_TO_FEE_COLLECTOR;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.UNAUTHORIZED;

import com.google.protobuf.ByteString;
import com.hedera.node.app.hapi.utils.ByteStringUtils;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.HapiPropertySource;
import com.hederahashgraph.api.proto.java.TokenSupplyType;
import com.hederahashgraph.api.proto.java.TokenType;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.stream.Stream;
import org.hiero.base.utility.CommonUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;

// This test cases are direct copies of TopicCustomFeeUpdateTest. The difference here is that
// we are wrapping the operations in an atomic batch to confirm the fees are the same
@HapiTestLifecycle
@DisplayName("Topic custom fees update")
class AtomicTopicCustomFeeUpdateTest extends TopicCustomFeeBase {

    private static final long FIFTY_BILLION = 50_000_000_000L;
    private static final String BATCH_OPERATOR = "batchOperator";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle lifecycle) {
        lifecycle.doAdhoc(setupBaseForUpdate());
    }

    @Nested
    @DisplayName("Positive scenarios")
    class TopicCreatePositiveScenarios {

        @HapiTest
        @DisplayName("the custom fee to 50 billion(max HBAR) + 1")
        final Stream<DynamicTest> updateTheCustomFeeTo50BillionPlusOne() {
            return hapiTest(
                    cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                    // Create a topic and verify custom fee is correct
                    createTopic(TOPIC)
                            .adminKeyName(ADMIN_KEY)
                            .feeScheduleKeyName(FEE_SCHEDULE_KEY)
                            .withConsensusCustomFee(fixedConsensusHtsFee(1, TOKEN, COLLECTOR)),
                    getTopicInfo(TOPIC).hasCustomFee(expectedConsensusFixedHTSFee(1, TOKEN, COLLECTOR)),

                    // Update the custom fee to 50 billion(max HBAR) + 1
                    atomicBatch(updateTopic(TOPIC)
                                    .withConsensusCustomFee(fixedConsensusHbarFee(FIFTY_BILLION + 1, COLLECTOR))
                                    .signedByPayerAnd(ADMIN_KEY, FEE_SCHEDULE_KEY)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR),
                    getTopicInfo(TOPIC).hasCustomFee(expectedConsensusFixedHbarFee(FIFTY_BILLION + 1, COLLECTOR)));
        }

        @HapiTest
        @DisplayName("the fee schedule key")
        final Stream<DynamicTest> updateFeeScheduleKey() {
            return hapiTest(
                    cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                    // Create a topic and verify that the keys are correct
                    createTopic(TOPIC).adminKeyName(ADMIN_KEY).feeScheduleKeyName(FEE_SCHEDULE_KEY),
                    getTopicInfo(TOPIC).hasAdminKey(ADMIN_KEY).hasFeeScheduleKey(FEE_SCHEDULE_KEY),

                    // Update the fee schedule and verify that it's updated
                    atomicBatch(updateTopic(TOPIC)
                                    .feeScheduleKeyName(FEE_SCHEDULE_KEY2)
                                    .signedByPayerAnd(ADMIN_KEY)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR),
                    getTopicInfo(TOPIC).hasFeeScheduleKey(FEE_SCHEDULE_KEY2));
        }

        @HapiTest
        @DisplayName("the custom fee from FT to HBAR")
        final Stream<DynamicTest> updateCustomFeeFromFTToHBAR() {
            return hapiTest(
                    cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                    // Create a topic and verify custom fee is correct
                    createTopic(TOPIC)
                            .adminKeyName(ADMIN_KEY)
                            .feeScheduleKeyName(FEE_SCHEDULE_KEY)
                            .withConsensusCustomFee(fixedConsensusHtsFee(1, TOKEN, COLLECTOR)),
                    getTopicInfo(TOPIC).hasCustomFee(expectedConsensusFixedHTSFee(1, TOKEN, COLLECTOR)),

                    // Update the custom fee and verify that it's updated
                    atomicBatch(updateTopic(TOPIC)
                                    .withConsensusCustomFee(fixedConsensusHbarFee(1, COLLECTOR))
                                    .signedByPayerAnd(ADMIN_KEY, FEE_SCHEDULE_KEY)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR),
                    getTopicInfo(TOPIC).hasCustomFee(expectedConsensusFixedHbarFee(1, COLLECTOR)));
        }

        @HapiTest
        @DisplayName("the custom fee from HBAR to FT")
        final Stream<DynamicTest> updateCustomFeeFromHBARToFT() {
            return hapiTest(
                    cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                    // Create a topic and verify custom fee is correct
                    createTopic(TOPIC)
                            .adminKeyName(ADMIN_KEY)
                            .feeScheduleKeyName(FEE_SCHEDULE_KEY)
                            .withConsensusCustomFee(fixedConsensusHbarFee(1, COLLECTOR)),
                    getTopicInfo(TOPIC).hasCustomFee(expectedConsensusFixedHbarFee(1, COLLECTOR)),

                    // Update the custom fee and verify that it's updated
                    atomicBatch(updateTopic(TOPIC)
                                    .withConsensusCustomFee(fixedConsensusHtsFee(1, TOKEN, COLLECTOR))
                                    .signedByPayerAnd(ADMIN_KEY, FEE_SCHEDULE_KEY)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR),
                    getTopicInfo(TOPIC).hasCustomFee(expectedConsensusFixedHTSFee(1, TOKEN, COLLECTOR)));
        }

        @HapiTest
        @DisplayName("to remove the custom fees")
        final Stream<DynamicTest> updateToRemoveCustomFees() {
            return hapiTest(
                    cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                    // Create a topic and verify custom fee is correct
                    createTopic(TOPIC)
                            .adminKeyName(ADMIN_KEY)
                            .feeScheduleKeyName(FEE_SCHEDULE_KEY)
                            .withConsensusCustomFee(fixedConsensusHbarFee(1, COLLECTOR)),
                    getTopicInfo(TOPIC).hasCustomFee(expectedConsensusFixedHbarFee(1, COLLECTOR)),

                    // Update the topic removing the custom fee
                    atomicBatch(updateTopic(TOPIC)
                                    .withEmptyCustomFee()
                                    .signedByPayerAnd(ADMIN_KEY, FEE_SCHEDULE_KEY)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR),
                    getTopicInfo(TOPIC).hasNoCustomFee());
        }

        @HapiTest
        @DisplayName("to remove one of the two custom fees")
        final Stream<DynamicTest> updateCustomFeesToRemoveOneOfTwo() {
            return hapiTest(
                    cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                    // Create a topic with two custom fees
                    createTopic(TOPIC)
                            .adminKeyName(ADMIN_KEY)
                            .feeScheduleKeyName(FEE_SCHEDULE_KEY)
                            .withConsensusCustomFee(fixedConsensusHbarFee(1, COLLECTOR))
                            .withConsensusCustomFee(fixedConsensusHtsFee(1, TOKEN, COLLECTOR)),
                    getTopicInfo(TOPIC)
                            .hasCustomFee(expectedConsensusFixedHbarFee(1, COLLECTOR))
                            .hasCustomFee(expectedConsensusFixedHTSFee(1, TOKEN, COLLECTOR)),

                    // Update to remove one of the fees
                    atomicBatch(updateTopic(TOPIC)
                                    .withConsensusCustomFee(fixedConsensusHbarFee(1, COLLECTOR))
                                    .signedByPayerAnd(ADMIN_KEY, FEE_SCHEDULE_KEY)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR),
                    getTopicInfo(TOPIC).hasCustomFeeSize(1).hasCustomFee(expectedConsensusFixedHbarFee(1, COLLECTOR)));
        }

        @HapiTest
        @DisplayName("to reach the limit of 10 custom fees")
        @Tag(MATS)
        final Stream<DynamicTest> updateCustomFeesToReachTheLimit() {
            return hapiTest(
                    cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                    // Create a topic with one custom fees
                    createTopic(TOPIC)
                            .adminKeyName(ADMIN_KEY)
                            .feeScheduleKeyName(FEE_SCHEDULE_KEY)
                            .withConsensusCustomFee(fixedConsensusHbarFee(1, COLLECTOR)),
                    getTopicInfo(TOPIC).hasCustomFee(expectedConsensusFixedHbarFee(1, COLLECTOR)),

                    // Update the topic to add 9 more custom fees(reaching the limit of 10 custom fees)
                    cryptoCreate("collector2"),
                    cryptoCreate("collector3"),
                    cryptoCreate("collector4"),
                    cryptoCreate("collector5"),
                    cryptoCreate("collector6"),
                    cryptoCreate("collector7"),
                    cryptoCreate("collector8"),
                    cryptoCreate("collector9"),
                    cryptoCreate("collector10"),
                    atomicBatch(updateTopic(TOPIC)
                                    .withConsensusCustomFee(fixedConsensusHbarFee(1, COLLECTOR))
                                    .withConsensusCustomFee(fixedConsensusHbarFee(1, "collector2"))
                                    .withConsensusCustomFee(fixedConsensusHbarFee(1, "collector3"))
                                    .withConsensusCustomFee(fixedConsensusHbarFee(1, "collector4"))
                                    .withConsensusCustomFee(fixedConsensusHbarFee(1, "collector5"))
                                    .withConsensusCustomFee(fixedConsensusHbarFee(1, "collector6"))
                                    .withConsensusCustomFee(fixedConsensusHbarFee(1, "collector7"))
                                    .withConsensusCustomFee(fixedConsensusHbarFee(1, "collector8"))
                                    .withConsensusCustomFee(fixedConsensusHbarFee(1, "collector9"))
                                    .withConsensusCustomFee(fixedConsensusHbarFee(1, "collector10"))
                                    .signedByPayerAnd(ADMIN_KEY, FEE_SCHEDULE_KEY)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR),

                    // Verify that the topic consist of all the custom fees
                    getTopicInfo(TOPIC)
                            .hasCustomFee(expectedConsensusFixedHbarFee(1, COLLECTOR))
                            .hasCustomFee(expectedConsensusFixedHbarFee(1, "collector2"))
                            .hasCustomFee(expectedConsensusFixedHbarFee(1, "collector3"))
                            .hasCustomFee(expectedConsensusFixedHbarFee(1, "collector4"))
                            .hasCustomFee(expectedConsensusFixedHbarFee(1, "collector5"))
                            .hasCustomFee(expectedConsensusFixedHbarFee(1, "collector6"))
                            .hasCustomFee(expectedConsensusFixedHbarFee(1, "collector7"))
                            .hasCustomFee(expectedConsensusFixedHbarFee(1, "collector8"))
                            .hasCustomFee(expectedConsensusFixedHbarFee(1, "collector9"))
                            .hasCustomFee(expectedConsensusFixedHbarFee(1, "collector10")));
        }

        @HapiTest
        @DisplayName("to add a custom fee with a hollow account as a collector")
        final Stream<DynamicTest> updateToAddCustomFeeWithHollowAsCollector() {
            final var hollowAccount = "hollowAccount";
            return hapiTest(
                    cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                    // Create a topic without custom fees
                    createTopic(TOPIC).adminKeyName(ADMIN_KEY).feeScheduleKeyName(FEE_SCHEDULE_KEY),
                    createHollow(1, i -> hollowAccount),

                    // Update the topic to have the hollow account as a collector
                    atomicBatch(updateTopic(TOPIC)
                                    .withConsensusCustomFee(fixedConsensusHbarFee(1, hollowAccount))
                                    .signedByPayerAnd(ADMIN_KEY, FEE_SCHEDULE_KEY)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR),
                    getTopicInfo(TOPIC).hasCustomFee(expectedConsensusFixedHbarFee(1, hollowAccount)));
        }

        @HapiTest
        @DisplayName("to add a custom fee with a contract as a collector")
        final Stream<DynamicTest> updateToAddCustomFeeWithContractAsCollector() {
            final var contract = "CallingContract";

            return hapiTest(
                    cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                    // Create a topic without custom fees
                    createTopic(TOPIC).adminKeyName(ADMIN_KEY).feeScheduleKeyName(FEE_SCHEDULE_KEY),
                    uploadInitCode(contract),
                    contractCreate(contract),

                    // Update the topic to have contract as a collector
                    atomicBatch(updateTopic(TOPIC)
                                    .withConsensusCustomFee(fixedConsensusHbarFee(1, contract))
                                    .signedByPayerAnd(ADMIN_KEY, FEE_SCHEDULE_KEY)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR),
                    getTopicInfo(TOPIC).hasCustomFee(expectedConsensusFixedHbarFee(1, contract)));
        }

        @HapiTest
        @DisplayName("to empty the custom fees with deleted collector")
        final Stream<DynamicTest> updateToEmptyCustomFeesWithDeletedCollector() {
            final var collector = "collector";
            return hapiTest(
                    cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                    cryptoCreate(collector),

                    // Create a topic with custom fees
                    createTopic(TOPIC)
                            .adminKeyName(ADMIN_KEY)
                            .feeScheduleKeyName(FEE_SCHEDULE_KEY)
                            .withConsensusCustomFee(fixedConsensusHbarFee(1, collector)),

                    // Delete the collector account
                    cryptoDelete(collector),

                    // Delete the custom fee
                    atomicBatch(updateTopic(TOPIC)
                                    .withEmptyCustomFee()
                                    .signedByPayerAnd(ADMIN_KEY, FEE_SCHEDULE_KEY)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR),
                    getTopicInfo(TOPIC).hasNoCustomFee());
        }

        @HapiTest
        @DisplayName("the fee schedule key to empty")
        final Stream<DynamicTest> updateTheFeeScheduleKeyToEmpty() {
            return hapiTest(
                    cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                    // Create a topic and verify that the keys are correct
                    createTopic(TOPIC).adminKeyName(ADMIN_KEY).feeScheduleKeyName(FEE_SCHEDULE_KEY),
                    getTopicInfo(TOPIC).hasAdminKey(ADMIN_KEY).hasFeeScheduleKey(FEE_SCHEDULE_KEY),

                    // Update the fee schedule to remove the fee schedule key
                    atomicBatch(updateTopic(TOPIC)
                                    .withEmptyFeeScheduleKey()
                                    .signedByPayerAnd(ADMIN_KEY, FEE_SCHEDULE_KEY)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR),
                    getTopicInfo(TOPIC).hasNoFeeScheduleKey());
        }

        @HapiTest
        @DisplayName("the FeeExemptKeyList")
        final Stream<DynamicTest> updateFeeExemptKeyList() {
            return hapiTest(flattened(
                    cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                    // Create a topic with no feeExemptKeyList
                    createTopic(TOPIC).adminKeyName(ADMIN_KEY).feeScheduleKeyName(FEE_SCHEDULE_KEY),

                    // Create 10 keys
                    newNamedKeysForFEKL(10),

                    // Update the topic to add feeExemptKeyList keys
                    atomicBatch(updateTopic(TOPIC)
                                    .withConsensusCustomFee(fixedConsensusHbarFee(1, COLLECTOR))
                                    .feeExemptKeys(feeExemptKeyNames(10))
                                    .signedByPayerAnd(ADMIN_KEY, FEE_SCHEDULE_KEY)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR),
                    getTopicInfo(TOPIC)
                            .hasCustomFee(expectedConsensusFixedHbarFee(1, COLLECTOR))
                            .hasFeeExemptKeys(List.of(feeExemptKeyNames(10)))));
        }

        @HapiTest
        @DisplayName("add one more fee exempt key")
        final Stream<DynamicTest> updateToAddOneMoreFeeExemptKey() {
            final var key = "key";
            final var key2 = "key2";
            return hapiTest(
                    cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                    newKeyNamed(key),

                    // Create a topic with a single key in feeExemptKeyList
                    createTopic(TOPIC)
                            .withConsensusCustomFee(fixedConsensusHbarFee(1, COLLECTOR))
                            .feeExemptKeys(key)
                            .adminKeyName(ADMIN_KEY),
                    getTopicInfo(TOPIC)
                            .hasCustomFee(expectedConsensusFixedHbarFee(1, COLLECTOR))
                            .hasFeeExemptKeys(List.of(key)),

                    // Update the topic to add one more key
                    newKeyNamed(key2),
                    atomicBatch(updateTopic(TOPIC)
                                    .feeExemptKeys(key, key2)
                                    .signedByPayerAnd(ADMIN_KEY)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR),
                    getTopicInfo(TOPIC)
                            .hasCustomFee(expectedConsensusFixedHbarFee(1, COLLECTOR))
                            .hasFeeExemptKeys(List.of(key, key2)));
        }

        @HapiTest
        @DisplayName("to remove one of the keys in fee except key list")
        final Stream<DynamicTest> updateToRemoveOneOfTheKeysFromFeeExemptKeysList() {
            final var key = "key";
            final var key2 = "key2";

            return hapiTest(
                    cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                    newKeyNamed(key),
                    newKeyNamed(key2),

                    // Create a topic with two keys in feeExemptKeyList
                    createTopic(TOPIC)
                            .withConsensusCustomFee(fixedConsensusHbarFee(1, COLLECTOR))
                            .feeExemptKeys(key, key2)
                            .adminKeyName(ADMIN_KEY),
                    getTopicInfo(TOPIC).hasFeeExemptKeys(List.of(key, key2)),

                    // Update the topic to remove one of the keys
                    atomicBatch(updateTopic(TOPIC)
                                    .feeExemptKeys(key2)
                                    .signedByPayerAnd(ADMIN_KEY)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR),
                    getTopicInfo(TOPIC).hasFeeExemptKeys(List.of(key2)));
        }

        @HapiTest
        @DisplayName("to remove all keys from fee exempt key list key")
        final Stream<DynamicTest> updateToRemoveAllFeeExemptKeyListKeys() {
            final var key = "key";
            final var key2 = "key2";

            return hapiTest(
                    cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                    newKeyNamed(key),
                    newKeyNamed(key2),

                    // Create a topic with two keys in feeExemptKeyList
                    createTopic(TOPIC)
                            .withConsensusCustomFee(fixedConsensusHbarFee(1, COLLECTOR))
                            .feeExemptKeys(key, key2)
                            .adminKeyName(ADMIN_KEY),
                    getTopicInfo(TOPIC).hasFeeExemptKeys(List.of(key, key2)),

                    // Update the topic to remove all the fee except keys
                    atomicBatch(updateTopic(TOPIC)
                                    .withEmptyFeeExemptKeyList()
                                    .signedByPayerAnd(ADMIN_KEY)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR),
                    getTopicInfo(TOPIC).hasEmptyFeeExemptKeyList());
        }

        @HapiTest
        @DisplayName("to replace all fee exempt keys")
        final Stream<DynamicTest> updateToReplaceAllKeys() {
            final var key = "key";
            final var key2 = "key2";
            final var key3 = "key3";
            final var key4 = "key4";

            return hapiTest(
                    cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                    newKeyNamed(key),
                    newKeyNamed(key2),
                    newKeyNamed(key3),
                    newKeyNamed(key4),

                    // Create a topic with two keys in feeExemptKeyList
                    createTopic(TOPIC)
                            .withConsensusCustomFee(fixedConsensusHbarFee(1, COLLECTOR))
                            .feeExemptKeys(key, key2)
                            .adminKeyName(ADMIN_KEY),
                    getTopicInfo(TOPIC).hasFeeExemptKeys(List.of(key, key2)),

                    // Update the topic to remove all the fee except keys
                    atomicBatch(updateTopic(TOPIC)
                                    .feeExemptKeys(key3, key4)
                                    .signedByPayerAnd(ADMIN_KEY)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR),
                    getTopicInfo(TOPIC).hasFeeExemptKeys(List.of(key3, key4)));
        }

        @HapiTest
        @DisplayName("a topic that contains deleted key")
        final Stream<DynamicTest> updateTopicWhenKeyInFeeExemptKeyListIsDeleted() {
            final var alice = "alice";
            return hapiTest(
                    cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                    cryptoCreate(alice),

                    // Create a topic with fee exempt key alice
                    createTopic(TOPIC)
                            .withConsensusCustomFee(fixedConsensusHbarFee(1, COLLECTOR))
                            .feeExemptKeys(alice)
                            .adminKeyName(ADMIN_KEY),

                    // Delete alice
                    cryptoDelete(alice),

                    // Do a dummy update and verify that it updates successfully
                    atomicBatch(updateTopic(TOPIC)
                                    .memo("dummy change")
                                    .signedByPayerAnd(ADMIN_KEY)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR));
        }

        @HapiTest
        @DisplayName("to update the fee schedule key")
        final Stream<DynamicTest> updateTheFeeScheduleKey() {
            return hapiTest(
                    cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                    // Create a topic and verify that the keys are correct
                    createTopic(TOPIC).adminKeyName(ADMIN_KEY).feeScheduleKeyName(FEE_SCHEDULE_KEY),
                    getTopicInfo(TOPIC).hasAdminKey(ADMIN_KEY).hasFeeScheduleKey(FEE_SCHEDULE_KEY),

                    // Update the fee schedule and sign with the new key
                    atomicBatch(updateTopic(TOPIC)
                                    .feeScheduleKeyName(FEE_SCHEDULE_KEY2)
                                    .signedByPayerAnd(ADMIN_KEY, FEE_SCHEDULE_KEY2)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR),
                    getTopicInfo(TOPIC).hasFeeScheduleKey(FEE_SCHEDULE_KEY2));
        }

        @HapiTest
        @DisplayName("to add a custom fee to a topic without")
        final Stream<DynamicTest> updateToAddACustomFee() {
            return hapiTest(
                    cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                    // create a topic without a custom fee
                    createTopic(TOPIC).adminKeyName(ADMIN_KEY).feeScheduleKeyName(FEE_SCHEDULE_KEY),

                    // Update the topic to add a custom fee
                    atomicBatch(updateTopic(TOPIC)
                                    .withConsensusCustomFee(fixedConsensusHbarFee(1, COLLECTOR))
                                    .signedByPayerAnd(ADMIN_KEY, FEE_SCHEDULE_KEY)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR),
                    getTopicInfo(TOPIC).hasCustomFee(expectedConsensusFixedHbarFee(1, COLLECTOR)));
        }

        @HapiTest
        @DisplayName("to remove the fee schedule key and the custom fee should not be deleted")
        final Stream<DynamicTest> updateToRemoveTheFeeScheduleKeyCustomFeeShouldStay() {
            return hapiTest(
                    cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                    // Create a topic with a fee schedule key and a custom fee
                    createTopic(TOPIC)
                            .adminKeyName(ADMIN_KEY)
                            .feeScheduleKeyName(FEE_SCHEDULE_KEY)
                            .withConsensusCustomFee(fixedConsensusHbarFee(1, COLLECTOR)),

                    // Update the fee schedule to remove the fee schedule key
                    atomicBatch(updateTopic(TOPIC)
                                    .withEmptyFeeScheduleKey()
                                    .signedByPayerAnd(ADMIN_KEY, FEE_SCHEDULE_KEY)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR),
                    getTopicInfo(TOPIC)
                            .hasNoFeeScheduleKey()
                            .hasCustomFee(expectedConsensusFixedHbarFee(1, COLLECTOR)));
        }

        @HapiTest
        @DisplayName("to remove the fee schedule key that doesn't exists")
        final Stream<DynamicTest> removeTheFeeScheduleKeyWhenItDoNotExists() {
            return hapiTest(
                    cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                    // Create a topic
                    createTopic(TOPIC).adminKeyName(ADMIN_KEY),

                    // Update the custom fee to add custom fee
                    atomicBatch(updateTopic(TOPIC)
                                    .withEmptyFeeScheduleKey()
                                    .signedByPayerAnd(ADMIN_KEY)
                                    .hasKnownStatus(FEE_SCHEDULE_KEY_CANNOT_BE_UPDATED)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR)
                            .hasKnownStatus(INNER_TRANSACTION_FAILED));
        }

        @HapiTest
        @DisplayName("to add the same exempt key")
        final Stream<DynamicTest> updateToAddTheSameExemptKey() {
            final var exemptKey = "exemptKey";
            return hapiTest(
                    cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                    // Create a topic with a fee exempt key
                    newKeyNamed(exemptKey),
                    createTopic(TOPIC).adminKeyName(ADMIN_KEY).feeExemptKeys(exemptKey),

                    // Update topic to add the same exempt key
                    atomicBatch(updateTopic(TOPIC)
                                    .feeExemptKeys(exemptKey)
                                    .signedByPayerAnd(ADMIN_KEY)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR),
                    getTopicInfo(TOPIC).hasFeeExemptKeys(List.of(exemptKey)));
        }

        @HapiTest
        @DisplayName(
                "fee schedule key and custom fee - should sign with admin_key, old fee schedule key and new fee schedule key")
        @Tag(MATS)
        final Stream<DynamicTest> updateFeeScheduleKeyAndCustomFeeSignWithAdminAndTwoFeeScheduleKeys() {
            return hapiTest(
                    cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                    createTopic(TOPIC).adminKeyName(ADMIN_KEY).feeScheduleKeyName(FEE_SCHEDULE_KEY),

                    // Update the fee schedule key and custom fee
                    atomicBatch(updateTopic(TOPIC)
                                    .feeScheduleKeyName(FEE_SCHEDULE_KEY2)
                                    .withConsensusCustomFee(fixedConsensusHbarFee(1, COLLECTOR))
                                    .signedByPayerAnd(ADMIN_KEY, FEE_SCHEDULE_KEY, FEE_SCHEDULE_KEY2)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR));
        }
    }

    @Nested
    @DisplayName("Negative scenarios")
    class TopicCreateNegativeScenarios {

        @HapiTest
        @DisplayName("to add a custom fee with a invalid collector")
        final Stream<DynamicTest> updateToAddCustomFeeWithInvalidCollector() {
            final byte[] publicKey =
                    CommonUtils.unhex("0000000000000000000000000000000000000000000000000000000000000000");
            final ByteString evmAddress = ByteStringUtils.wrapUnsafely(recoverAddressFromPubKey(publicKey));
            final var accountId = HapiPropertySource.asAccount(evmAddress);

            return hapiTest(
                    cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                    // Create a topic without custom fees
                    createTopic(TOPIC).adminKeyName(ADMIN_KEY).feeScheduleKeyName(FEE_SCHEDULE_KEY),

                    // Update the topic to have an empty address as a collector
                    atomicBatch(updateTopic(TOPIC)
                                    .withConsensusCustomFee(fixedConsensusHbarFee(1, accountId))
                                    .signedByPayerAnd(ADMIN_KEY, FEE_SCHEDULE_KEY)
                                    .hasKnownStatus(INVALID_CUSTOM_FEE_COLLECTOR)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR)
                            .hasKnownStatus(INNER_TRANSACTION_FAILED));
        }

        @HapiTest
        @DisplayName("the fee schedule key when it was empty on create")
        final Stream<DynamicTest> updateTheFeeScheduleKeyWhenWasEmpty() {
            return hapiTest(
                    cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                    // Create a topic without custom fees
                    createTopic(TOPIC).adminKeyName(ADMIN_KEY),
                    atomicBatch(updateTopic(TOPIC)
                                    .feeScheduleKeyName(FEE_SCHEDULE_KEY)
                                    .signedByPayerAnd(ADMIN_KEY, FEE_SCHEDULE_KEY)
                                    .hasKnownStatus(FEE_SCHEDULE_KEY_CANNOT_BE_UPDATED)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR)
                            .hasKnownStatus(INNER_TRANSACTION_FAILED));
        }

        @HapiTest
        @DisplayName("to remove the fee schedule key without the fee schedule key to sign")
        final Stream<DynamicTest> updateToRemoveTheFeeScheduleKeyWithoutFeeScheduleToSign() {
            return hapiTest(
                    cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                    // Create a topic with a fee schedule key and a custom fee
                    createTopic(TOPIC).adminKeyName(ADMIN_KEY).feeScheduleKeyName(FEE_SCHEDULE_KEY),

                    // Update the fee schedule to remove the fee schedule key
                    atomicBatch(updateTopic(TOPIC)
                                    .withEmptyFeeScheduleKey()
                                    .signedByPayerAnd(ADMIN_KEY)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR),
                    getTopicInfo(TOPIC).hasNoFeeScheduleKey());
        }

        @HapiTest
        @DisplayName("to remove the fee schedule key without the admin key to sign")
        final Stream<DynamicTest> updateToRemoveTheFeeScheduleKeyWithoutAdminToSign() {
            return hapiTest(
                    cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                    // Create a topic with a fee schedule key and a custom fee
                    createTopic(TOPIC).adminKeyName(ADMIN_KEY).feeScheduleKeyName(FEE_SCHEDULE_KEY),

                    // Update the fee schedule to remove the fee schedule key
                    atomicBatch(updateTopic(TOPIC)
                                    .withEmptyFeeScheduleKey()
                                    .signedByPayerAnd(FEE_SCHEDULE_KEY)
                                    .hasKnownStatus(INVALID_SIGNATURE)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR)
                            .hasKnownStatus(INNER_TRANSACTION_FAILED));
        }

        @HapiTest
        @DisplayName("to reach the limit + 1 custom fees")
        final Stream<DynamicTest> updateCustomFeesToReachTheLimitPlusOne() {
            return hapiTest(
                    cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                    // Create a topic with one custom fees
                    createTopic(TOPIC)
                            .adminKeyName(ADMIN_KEY)
                            .feeScheduleKeyName(FEE_SCHEDULE_KEY)
                            .withConsensusCustomFee(fixedConsensusHbarFee(1, COLLECTOR)),
                    getTopicInfo(TOPIC).hasCustomFee(expectedConsensusFixedHbarFee(1, COLLECTOR)),

                    // Update the topic to add 10 more custom fees(reaching the limit + 1 custom fees)
                    cryptoCreate("collector2"),
                    cryptoCreate("collector3"),
                    cryptoCreate("collector4"),
                    cryptoCreate("collector5"),
                    cryptoCreate("collector6"),
                    cryptoCreate("collector7"),
                    cryptoCreate("collector8"),
                    cryptoCreate("collector9"),
                    cryptoCreate("collector10"),
                    cryptoCreate("collector11"),
                    atomicBatch(updateTopic(TOPIC)
                                    .withConsensusCustomFee(fixedConsensusHbarFee(1, COLLECTOR))
                                    .withConsensusCustomFee(fixedConsensusHbarFee(1, "collector2"))
                                    .withConsensusCustomFee(fixedConsensusHbarFee(1, "collector3"))
                                    .withConsensusCustomFee(fixedConsensusHbarFee(1, "collector4"))
                                    .withConsensusCustomFee(fixedConsensusHbarFee(1, "collector5"))
                                    .withConsensusCustomFee(fixedConsensusHbarFee(1, "collector6"))
                                    .withConsensusCustomFee(fixedConsensusHbarFee(1, "collector7"))
                                    .withConsensusCustomFee(fixedConsensusHbarFee(1, "collector8"))
                                    .withConsensusCustomFee(fixedConsensusHbarFee(1, "collector9"))
                                    .withConsensusCustomFee(fixedConsensusHbarFee(1, "collector10"))
                                    .withConsensusCustomFee(fixedConsensusHbarFee(1, "collector11"))
                                    .signedByPayerAnd(ADMIN_KEY, FEE_SCHEDULE_KEY)
                                    .hasKnownStatus(CUSTOM_FEES_LIST_TOO_LONG)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR)
                            .hasKnownStatus(INNER_TRANSACTION_FAILED));
        }

        @HapiTest
        @DisplayName("the custom fee without having a custom fee and a schedule key")
        final Stream<DynamicTest> updateCustomFeeWithoutHavingCustomFeeAndScheduleKey() {
            return hapiTest(
                    cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                    // Create a topic
                    createTopic(TOPIC).adminKeyName(ADMIN_KEY),

                    // Update the custom fee to add custom fee
                    atomicBatch(updateTopic(TOPIC)
                                    .withConsensusCustomFee(fixedConsensusHbarFee(1, COLLECTOR))
                                    .signedByPayerAnd(ADMIN_KEY)
                                    .hasKnownStatus(FEE_SCHEDULE_KEY_NOT_SET)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR)
                            .hasKnownStatus(INNER_TRANSACTION_FAILED));
        }

        @HapiTest
        @DisplayName("to remove the custom fees sign with admin key only")
        final Stream<DynamicTest> removeCustomFeeSignWithAdminOnly() {
            return hapiTest(
                    cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                    // Create a topic and verify custom fee is correct
                    createTopic(TOPIC)
                            .adminKeyName(ADMIN_KEY)
                            .feeScheduleKeyName(FEE_SCHEDULE_KEY)
                            .withConsensusCustomFee(fixedConsensusHtsFee(1, TOKEN, COLLECTOR)),

                    // Update the custom fee and verify that it's updated
                    atomicBatch(updateTopic(TOPIC)
                                    .withEmptyCustomFee()
                                    .signedByPayerAnd(ADMIN_KEY)
                                    .hasKnownStatus(INVALID_SIGNATURE)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR)
                            .hasKnownStatus(INNER_TRANSACTION_FAILED));
        }

        @HapiTest
        @DisplayName("the custom fee with no fee schedule key")
        final Stream<DynamicTest> updateTheCustomFeeWithNoFeeScheduleKey() {
            return hapiTest(
                    cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                    // Create a topic without custom fees
                    createTopic(TOPIC).adminKeyName(ADMIN_KEY),
                    atomicBatch(updateTopic(TOPIC)
                                    .withConsensusCustomFee(fixedConsensusHbarFee(1, COLLECTOR))
                                    .signedByPayerAnd(ADMIN_KEY)
                                    .hasKnownStatus(FEE_SCHEDULE_KEY_NOT_SET)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR)
                            .hasKnownStatus(INNER_TRANSACTION_FAILED));
        }

        @HapiTest
        @DisplayName("to add a custom fee without a fee collector")
        final Stream<DynamicTest> updateToAddCustomFeeWithoutFeeCollector() {
            return hapiTest(
                    cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                    // Create a topic with fee schedule key
                    createTopic(TOPIC).feeScheduleKeyName(FEE_SCHEDULE_KEY).adminKeyName(ADMIN_KEY),

                    // Update the topic with custom fee without a fee collector
                    atomicBatch(updateTopic(TOPIC)
                                    .withConsensusCustomFee(fixedConsensusHbarFeeNoCollector(1))
                                    .signedByPayerAnd(ADMIN_KEY, FEE_SCHEDULE_KEY)
                                    .hasKnownStatus(INVALID_CUSTOM_FEE_COLLECTOR)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR)
                            .hasKnownStatus(INNER_TRANSACTION_FAILED));
        }

        @HapiTest
        @DisplayName("custom fee with deleted token")
        final Stream<DynamicTest> updateCustomFeeWithDeletedToken() {
            final var tokenToDeleted = "tokenToDeleted";
            final var tokenAdminKey = "tokenAdminKey";

            return hapiTest(
                    cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                    createTopic(TOPIC).feeScheduleKeyName(FEE_SCHEDULE_KEY).adminKeyName(ADMIN_KEY),
                    newKeyNamed(tokenAdminKey),
                    tokenCreate(tokenToDeleted)
                            .tokenType(TokenType.FUNGIBLE_COMMON)
                            .initialSupply(500)
                            .adminKey(tokenAdminKey),
                    tokenDelete(tokenToDeleted).signedByPayerAnd(tokenAdminKey),
                    atomicBatch(updateTopic(TOPIC)
                                    .withConsensusCustomFee(fixedConsensusHtsFee(1, tokenToDeleted, COLLECTOR))
                                    .signedByPayerAnd(ADMIN_KEY, FEE_SCHEDULE_KEY)
                                    .hasKnownStatus(INVALID_TOKEN_ID_IN_CUSTOM_FEES)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR)
                            .hasKnownStatus(INNER_TRANSACTION_FAILED));
        }

        @HapiTest
        @DisplayName("the custom fee to be payed by NFT")
        final Stream<DynamicTest> updateCustomFeeToBePayedWithNFT() {
            final var nft = "nft";
            final var supplyKey = "supplyKey";

            return hapiTest(
                    cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                    createTopic(TOPIC).feeScheduleKeyName(FEE_SCHEDULE_KEY).adminKeyName(ADMIN_KEY),

                    // create a NFT
                    newKeyNamed(supplyKey),
                    tokenCreate(nft)
                            .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                            .name(nft)
                            .symbol("TOKEN")
                            .supplyType(TokenSupplyType.INFINITE)
                            .supplyKey(supplyKey)
                            .initialSupply(0),

                    // update the custom fee to be paid by the NFT
                    atomicBatch(updateTopic(TOPIC)
                                    .withConsensusCustomFee(fixedConsensusHtsFee(1, nft, COLLECTOR))
                                    .signedByPayerAnd(ADMIN_KEY, FEE_SCHEDULE_KEY)
                                    .hasKnownStatus(CUSTOM_FEE_DENOMINATION_MUST_BE_FUNGIBLE_COMMON)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR)
                            .hasKnownStatus(INNER_TRANSACTION_FAILED));
        }

        @HapiTest
        @DisplayName("topic with custom fees to set custom fees with a deleted account as a collector")
        final Stream<DynamicTest> updateTopicWithCustomFeesSetCustomFeesWithDeletedAccountAsCollector() {
            final var collectorToBeDeleted = "collectorToBeDeleted";
            return hapiTest(
                    cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                    createTopic(TOPIC)
                            .withConsensusCustomFee(fixedConsensusHbarFee(1, COLLECTOR))
                            .feeScheduleKeyName(FEE_SCHEDULE_KEY)
                            .adminKeyName(ADMIN_KEY),
                    cryptoCreate(collectorToBeDeleted),
                    cryptoDelete(collectorToBeDeleted),

                    // Update to set a deleted account as a collector
                    atomicBatch(updateTopic(TOPIC)
                                    .withConsensusCustomFee(fixedConsensusHbarFee(1, collectorToBeDeleted))
                                    .signedByPayerAnd(ADMIN_KEY, FEE_SCHEDULE_KEY)
                                    .hasKnownStatus(ACCOUNT_DELETED)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR)
                            .hasKnownStatus(INNER_TRANSACTION_FAILED));
        }

        @HapiTest
        @DisplayName("topic with no custom fees to set custom fees with a deleted account as a collector")
        final Stream<DynamicTest> updateTopicWithNoCustomFeesSetCustomFeesWithDeletedAccountAsCollector() {
            final var collectorToBeDeleted = "collectorToBeDeleted";
            return hapiTest(
                    cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                    createTopic(TOPIC).feeScheduleKeyName(FEE_SCHEDULE_KEY).adminKeyName(ADMIN_KEY),
                    cryptoCreate(collectorToBeDeleted),
                    cryptoDelete(collectorToBeDeleted),

                    // Update to set a deleted account as a collector
                    atomicBatch(updateTopic(TOPIC)
                                    .withConsensusCustomFee(fixedConsensusHbarFee(1, collectorToBeDeleted))
                                    .signedByPayerAnd(ADMIN_KEY, FEE_SCHEDULE_KEY)
                                    .hasKnownStatus(ACCOUNT_DELETED)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR)
                            .hasKnownStatus(INNER_TRANSACTION_FAILED));
        }

        @HapiTest
        @DisplayName("the custom fee with 0 for the amount")
        final Stream<DynamicTest> updateCustomFeeWith0ForAmount() {
            return hapiTest(
                    cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                    createTopic(TOPIC)
                            .adminKeyName(ADMIN_KEY)
                            .feeScheduleKeyName(FEE_SCHEDULE_KEY)
                            .withConsensusCustomFee(fixedConsensusHbarFee(1, COLLECTOR)),
                    atomicBatch(updateTopic(TOPIC)
                                    .withConsensusCustomFee(fixedConsensusHbarFee(0, COLLECTOR))
                                    .signedByPayerAnd(FEE_SCHEDULE_KEY, ADMIN_KEY)
                                    .hasKnownStatus(CUSTOM_FEE_MUST_BE_POSITIVE)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR)
                            .hasKnownStatus(INNER_TRANSACTION_FAILED));
        }

        @HapiTest
        @DisplayName("the custom fee with multiple custom fees one of them with 0 for the amount")
        final Stream<DynamicTest> updateCustomFeeWithMultipleFeesOneOfThemWith0ForAmount() {
            return hapiTest(
                    cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                    createTopic(TOPIC)
                            .adminKeyName(ADMIN_KEY)
                            .feeScheduleKeyName(FEE_SCHEDULE_KEY)
                            .withConsensusCustomFee(fixedConsensusHbarFee(1, COLLECTOR)),
                    atomicBatch(updateTopic(TOPIC)
                                    .withConsensusCustomFee(fixedConsensusHbarFee(1, COLLECTOR))
                                    .withConsensusCustomFee(fixedConsensusHbarFee(0, COLLECTOR))
                                    .withConsensusCustomFee(fixedConsensusHbarFee(2, COLLECTOR))
                                    .signedByPayerAnd(FEE_SCHEDULE_KEY, ADMIN_KEY)
                                    .hasKnownStatus(CUSTOM_FEE_MUST_BE_POSITIVE)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR)
                            .hasKnownStatus(INNER_TRANSACTION_FAILED));
        }

        @HapiTest
        @DisplayName("the custom fee collector that is not associated with the token")
        final Stream<DynamicTest> updateCustomFeeCollectorThatIsNotAssociatedWithToken() {
            final var collector3 = "collector3";
            return hapiTest(
                    cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                    createTopic(TOPIC)
                            .adminKeyName(ADMIN_KEY)
                            .feeScheduleKeyName(FEE_SCHEDULE_KEY)
                            .withConsensusCustomFee(fixedConsensusHbarFee(1, COLLECTOR)),

                    // Updating the topic's custom fees with a collector not associated with the token
                    cryptoCreate(collector3),
                    atomicBatch(updateTopic(TOPIC)
                                    .withConsensusCustomFee(fixedConsensusHtsFee(1, TOKEN, collector3))
                                    .signedByPayerAnd(ADMIN_KEY, FEE_SCHEDULE_KEY)
                                    .hasKnownStatus(TOKEN_NOT_ASSOCIATED_TO_FEE_COLLECTOR)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR)
                            .hasKnownStatus(INNER_TRANSACTION_FAILED));
        }

        @HapiTest
        @DisplayName("the custom fee with frozen collector")
        final Stream<DynamicTest> updateCustomFeeWithFrozenCollector() {
            final var collector4 = "collector4";
            return hapiTest(
                    cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                    createTopic(TOPIC)
                            .adminKeyName(ADMIN_KEY)
                            .feeScheduleKeyName(FEE_SCHEDULE_KEY)
                            .withConsensusCustomFee(fixedConsensusHbarFee(1, COLLECTOR)),

                    // Update the token with a custom fee with frozen collector
                    cryptoCreate(collector4),
                    tokenAssociate(collector4, TOKEN),
                    tokenFreeze(TOKEN, collector4),
                    atomicBatch(updateTopic(TOPIC)
                                    .withConsensusCustomFee(fixedConsensusHtsFee(1, TOKEN, collector4))
                                    .signedByPayerAnd(ADMIN_KEY, FEE_SCHEDULE_KEY)
                                    .hasKnownStatus(ACCOUNT_FROZEN_FOR_TOKEN)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR)
                            .hasKnownStatus(INNER_TRANSACTION_FAILED));
        }

        @HapiTest
        @DisplayName("the custom fee with deleted collector")
        final Stream<DynamicTest> updateCustomFeeWithDeletedCollector() {
            final var token = "token";
            final var tokenAdmin = "tokenAdmin";
            final var collector5 = "collector5";
            return hapiTest(
                    cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                    createTopic(TOPIC).adminKeyName(ADMIN_KEY).feeScheduleKeyName(FEE_SCHEDULE_KEY),

                    // Create a token and delete it
                    newKeyNamed(tokenAdmin),
                    tokenCreate(token).tokenType(TokenType.FUNGIBLE_COMMON).adminKey(tokenAdmin),
                    tokenDelete(token),

                    // Update the topic to set the deleted token as a custom fee
                    cryptoCreate(collector5),
                    atomicBatch(updateTopic(TOPIC)
                                    .withConsensusCustomFee(fixedConsensusHtsFee(1, token, collector5))
                                    .signedByPayerAnd(ADMIN_KEY, FEE_SCHEDULE_KEY)
                                    .hasKnownStatus(INVALID_TOKEN_ID_IN_CUSTOM_FEES)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR)
                            .hasKnownStatus(INNER_TRANSACTION_FAILED));
        }

        @HapiTest
        @DisplayName("the custom fee with paused collector")
        final Stream<DynamicTest> updateCustomFeeWithPausedCollector() {
            final var token = "token";
            final var tokenAdmin = "tokenAdmin";
            final var collector5 = "collector5";
            return hapiTest(
                    cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                    createTopic(TOPIC).adminKeyName(ADMIN_KEY).feeScheduleKeyName(FEE_SCHEDULE_KEY),

                    // Create a token and pause it
                    newKeyNamed(tokenAdmin),
                    tokenCreate(token).tokenType(TokenType.FUNGIBLE_COMMON).pauseKey(tokenAdmin),
                    tokenPause(token),

                    // Update the topic to set the paused token as a custom fee
                    cryptoCreate(collector5),
                    atomicBatch(updateTopic(TOPIC)
                                    .withConsensusCustomFee(fixedConsensusHtsFee(1, token, collector5))
                                    .signedByPayerAnd(ADMIN_KEY, FEE_SCHEDULE_KEY)
                                    .hasKnownStatus(INVALID_TOKEN_ID_IN_CUSTOM_FEES)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR)
                            .hasKnownStatus(INNER_TRANSACTION_FAILED));
        }

        @HapiTest
        @DisplayName("with no admin key")
        final Stream<DynamicTest> updateWithNoAdminKey() {
            return hapiTest(flattened(
                    cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                    // Create a topic without an admin key
                    createTopic(TOPIC).feeScheduleKeyName(FEE_SCHEDULE_KEY),

                    // Update the FEKL list without signing with an admin key
                    newNamedKeysForFEKL(2),
                    atomicBatch(updateTopic(TOPIC)
                                    .feeExemptKeys(feeExemptKeyNames(2))
                                    .signedByPayerAnd(FEE_SCHEDULE_KEY)
                                    .hasKnownStatus(UNAUTHORIZED)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR)
                            .hasKnownStatus(INNER_TRANSACTION_FAILED)));
        }

        @HapiTest
        @DisplayName("the custom fee signed with the wrong fee schedule key")
        final Stream<DynamicTest> updateCustomFeeSingledWithTheWrongFeeScheduleKey() {
            return hapiTest(
                    cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                    createTopic(TOPIC).adminKeyName(ADMIN_KEY).feeScheduleKeyName(FEE_SCHEDULE_KEY),

                    // Update the fee schedule and sign with the wrong key
                    atomicBatch(updateTopic(TOPIC)
                                    .withConsensusCustomFee(fixedConsensusHbarFee(1, COLLECTOR))
                                    .signedByPayerAnd(ADMIN_KEY, FEE_SCHEDULE_KEY2)
                                    .hasKnownStatus(INVALID_SIGNATURE)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR)
                            .hasKnownStatus(INNER_TRANSACTION_FAILED));
        }

        @HapiTest
        @DisplayName("the custom fee without signing with the fee schedule key")
        final Stream<DynamicTest> updateCustomFeeWithoutSigningWithFeeScheduleKey() {
            return hapiTest(
                    cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                    createTopic(TOPIC).adminKeyName(ADMIN_KEY).feeScheduleKeyName(FEE_SCHEDULE_KEY),

                    // Update the fee schedule and sign with the wrong key
                    atomicBatch(updateTopic(TOPIC)
                                    .withConsensusCustomFee(fixedConsensusHbarFee(1, COLLECTOR))
                                    .signedByPayerAnd(ADMIN_KEY)
                                    .hasKnownStatus(INVALID_SIGNATURE)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR)
                            .hasKnownStatus(INNER_TRANSACTION_FAILED));
        }

        @HapiTest
        @DisplayName("with custom fee exceeding the finite token's max supply")
        final Stream<DynamicTest> updateWithCustomFeeExceedingFiniteTokenMaxSupply() {
            final var finiteToken = "finiteToken";
            return hapiTest(
                    cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                    createTopic(TOPIC).adminKeyName(ADMIN_KEY).feeScheduleKeyName(FEE_SCHEDULE_KEY),

                    // Create a topic with finite amount
                    tokenCreate(finiteToken)
                            .tokenType(TokenType.FUNGIBLE_COMMON)
                            .supplyType(TokenSupplyType.FINITE)
                            .initialSupply(0)
                            .maxSupply(100),

                    // Update the topic amount exceeding the finite token's max supply
                    atomicBatch(updateTopic(TOPIC)
                                    .withConsensusCustomFee(fixedConsensusHtsFee(101, finiteToken, COLLECTOR))
                                    .signedByPayerAnd(ADMIN_KEY, FEE_SCHEDULE_KEY)
                                    .hasKnownStatus(AMOUNT_EXCEEDS_TOKEN_MAX_SUPPLY)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR)
                            .hasKnownStatus(INNER_TRANSACTION_FAILED));
        }

        @HapiTest
        @DisplayName("with custom fee amount to Long.MAX_VALUE + 1")
        final Stream<DynamicTest> updateCustomFeeAmountToMaxValuePlusOne() {
            final var finiteToken = "finiteToken";
            return hapiTest(
                    cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                    createTopic(TOPIC).adminKeyName(ADMIN_KEY).feeScheduleKeyName(FEE_SCHEDULE_KEY),

                    // Create a topic with finite amount
                    tokenCreate(finiteToken)
                            .tokenType(TokenType.FUNGIBLE_COMMON)
                            .supplyType(TokenSupplyType.FINITE)
                            .initialSupply(0)
                            .maxSupply(100),

                    // Update the topic amount to MAX_VALUE + 1(overflowing the max and essentially passing a negative
                    // value)
                    atomicBatch(updateTopic(TOPIC)
                                    .withConsensusCustomFee(
                                            fixedConsensusHtsFee(Long.MAX_VALUE + 1, finiteToken, COLLECTOR))
                                    .signedByPayerAnd(ADMIN_KEY, FEE_SCHEDULE_KEY)
                                    .hasKnownStatus(CUSTOM_FEE_MUST_BE_POSITIVE)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR)
                            .hasKnownStatus(INNER_TRANSACTION_FAILED));
        }

        @HapiTest
        @DisplayName("the custom fees with deleted collector")
        final Stream<DynamicTest> updateTheCustomFeesWithDeletedCollector() {
            final var collector5 = "collector5";
            return hapiTest(
                    cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                    cryptoCreate(collector5),
                    createTopic(TOPIC)
                            .withConsensusCustomFee(fixedConsensusHbarFee(1, collector5))
                            .adminKeyName(ADMIN_KEY)
                            .feeScheduleKeyName(FEE_SCHEDULE_KEY),
                    cryptoDelete(collector5),

                    // Update the topic to have the deleted collector
                    atomicBatch(updateTopic(TOPIC)
                                    .withConsensusCustomFee(fixedConsensusHbarFee(2, collector5))
                                    .signedByPayerAnd(ADMIN_KEY, FEE_SCHEDULE_KEY)
                                    .hasKnownStatus(ACCOUNT_DELETED)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR)
                            .hasKnownStatus(INNER_TRANSACTION_FAILED));
        }

        @HapiTest
        @DisplayName("fee schedule key and custom fee - do not sign with admin key")
        final Stream<DynamicTest> updateFeeScheduleKeyAndCustomFeeDoNotSignWithAdminKey() {
            return hapiTest(
                    cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                    createTopic(TOPIC).adminKeyName(ADMIN_KEY).feeScheduleKeyName(FEE_SCHEDULE_KEY),

                    // Update the fee schedule key and custom fee
                    atomicBatch(updateTopic(TOPIC)
                                    .feeScheduleKeyName(FEE_SCHEDULE_KEY2)
                                    .withConsensusCustomFee(fixedConsensusHbarFee(1, COLLECTOR))
                                    .signedByPayerAnd(FEE_SCHEDULE_KEY, FEE_SCHEDULE_KEY2)
                                    .hasKnownStatus(INVALID_SIGNATURE)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR)
                            .hasKnownStatus(INNER_TRANSACTION_FAILED));
        }

        @HapiTest
        @DisplayName("fee schedule key and custom fee - should sign with admin_key only")
        final Stream<DynamicTest> updateFeeScheduleKeyAndCustomFeeSignWithAdminOnly() {
            return hapiTest(
                    cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                    createTopic(TOPIC).adminKeyName(ADMIN_KEY).feeScheduleKeyName(FEE_SCHEDULE_KEY),

                    // Update the fee schedule key and custom fee
                    atomicBatch(updateTopic(TOPIC)
                                    .feeScheduleKeyName(FEE_SCHEDULE_KEY2)
                                    .withConsensusCustomFee(fixedConsensusHbarFee(1, COLLECTOR))
                                    .signedByPayerAnd(ADMIN_KEY)
                                    .hasKnownStatus(INVALID_SIGNATURE)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR)
                            .hasKnownStatus(INNER_TRANSACTION_FAILED));
        }

        @HapiTest
        @DisplayName("with duplicated fee exempt keys")
        final Stream<DynamicTest> updateWithDuplicatedFeeExemptKeys() {
            final var key = "key";
            return hapiTest(
                    cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                    createTopic(TOPIC).adminKeyName(ADMIN_KEY).feeScheduleKeyName(FEE_SCHEDULE_KEY),
                    newKeyNamed(key),
                    atomicBatch(updateTopic(TOPIC)
                                    .feeExemptKeys(key, key)
                                    .signedByPayerAnd(ADMIN_KEY)
                                    .hasPrecheck(FEE_EXEMPT_KEY_LIST_CONTAINS_DUPLICATED_KEYS)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR)
                            .hasPrecheck(FEE_EXEMPT_KEY_LIST_CONTAINS_DUPLICATED_KEYS));
        }
    }
}
