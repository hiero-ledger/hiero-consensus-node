// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.consensus;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTopicInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.submitMessageTo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.updateTopic;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doSeveralWithStartupConfigNow;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.specOps;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.submitModified;
import static com.hedera.services.bdd.spec.utilops.mod.ModificationUtils.withSuccessivelyVariedBodyIds;
import static com.hedera.services.bdd.suites.HapiSuite.EMPTY_KEY;
import static com.hedera.services.bdd.suites.HapiSuite.NONSENSE_KEY;
import static com.hedera.services.bdd.suites.HapiSuite.ZERO_BYTE_MEMO;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.AUTORENEW_ACCOUNT_NOT_ALLOWED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.AUTORENEW_DURATION_NOT_IN_RANGE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BAD_ENCODING;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.EXPIRATION_REDUCTION_NOT_ALLOWED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_AUTORENEW_ACCOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_EXPIRATION_TIME;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOPIC_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ZERO_BYTE_IN_STRING;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MEMO_TOO_LONG;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.UNAUTHORIZED;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.transactions.consensus.HapiTopicUpdate;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

public class TopicUpdateSuite {
    private static final long validAutoRenewPeriod = 7_000_000L;

    @HapiTest
    final Stream<DynamicTest> pureCheckFails() {
        return hapiTest(updateTopic("0.0.1").hasPrecheck(INVALID_TOPIC_ID));
    }

    @HapiTest
    final Stream<DynamicTest> updateToMissingTopicFails() {
        return hapiTest(updateTopic("1.2.3").hasKnownStatus(INVALID_TOPIC_ID));
    }

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> idVariantsTreatedAsExpected() {
        final var autoRenewAccount = "autoRenewAccount";
        return hapiTest(
                cryptoCreate(autoRenewAccount),
                cryptoCreate("replacementAccount"),
                newKeyNamed("adminKey"),
                createTopic("topic").adminKeyName("adminKey").autoRenewAccountId(autoRenewAccount),
                submitModified(withSuccessivelyVariedBodyIds(), () -> updateTopic("topic")
                        .autoRenewAccountId("replacementAccount")));
    }

    @HapiTest
    final Stream<DynamicTest> validateMultipleFields() {
        byte[] longBytes = new byte[1000];
        Arrays.fill(longBytes, (byte) 33);
        String longMemo = new String(longBytes, StandardCharsets.UTF_8);
        return hapiTest(
                newKeyNamed("adminKey"),
                createTopic("testTopic").adminKeyName("adminKey"),
                updateTopic("testTopic")
                        .adminKey(NONSENSE_KEY)
                        .hasPrecheckFrom(BAD_ENCODING, OK)
                        .hasKnownStatus(BAD_ENCODING),
                updateTopic("testTopic").submitKey(NONSENSE_KEY).hasKnownStatus(BAD_ENCODING),
                updateTopic("testTopic").topicMemo(longMemo).hasKnownStatus(MEMO_TOO_LONG),
                updateTopic("testTopic").topicMemo(ZERO_BYTE_MEMO).hasKnownStatus(INVALID_ZERO_BYTE_IN_STRING),
                updateTopic("testTopic").autoRenewPeriod(0).hasKnownStatus(AUTORENEW_DURATION_NOT_IN_RANGE),
                updateTopic("testTopic")
                        .autoRenewPeriod(Long.MAX_VALUE)
                        .hasKnownStatus(AUTORENEW_DURATION_NOT_IN_RANGE));
    }

    @HapiTest
    final Stream<DynamicTest> updatingAutoRenewAccountWithoutAdminFails() {
        return hapiTest(
                cryptoCreate("autoRenewAccount"),
                cryptoCreate("payer"),
                createTopic("testTopic").autoRenewAccountId("autoRenewAccount").payingWith("payer"),
                updateTopic("testTopic").autoRenewAccountId("payer").hasKnownStatus(UNAUTHORIZED));
    }

    @HapiTest
    final Stream<DynamicTest> updatingAutoRenewAccountWithAdminWorks() {
        return hapiTest(
                cryptoCreate("autoRenewAccount"),
                cryptoCreate("newAutoRenewAccount"),
                cryptoCreate("payer"),
                newKeyNamed("adminKey"),
                createTopic("testTopic")
                        .adminKeyName("adminKey")
                        .autoRenewAccountId("autoRenewAccount")
                        .payingWith("payer"),
                updateTopic("testTopic")
                        .payingWith("payer")
                        .autoRenewAccountId("newAutoRenewAccount")
                        .signedBy("payer", "adminKey", "newAutoRenewAccount"));
    }

    // TOPIC_RENEW_7
    @HapiTest
    final Stream<DynamicTest> updateTopicWithAdminKeyWithoutAutoRenewAccountWithNewAdminKey() {
        return hapiTest(
                cryptoCreate("payer"),
                newKeyNamed("adminKey"),
                newKeyNamed("newAdminKey"),
                createTopic("testTopic").adminKeyName("adminKey").payingWith("payer"),
                updateTopic("testTopic")
                        .payingWith("payer")
                        .adminKey("newAdminKey")
                        .signedBy("payer", "adminKey", "newAdminKey"),
                getTopicInfo("testTopic").logged().hasAdminKey("newAdminKey"));
    }

