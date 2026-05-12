// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.consensus;

import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTopicInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.submitMessageTo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.updateTopic;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedConsensusHbarFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedConsensusHtsFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeTests.expectedConsensusFixedHTSFee;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doSeveralWithStartupConfigNow;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doWithStartupConfig;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doWithStartupConfigNow;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.noOp;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.specOps;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.submitModified;
import static com.hedera.services.bdd.spec.utilops.mod.ModificationUtils.withSuccessivelyVariedBodyIds;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.EMPTY_KEY;
import static com.hedera.services.bdd.suites.HapiSuite.NONSENSE_KEY;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ZERO_BYTE_MEMO;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.AUTORENEW_ACCOUNT_NOT_ALLOWED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.AUTORENEW_DURATION_NOT_IN_RANGE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BAD_ENCODING;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.EXPIRATION_REDUCTION_NOT_ALLOWED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.FEE_SCHEDULE_KEY_CANNOT_BE_UPDATED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.FEE_SCHEDULE_KEY_NOT_SET;
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
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;

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

    @HapiTest
    final Stream<DynamicTest> feeScheduleUpdatesRequireFeeScheduleKeyExtantAndSigning() {
        final var newExpiryTime = new AtomicLong();
        final var minAutoRenewPeriod = new AtomicLong();
        return hapiTest(
                // Capture temporal context
                doWithStartupConfig("ledger.autoRenewPeriod.minDuration", literal -> {
                    minAutoRenewPeriod.set(Long.parseLong(literal));
                    return noOp();
                }),
                doWithStartupConfigNow("ledger.autoRenewPeriod.maxDuration", (literal, now) -> {
                    final long maxLifetime = Long.parseLong(literal);
                    newExpiryTime.set(now.getEpochSecond() + maxLifetime - 12_345L);
                    return noOp();
                }),
                // Create entities
                newKeyNamed("aKey"),
                newKeyNamed("fKey"),
                newKeyNamed("bfKey"),
                cryptoCreate("accountOne"),
                sourcing(() -> createTopic("immutableTopic").autoRenewPeriod(minAutoRenewPeriod.get())),
                sourcing(() ->
                        createTopic("adminOnlyTopic").adminKeyName("aKey").autoRenewPeriod(minAutoRenewPeriod.get())),
                sourcing(() -> createTopic("feeScheduleOnlyTopic")
                        .feeScheduleKeyName("fKey")
                        .autoRenewPeriod(minAutoRenewPeriod.get())),
                sourcing(() -> createTopic("adminAndFeeScheduleTopic")
                        .adminKeyName("aKey")
                        .feeScheduleKeyName("fKey")
                        .autoRenewPeriod(minAutoRenewPeriod.get())),
                // Degenerate cases - no HIP-991 fields can change for immutable topic
                sourcing(() -> updateTopic("immutableTopic")
                        .expiry(newExpiryTime.get())
                        .withConsensusCustomFee(fixedConsensusHbarFee(ONE_HBAR, "accountOne"))
                        .hasKnownStatus(FEE_SCHEDULE_KEY_NOT_SET)),
                sourcing(() -> updateTopic("immutableTopic")
                        .expiry(newExpiryTime.get())
                        .feeExemptKeys("aKey")
                        .hasKnownStatus(UNAUTHORIZED)),
                sourcing(() -> updateTopic("immutableTopic")
                        .expiry(newExpiryTime.get())
                        .feeScheduleKeyName("fKey")
                        .hasKnownStatus(UNAUTHORIZED)),
                // An admin-only topic can change just the exempt fee list; can't add a fee schedule key or change fees
                sourcing(() -> updateTopic("adminOnlyTopic")
                        .expiry(newExpiryTime.get())
                        .withConsensusCustomFee(fixedConsensusHbarFee(ONE_HBAR, "accountOne"))
                        .signedBy(DEFAULT_PAYER, "aKey")
                        .hasKnownStatus(FEE_SCHEDULE_KEY_NOT_SET)),
                sourcing(() -> updateTopic("adminOnlyTopic")
                        .expiry(newExpiryTime.get())
                        .feeExemptKeys("aKey")),
                sourcing(() -> updateTopic("adminOnlyTopic")
                        .expiry(newExpiryTime.get())
                        .feeScheduleKeyName("fKey")
                        .hasKnownStatus(FEE_SCHEDULE_KEY_CANNOT_BE_UPDATED)),
                // A fee schedule-only topic can change just fees
                sourcing(() -> updateTopic("feeScheduleOnlyTopic")
                        .expiry(newExpiryTime.get())
                        .withConsensusCustomFee(fixedConsensusHbarFee(ONE_HBAR, "accountOne"))
                        .signedBy(DEFAULT_PAYER, "fKey")),
                sourcing(() -> updateTopic("feeScheduleOnlyTopic")
                        .expiry(newExpiryTime.get())
                        .feeExemptKeys("aKey")
                        .signedBy(DEFAULT_PAYER, "fKey", "aKey")
                        .hasKnownStatus(UNAUTHORIZED)),
                sourcing(() -> updateTopic("feeScheduleOnlyTopic")
                        .expiry(newExpiryTime.get())
                        .feeScheduleKeyName("fKey")
                        .signedBy(DEFAULT_PAYER, "fKey", "aKey")
                        .hasKnownStatus(UNAUTHORIZED)),
                // An admin+fee schedule topic can change all three HIP-991 fields with the appropriate signatures
                // - Custom fee requires exactly fee schedule key, admin key is not enough
                sourcing(() -> updateTopic("adminAndFeeScheduleTopic")
                        .expiry(newExpiryTime.get())
                        .withConsensusCustomFee(fixedConsensusHbarFee(ONE_HBAR, "accountOne"))
                        .signedBy(DEFAULT_PAYER, "aKey")
                        .hasKnownStatus(INVALID_SIGNATURE)),
                sourcing(() -> updateTopic("adminAndFeeScheduleTopic")
                        .expiry(newExpiryTime.get())
                        .withConsensusCustomFee(fixedConsensusHbarFee(ONE_HBAR, "accountOne"))
                        .signedBy(DEFAULT_PAYER, "fKey")),
                // - Fee exempt key list requires exactly admin key, fee schedule key is not enough
                sourcing(() -> updateTopic("adminAndFeeScheduleTopic")
                        .expiry(newExpiryTime.get())
                        .feeExemptKeys("aKey")
                        .signedBy(DEFAULT_PAYER, "fKey")
                        .hasKnownStatus(INVALID_SIGNATURE)),
                sourcing(() -> updateTopic("adminAndFeeScheduleTopic")
                        .expiry(newExpiryTime.get())
                        .feeExemptKeys("aKey")
                        .signedBy(DEFAULT_PAYER, "aKey")),
                // - Fee schedule key requires exactly admin key, fee schedule key is not enough
                sourcing(() -> updateTopic("adminAndFeeScheduleTopic")
                        .expiry(newExpiryTime.get())
                        .feeScheduleKeyName("bfKey")
                        .signedBy(DEFAULT_PAYER, "fKey", "bfKey")
                        .hasKnownStatus(INVALID_SIGNATURE)),
                sourcing(() -> updateTopic("adminAndFeeScheduleTopic")
                        .expiry(newExpiryTime.get())
                        .feeScheduleKeyName("bfKey")
                        // New fee schedule key doesn't need to sign
                        .signedBy(DEFAULT_PAYER, "aKey")));
    }

    @HapiTest
    final Stream<DynamicTest> updateTopicBypassViaExpiry() {
        final long now = Instant.now().getEpochSecond();
        final long thirtyDaysInSeconds = 40L * 24 * 60 * 60;
        final long futureExpiry = now + thirtyDaysInSeconds;
        final var ALICE = "alice";
        final var TOPIC = "topic";
        final var ATTACKER = "attacker";
        final var TOKEN = "token";
        final var COLLECTOR = "collector";
        final var ADMIN_KEY = "adminKey";
        final var FEE_SCHEDULE_KEY = "feeScheduleKey";
        final var FEE_SCHEDULE_KEY2 = "feeScheduleKey2";
        return hapiTest(
                newKeyNamed(ADMIN_KEY),
                newKeyNamed(FEE_SCHEDULE_KEY),
                newKeyNamed(FEE_SCHEDULE_KEY2),
                cryptoCreate(ALICE).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(ATTACKER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(COLLECTOR),
                tokenCreate(TOKEN).treasury(COLLECTOR).initialSupply(100),
                // Create a topic and verify custom fee is correct
                createTopic(TOPIC)
                        .autoRenewPeriod(30L * 24 * 60 * 60)
                        .adminKeyName(ADMIN_KEY)
                        .feeScheduleKeyName(FEE_SCHEDULE_KEY)
                        .withConsensusCustomFee(fixedConsensusHtsFee(1, TOKEN, COLLECTOR)),
                getTopicInfo(TOPIC).logged(),

                // Update renew period without admin key, should succeed
                updateTopic(TOPIC)
                        .expiry(futureExpiry)
                        .signedBy(ALICE)
                        .payingWith(ALICE)
                        .hasKnownStatus(SUCCESS),

                // Update feeScheduleKey without that key signature, should fail
                updateTopic(TOPIC)
                        .feeScheduleKeyName(FEE_SCHEDULE_KEY2)
                        .signedBy(ALICE)
                        .payingWith(ALICE)
                        .hasKnownStatus(INVALID_SIGNATURE),

                // Update feeScheduleKey without admin key signature if changing expiry, should fail
                updateTopic(TOPIC)
                        .expiry(futureExpiry)
                        .feeScheduleKeyName(FEE_SCHEDULE_KEY2)
                        .signedBy(ATTACKER)
                        .payingWith(ATTACKER)
                        .hasKnownStatus(INVALID_SIGNATURE),
                getTopicInfo(TOPIC).hasFeeScheduleKey(FEE_SCHEDULE_KEY).hasAdminKey(ADMIN_KEY),

                // Add custom fee without feeSchedule key signature if expiry changes, should fail
                updateTopic(TOPIC)
                        .expiry(futureExpiry)
                        .withConsensusCustomFee(fixedConsensusHbarFee(1, ATTACKER))
                        .signedBy(ATTACKER)
                        .payingWith(ATTACKER)
                        .hasKnownStatus(INVALID_SIGNATURE),
                getTopicInfo(TOPIC)
                        .hasCustomFee(expectedConsensusFixedHTSFee(1, TOKEN, COLLECTOR))
                        .hasAdminKey(ADMIN_KEY)
                        .hasFeeScheduleKey(FEE_SCHEDULE_KEY),

                // Update exempt keys without admin key signature if expiry changes, should fail
                updateTopic(TOPIC)
                        .expiry(futureExpiry)
                        .feeExemptKeys(FEE_SCHEDULE_KEY)
                        .signedBy(ATTACKER)
                        .payingWith(ATTACKER)
                        .hasKnownStatus(INVALID_SIGNATURE),
                getTopicInfo(TOPIC)
                        .hasFeeExemptKeys(List.of())
                        .hasAdminKey(ADMIN_KEY)
                        .hasFeeScheduleKey(FEE_SCHEDULE_KEY));
    }
}
