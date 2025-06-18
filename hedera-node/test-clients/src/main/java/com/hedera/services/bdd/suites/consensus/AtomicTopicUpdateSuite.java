// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.consensus;

import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTopicInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.submitMessageTo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.updateTopic;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doSeveralWithStartupConfigNow;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.specOps;
import static com.hedera.services.bdd.suites.HapiSuite.EMPTY_KEY;
import static com.hedera.services.bdd.suites.HapiSuite.NONSENSE_KEY;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ZERO_BYTE_MEMO;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.AUTORENEW_ACCOUNT_NOT_ALLOWED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.AUTORENEW_DURATION_NOT_IN_RANGE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BAD_ENCODING;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.EXPIRATION_REDUCTION_NOT_ALLOWED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_AUTORENEW_ACCOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_EXPIRATION_TIME;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOPIC_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ZERO_BYTE_IN_STRING;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MEMO_TOO_LONG;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.UNAUTHORIZED;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.transactions.util.HapiAtomicBatch;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;

// This test cases are direct copies of TopicUpdateSuite\. The difference here is that
// we are wrapping the operations in an atomic batch to confirm that everything works as expected.
@HapiTestLifecycle
public class AtomicTopicUpdateSuite {

    private static final long validAutoRenewPeriod = 7_000_000L;
    private static final String BATCH_OPERATOR = "batchOperator";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(
                Map.of("atomicBatch.isEnabled", "true", "atomicBatch.maxNumberOfTransactions", "50"));
    }

    @HapiTest
    final Stream<DynamicTest> pureCheckFails() {
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                atomicBatch(updateTopic("0.0.1").hasPrecheck(INVALID_TOPIC_ID).batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> updateToMissingTopicFails() {
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                atomicBatch(updateTopic("1.2.3")
                                .hasKnownStatus(INVALID_TOPIC_ID)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> idVariantsTreatedAsExpected() {
        final var autoRenewAccount = "autoRenewAccount";
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                cryptoCreate(autoRenewAccount),
                cryptoCreate("replacementAccount"),
                newKeyNamed("adminKey"),
                createTopic("topic").adminKeyName("adminKey").autoRenewAccountId(autoRenewAccount),
                atomicBatch(updateTopic("topic")
                                .autoRenewAccountId("replacementAccount")
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR));
    }

    private HapiSpecOperation[] validateMultipleFieldsBase() {
        return new HapiSpecOperation[] {
            cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
            newKeyNamed("adminKey"),
            createTopic("testTopic").adminKeyName("adminKey")
        };
    }

    @HapiTest
    final Stream<DynamicTest> validateNonsenseAdminKey() {
        return hapiTest(flattened(
                validateMultipleFieldsBase(),
                atomicBatch(updateTopic("testTopic")
                                .adminKey(NONSENSE_KEY)
                                .hasPrecheckFrom(BAD_ENCODING, OK)
                                .hasKnownStatus(BAD_ENCODING)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)));
    }

    @HapiTest
    final Stream<DynamicTest> validateNonsenseSubmitKey() {
        return hapiTest(flattened(
                validateMultipleFieldsBase(),
                atomicBatch(updateTopic("testTopic")
                                .submitKey(NONSENSE_KEY)
                                .hasKnownStatus(BAD_ENCODING)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)));
    }

    @HapiTest
    final Stream<DynamicTest> validateLongMemoFields() {
        byte[] longBytes = new byte[1000];
        Arrays.fill(longBytes, (byte) 33);
        String longMemo = new String(longBytes, StandardCharsets.UTF_8);
        return hapiTest(flattened(
                validateMultipleFieldsBase(),
                atomicBatch(updateTopic("testTopic")
                                .topicMemo(longMemo)
                                .hasKnownStatus(MEMO_TOO_LONG)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)));
    }

    @HapiTest
    final Stream<DynamicTest> validateZeroLongMemoFields() {
        return hapiTest(flattened(
                validateMultipleFieldsBase(),
                atomicBatch(updateTopic("testTopic")
                                .topicMemo(ZERO_BYTE_MEMO)
                                .hasKnownStatus(INVALID_ZERO_BYTE_IN_STRING)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)));
    }

    @HapiTest
    final Stream<DynamicTest> validateAutoRenewPeriod0Fails() {
        return hapiTest(flattened(
                validateMultipleFieldsBase(),
                atomicBatch(updateTopic("testTopic")
                                .autoRenewPeriod(0)
                                .hasKnownStatus(AUTORENEW_DURATION_NOT_IN_RANGE)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)));
    }

    @HapiTest
    final Stream<DynamicTest> validateLongAutoRenewPeriodFails() {
        return hapiTest(flattened(
                validateMultipleFieldsBase(),
                atomicBatch(updateTopic("testTopic")
                                .autoRenewPeriod(Long.MAX_VALUE)
                                .hasKnownStatus(AUTORENEW_DURATION_NOT_IN_RANGE)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)));
    }

    @HapiTest
    final Stream<DynamicTest> updatingAutoRenewAccountWithoutAdminFails() {
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                cryptoCreate("autoRenewAccount"),
                cryptoCreate("payer"),
                createTopic("testTopic").autoRenewAccountId("autoRenewAccount").payingWith("payer"),
                atomicBatch(updateTopic("testTopic")
                                .autoRenewAccountId("payer")
                                .hasKnownStatus(UNAUTHORIZED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> updatingAutoRenewAccountWithAdminWorks() {
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                cryptoCreate("autoRenewAccount"),
                cryptoCreate("newAutoRenewAccount"),
                cryptoCreate("payer"),
                newKeyNamed("adminKey"),
                createTopic("testTopic")
                        .adminKeyName("adminKey")
                        .autoRenewAccountId("autoRenewAccount")
                        .payingWith("payer"),
                atomicBatch(updateTopic("testTopic")
                                .payingWith("payer")
                                .autoRenewAccountId("newAutoRenewAccount")
                                .signedBy("payer", "adminKey", "newAutoRenewAccount")
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR));
    }

    // TOPIC_RENEW_7
    @HapiTest
    final Stream<DynamicTest> updateTopicWithAdminKeyWithoutAutoRenewAccountWithNewAdminKey() {
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                cryptoCreate("payer"),
                newKeyNamed("adminKey"),
                newKeyNamed("newAdminKey"),
                createTopic("testTopic").adminKeyName("adminKey").payingWith("payer"),
                atomicBatch(updateTopic("testTopic")
                                .payingWith("payer")
                                .adminKey("newAdminKey")
                                .signedBy("payer", "adminKey", "newAdminKey")
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getTopicInfo("testTopic").logged().hasAdminKey("newAdminKey"));
    }

    // TOPIC_RENEW_8
    @HapiTest
    final Stream<DynamicTest> updateTopicWithoutAutoRenewAccountWithNewAutoRenewAccountAdded() {
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                cryptoCreate("autoRenewAccount"),
                cryptoCreate("payer"),
                newKeyNamed("adminKey"),
                createTopic("testTopic").adminKeyName("adminKey").payingWith("payer"),
                atomicBatch(updateTopic("testTopic")
                                .payingWith("payer")
                                .autoRenewAccountId("autoRenewAccount")
                                .signedBy("payer", "adminKey", "autoRenewAccount")
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getTopicInfo("testTopic").logged().hasAdminKey("adminKey").hasAutoRenewAccount("autoRenewAccount"));
    }

    @HapiTest
    final Stream<DynamicTest> topicUpdateSigReqsEnforcedAtConsensus() {
        long PAYER_BALANCE = 199_999_999_999L;
        Function<String[], HapiAtomicBatch> updateTopicSignedBy = (signers) -> atomicBatch(updateTopic("testTopic")
                        .payingWith("payer")
                        .adminKey("newAdminKey")
                        .autoRenewAccountId("newAutoRenewAccount")
                        .signedBy(signers)
                        .batchKey(BATCH_OPERATOR))
                .payingWith(BATCH_OPERATOR);

        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                newKeyNamed("oldAdminKey"),
                cryptoCreate("oldAutoRenewAccount"),
                newKeyNamed("newAdminKey"),
                cryptoCreate("newAutoRenewAccount"),
                cryptoCreate("payer").balance(PAYER_BALANCE),
                createTopic("testTopic").adminKeyName("oldAdminKey").autoRenewAccountId("oldAutoRenewAccount"),
                updateTopicSignedBy
                        .apply(new String[] {"payer", "oldAdminKey"})
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                updateTopicSignedBy
                        .apply(new String[] {"payer", "oldAdminKey", "newAdminKey"})
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                updateTopicSignedBy
                        .apply(new String[] {"payer", "oldAdminKey", "newAutoRenewAccount"})
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                updateTopicSignedBy
                        .apply(new String[] {"payer", "newAdminKey", "newAutoRenewAccount"})
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                updateTopicSignedBy
                        .apply(new String[] {"payer", "oldAdminKey", "newAdminKey", "newAutoRenewAccount"})
                        .hasKnownStatus(SUCCESS),
                getTopicInfo("testTopic")
                        .logged()
                        .hasAdminKey("newAdminKey")
                        .hasAutoRenewAccount("newAutoRenewAccount"));
    }

    // TOPIC_RENEW_10
    @HapiTest
    final Stream<DynamicTest> updateTopicWithoutAutoRenewAccountWithNewAutoRenewAccountAddedAndNewAdminKey() {
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                cryptoCreate("autoRenewAccount"),
                cryptoCreate("payer"),
                newKeyNamed("adminKey"),
                newKeyNamed("newAdminKey"),
                createTopic("testTopic").adminKeyName("adminKey").payingWith("payer"),
                atomicBatch(updateTopic("testTopic")
                                .payingWith("payer")
                                .adminKey("newAdminKey")
                                .autoRenewAccountId("autoRenewAccount")
                                .signedBy("payer", "adminKey", "newAdminKey", "autoRenewAccount")
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getTopicInfo("testTopic").logged().hasAdminKey("newAdminKey").hasAutoRenewAccount("autoRenewAccount"));
    }

    @HapiTest
    final Stream<DynamicTest> updateSubmitKeyToDiffKey() {
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                newKeyNamed("adminKey"),
                newKeyNamed("submitKey"),
                createTopic("testTopic").adminKeyName("adminKey"),
                atomicBatch(updateTopic("testTopic").submitKey("submitKey").batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getTopicInfo("testTopic")
                        .hasSubmitKey("submitKey")
                        .hasAdminKey("adminKey")
                        .logged());
    }

    @HapiTest
    final Stream<DynamicTest> canRemoveSubmitKeyDuringUpdate() {
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                newKeyNamed("adminKey"),
                newKeyNamed("submitKey"),
                createTopic("testTopic").adminKeyName("adminKey").submitKeyName("submitKey"),
                submitMessageTo("testTopic").message("message"),
                atomicBatch(updateTopic("testTopic").submitKey(EMPTY_KEY).batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getTopicInfo("testTopic").hasNoSubmitKey().hasAdminKey("adminKey"),
                submitMessageTo("testTopic").message("message").logged());
    }

    @HapiTest
    final Stream<DynamicTest> updateAdminKeyToDiffKey() {
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                newKeyNamed("adminKey"),
                newKeyNamed("updateAdminKey"),
                createTopic("testTopic").adminKeyName("adminKey"),
                atomicBatch(updateTopic("testTopic").adminKey("updateAdminKey").batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getTopicInfo("testTopic").hasAdminKey("updateAdminKey").logged());
    }

    @HapiTest
    final Stream<DynamicTest> updateAdminKeyToEmpty() {
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                newKeyNamed("adminKey"),
                createTopic("testTopic").adminKeyName("adminKey"),
                /* if adminKey is empty list should clear adminKey */
                atomicBatch(updateTopic("testTopic").adminKey(EMPTY_KEY).batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getTopicInfo("testTopic").hasNoAdminKey().logged());
    }

    @HapiTest
    final Stream<DynamicTest> updateMultipleFields() {
        long expirationTimestamp = Instant.now().getEpochSecond() + 7999990; // more than default.autorenew
        // .secs=7000000
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                newKeyNamed("adminKey"),
                newKeyNamed("adminKey2"),
                newKeyNamed("submitKey"),
                cryptoCreate("autoRenewAccount"),
                cryptoCreate("nextAutoRenewAccount"),
                createTopic("testTopic")
                        .topicMemo("initialmemo")
                        .adminKeyName("adminKey")
                        .autoRenewPeriod(validAutoRenewPeriod)
                        .autoRenewAccountId("autoRenewAccount"),
                atomicBatch(updateTopic("testTopic")
                                .topicMemo("updatedmemo")
                                .submitKey("submitKey")
                                .adminKey("adminKey2")
                                .expiry(expirationTimestamp)
                                .autoRenewPeriod(validAutoRenewPeriod + 5_000L)
                                .autoRenewAccountId("nextAutoRenewAccount")
                                .hasKnownStatus(SUCCESS)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getTopicInfo("testTopic")
                        .hasMemo("updatedmemo")
                        .hasSubmitKey("submitKey")
                        .hasAdminKey("adminKey2")
                        .hasExpiry(expirationTimestamp)
                        .hasAutoRenewPeriod(validAutoRenewPeriod + 5_000L)
                        .hasAutoRenewAccount("nextAutoRenewAccount")
                        .logged());
    }

    @HapiTest
    final Stream<DynamicTest> expirationTimestampNegative() {
        long now = Instant.now().getEpochSecond();
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                createTopic("testTopic").autoRenewPeriod(validAutoRenewPeriod),
                atomicBatch(updateTopic("testTopic")
                                .expiry(now - 1) // less than consensus time
                                .hasKnownStatusFrom(INVALID_EXPIRATION_TIME, EXPIRATION_REDUCTION_NOT_ALLOWED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> expirationTimestampReduction() {
        long now = Instant.now().getEpochSecond();
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                createTopic("testTopic").autoRenewPeriod(validAutoRenewPeriod),
                atomicBatch(updateTopic("testTopic")
                                .expiry(now + 1000) // 1000 < autoRenewPeriod
                                .hasKnownStatus(EXPIRATION_REDUCTION_NOT_ALLOWED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    /* If admin key is not set, only expiration timestamp updates are allowed */
    @HapiTest
    final Stream<DynamicTest> updateExpiryOnTopicWithNoAdminKey() {
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                createTopic("testTopic"),
                doSeveralWithStartupConfigNow("entities.maxLifetime", (value, now) -> {
                    final var maxLifetime = Long.parseLong(value);
                    final var newExpiry = now.getEpochSecond() + maxLifetime - 12_345L;
                    final var excessiveExpiry = now.getEpochSecond() + maxLifetime + 12_345L;
                    return specOps(
                            atomicBatch(updateTopic("testTopic")
                                            .expiry(excessiveExpiry)
                                            .hasKnownStatus(INVALID_EXPIRATION_TIME)
                                            .batchKey(BATCH_OPERATOR))
                                    .payingWith(BATCH_OPERATOR)
                                    .hasKnownStatus(INNER_TRANSACTION_FAILED),
                            atomicBatch(updateTopic("testTopic")
                                            .expiry(newExpiry)
                                            .batchKey(BATCH_OPERATOR))
                                    .payingWith(BATCH_OPERATOR),
                            getTopicInfo("testTopic").hasExpiry(newExpiry));
                }));
    }

    @HapiTest
    final Stream<DynamicTest> updateExpiryOnTopicWithAutoRenewAccountNoAdminKey() {
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                cryptoCreate("autoRenewAccount"),
                createTopic("testTopic").autoRenewAccountId("autoRenewAccount"),
                doSeveralWithStartupConfigNow("entities.maxLifetime", (value, now) -> {
                    final var maxLifetime = Long.parseLong(value);
                    final var newExpiry = now.getEpochSecond() + maxLifetime - 12_345L;
                    final var excessiveExpiry = now.getEpochSecond() + maxLifetime + 12_345L;
                    return specOps(
                            atomicBatch(updateTopic("testTopic")
                                            .expiry(excessiveExpiry)
                                            .hasKnownStatus(INVALID_EXPIRATION_TIME)
                                            .batchKey(BATCH_OPERATOR))
                                    .payingWith(BATCH_OPERATOR)
                                    .hasKnownStatus(INNER_TRANSACTION_FAILED),
                            atomicBatch(updateTopic("testTopic")
                                            .expiry(newExpiry)
                                            .batchKey(BATCH_OPERATOR))
                                    .payingWith(BATCH_OPERATOR),
                            getTopicInfo("testTopic").hasExpiry(newExpiry));
                }));
    }

    @HapiTest
    final Stream<DynamicTest> clearingAdminKeyWhenAutoRenewAccountPresent() {
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                newKeyNamed("adminKey"),
                cryptoCreate("autoRenewAccount"),
                createTopic("testTopic").adminKeyName("adminKey").autoRenewAccountId("autoRenewAccount"),
                atomicBatch(updateTopic("testTopic")
                                .adminKey(EMPTY_KEY)
                                .hasKnownStatus(AUTORENEW_ACCOUNT_NOT_ALLOWED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(updateTopic("testTopic")
                                .adminKey(EMPTY_KEY)
                                .autoRenewAccountId("0.0.0")
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getTopicInfo("testTopic").hasNoAdminKey());
    }

    @HapiTest
    final Stream<DynamicTest> updateSubmitKeyOnTopicWithNoAdminKeyFails() {
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                newKeyNamed("submitKey"),
                createTopic("testTopic"),
                atomicBatch(updateTopic("testTopic")
                                .submitKey("submitKey")
                                .hasKnownStatus(UNAUTHORIZED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    // TOPIC_RENEW_18
    @HapiTest
    final Stream<DynamicTest> updateTopicWithoutAutoRenewAccountWithNewInvalidAutoRenewAccountAdded() {
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                cryptoCreate("payer"),
                newKeyNamed("adminKey"),
                createTopic("testTopic").adminKeyName("adminKey").payingWith("payer"),
                atomicBatch(updateTopic("testTopic")
                                .payingWith("payer")
                                .adminKey("adminKey")
                                .autoRenewAccountId("1.2.3")
                                .signedBy("payer", "adminKey")
                                .hasKnownStatus(INVALID_AUTORENEW_ACCOUNT)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    // TOPIC_RENEW_19
    @HapiTest
    final Stream<DynamicTest> updateImmutableTopicWithAutoRenewAccountWithNewExpirationTime() {
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                cryptoCreate("payer"),
                cryptoCreate("autoRenewAccount"),
                createTopic("testTopic")
                        .payingWith("payer")
                        .autoRenewAccountId("autoRenewAccount")
                        .signedBy("payer", "autoRenewAccount"),
                doSeveralWithStartupConfigNow("entities.maxLifetime", (value, now) -> {
                    final var maxLifetime = Long.parseLong(value);
                    final var newExpiry = now.getEpochSecond() + maxLifetime - 12_345L;
                    return specOps(
                            atomicBatch(updateTopic("testTopic")
                                            .expiry(newExpiry)
                                            .batchKey(BATCH_OPERATOR))
                                    .payingWith(BATCH_OPERATOR),
                            getTopicInfo("testTopic").hasExpiry(newExpiry));
                }));
    }
}