    // TOPIC_RENEW_8
    @HapiTest
    final Stream<DynamicTest> updateTopicWithoutAutoRenewAccountWithNewAutoRenewAccountAdded() {
        return hapiTest(
                cryptoCreate("autoRenewAccount"),
                cryptoCreate("payer"),
                newKeyNamed("adminKey"),
                createTopic("testTopic").adminKeyName("adminKey").payingWith("payer"),
                updateTopic("testTopic")
                        .payingWith("payer")
                        .autoRenewAccountId("autoRenewAccount")
                        .signedBy("payer", "adminKey", "autoRenewAccount"),
                getTopicInfo("testTopic").logged().hasAdminKey("adminKey").hasAutoRenewAccount("autoRenewAccount"));
    }

    @HapiTest
    final Stream<DynamicTest> topicUpdateSigReqsEnforcedAtConsensus() {
        long PAYER_BALANCE = 199_999_999_999L;
        Function<String[], HapiTopicUpdate> updateTopicSignedBy = (signers) -> updateTopic("testTopic")
                .payingWith("payer")
                .adminKey("newAdminKey")
                .autoRenewAccountId("newAutoRenewAccount")
                .signedBy(signers);

        return hapiTest(
                newKeyNamed("oldAdminKey"),
                cryptoCreate("oldAutoRenewAccount"),
                newKeyNamed("newAdminKey"),
                cryptoCreate("newAutoRenewAccount"),
                cryptoCreate("payer").balance(PAYER_BALANCE),
                createTopic("testTopic").adminKeyName("oldAdminKey").autoRenewAccountId("oldAutoRenewAccount"),
                updateTopicSignedBy.apply(new String[] {"payer", "oldAdminKey"}).hasKnownStatus(INVALID_SIGNATURE),
                updateTopicSignedBy
                        .apply(new String[] {"payer", "oldAdminKey", "newAdminKey"})
                        .hasKnownStatus(INVALID_SIGNATURE),
                updateTopicSignedBy
                        .apply(new String[] {"payer", "oldAdminKey", "newAutoRenewAccount"})
                        .hasKnownStatus(INVALID_SIGNATURE),
                updateTopicSignedBy
                        .apply(new String[] {"payer", "newAdminKey", "newAutoRenewAccount"})
                        .hasKnownStatus(INVALID_SIGNATURE),
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
                cryptoCreate("autoRenewAccount"),
                cryptoCreate("payer"),
                newKeyNamed("adminKey"),
                newKeyNamed("newAdminKey"),
                createTopic("testTopic").adminKeyName("adminKey").payingWith("payer"),
                updateTopic("testTopic")
                        .payingWith("payer")
                        .adminKey("newAdminKey")
                        .autoRenewAccountId("autoRenewAccount")
                        .signedBy("payer", "adminKey", "newAdminKey", "autoRenewAccount"),
                getTopicInfo("testTopic").logged().hasAdminKey("newAdminKey").hasAutoRenewAccount("autoRenewAccount"));
    }

    @HapiTest
    final Stream<DynamicTest> updateSubmitKeyToDiffKey() {
        return hapiTest(
                newKeyNamed("adminKey"),
                newKeyNamed("submitKey"),
                createTopic("testTopic").adminKeyName("adminKey"),
                updateTopic("testTopic").submitKey("submitKey"),
                getTopicInfo("testTopic")
                        .hasSubmitKey("submitKey")
                        .hasAdminKey("adminKey")
                        .logged());
    }

    @HapiTest
    final Stream<DynamicTest> canRemoveSubmitKeyDuringUpdate() {
        return hapiTest(
                newKeyNamed("adminKey"),
                newKeyNamed("submitKey"),
                createTopic("testTopic").adminKeyName("adminKey").submitKeyName("submitKey"),
                submitMessageTo("testTopic").message("message"),
                updateTopic("testTopic").submitKey(EMPTY_KEY),
                getTopicInfo("testTopic").hasNoSubmitKey().hasAdminKey("adminKey"),
                submitMessageTo("testTopic").message("message").logged());
    }

    @HapiTest
    final Stream<DynamicTest> updateAdminKeyToDiffKey() {
        return hapiTest(
                newKeyNamed("adminKey"),
                newKeyNamed("updateAdminKey"),
                createTopic("testTopic").adminKeyName("adminKey"),
                updateTopic("testTopic").adminKey("updateAdminKey"),
                getTopicInfo("testTopic").hasAdminKey("updateAdminKey").logged());
    }

    @HapiTest
    final Stream<DynamicTest> updateAdminKeyToEmpty() {
        return hapiTest(
                newKeyNamed("adminKey"),
                createTopic("testTopic").adminKeyName("adminKey"),
                /* if adminKey is empty list should clear adminKey */
                updateTopic("testTopic").adminKey(EMPTY_KEY),
                getTopicInfo("testTopic").hasNoAdminKey().logged());
    }

    @HapiTest
    final Stream<DynamicTest> updateMultipleFields() {
        long expirationTimestamp = Instant.now().getEpochSecond() + 7999990; // more than default.autorenew
        // .secs=7000000
        return hapiTest(
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
                updateTopic("testTopic")
                        .topicMemo("updatedmemo")
                        .submitKey("submitKey")
                        .adminKey("adminKey2")
                        .expiry(expirationTimestamp)
                        .autoRenewPeriod(validAutoRenewPeriod + 5_000L)
                        .autoRenewAccountId("nextAutoRenewAccount")
                        .hasKnownStatus(SUCCESS),
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
    final Stream<DynamicTest> expirationTimestampIsValidated() {
        long now = Instant.now().getEpochSecond();
        return hapiTest(
                createTopic("testTopic").autoRenewPeriod(validAutoRenewPeriod),
                updateTopic("testTopic")
                        .expiry(now - 1) // less than consensus time
                        .hasKnownStatusFrom(INVALID_EXPIRATION_TIME, EXPIRATION_REDUCTION_NOT_ALLOWED),
                updateTopic("testTopic")
                        .expiry(now + 1000) // 1000 < autoRenewPeriod
                        .hasKnownStatus(EXPIRATION_REDUCTION_NOT_ALLOWED));
    }

    /* If admin key is not set, only expiration timestamp updates are allowed */
    @HapiTest
    final Stream<DynamicTest> updateExpiryOnTopicWithNoAdminKey() {
        return hapiTest(
                createTopic("testTopic"), doSeveralWithStartupConfigNow("entities.maxLifetime", (value, now) -> {
                    final var maxLifetime = Long.parseLong(value);
                    final var newExpiry = now.getEpochSecond() + maxLifetime - 12_345L;
                    final var excessiveExpiry = now.getEpochSecond() + maxLifetime + 12_345L;
                    return specOps(
                            updateTopic("testTopic").expiry(excessiveExpiry).hasKnownStatus(INVALID_EXPIRATION_TIME),
                            updateTopic("testTopic").expiry(newExpiry),
                            getTopicInfo("testTopic").hasExpiry(newExpiry));
                }));
    }

    @HapiTest
    final Stream<DynamicTest> updateExpiryOnTopicWithAutoRenewAccountNoAdminKey() {
        return hapiTest(
                cryptoCreate("autoRenewAccount"),
                createTopic("testTopic").autoRenewAccountId("autoRenewAccount"),
                doSeveralWithStartupConfigNow("entities.maxLifetime", (value, now) -> {
                    final var maxLifetime = Long.parseLong(value);
                    final var newExpiry = now.getEpochSecond() + maxLifetime - 12_345L;
                    final var excessiveExpiry = now.getEpochSecond() + maxLifetime + 12_345L;
                    return specOps(
                            updateTopic("testTopic").expiry(excessiveExpiry).hasKnownStatus(INVALID_EXPIRATION_TIME),
                            updateTopic("testTopic").expiry(newExpiry),
                            getTopicInfo("testTopic").hasExpiry(newExpiry));
                }));
    }

    @HapiTest
    final Stream<DynamicTest> clearingAdminKeyWhenAutoRenewAccountPresent() {
        return hapiTest(
                newKeyNamed("adminKey"),
                cryptoCreate("autoRenewAccount"),
                createTopic("testTopic").adminKeyName("adminKey").autoRenewAccountId("autoRenewAccount"),
                updateTopic("testTopic").adminKey(EMPTY_KEY).hasKnownStatus(AUTORENEW_ACCOUNT_NOT_ALLOWED),
                updateTopic("testTopic").adminKey(EMPTY_KEY).autoRenewAccountId("0.0.0"),
                getTopicInfo("testTopic").hasNoAdminKey());
    }

    @HapiTest
    final Stream<DynamicTest> updateSubmitKeyOnTopicWithNoAdminKeyFails() {
        return hapiTest(
                newKeyNamed("submitKey"),
                createTopic("testTopic"),
                updateTopic("testTopic").submitKey("submitKey").hasKnownStatus(UNAUTHORIZED));
    }

    // TOPIC_RENEW_18
    @HapiTest
    final Stream<DynamicTest> updateTopicWithoutAutoRenewAccountWithNewInvalidAutoRenewAccountAdded() {
        return hapiTest(
                cryptoCreate("payer"),
                newKeyNamed("adminKey"),
                createTopic("testTopic").adminKeyName("adminKey").payingWith("payer"),
                updateTopic("testTopic")
                        .payingWith("payer")
                        .adminKey("adminKey")
                        .autoRenewAccountId("1.2.3")
                        .signedBy("payer", "adminKey")
                        .hasKnownStatus(INVALID_AUTORENEW_ACCOUNT));
    }

    // TOPIC_RENEW_19
    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> updateImmutableTopicWithAutoRenewAccountWithNewExpirationTime() {
        return hapiTest(
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
                            updateTopic("testTopic").expiry(newExpiry),
                            getTopicInfo("testTopic").hasExpiry(newExpiry));
                }));
    }
}
