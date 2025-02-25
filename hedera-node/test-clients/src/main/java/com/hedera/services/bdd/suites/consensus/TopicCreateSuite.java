// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.consensus;

import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTopicInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createDefaultContract;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.deleteTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.submitMessageTo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.updateTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.exposeTargetLedgerIdTo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sendModified;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.submitModified;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsd;
import static com.hedera.services.bdd.spec.utilops.mod.ModificationUtils.withSuccessivelyVariedBodyIds;
import static com.hedera.services.bdd.spec.utilops.mod.ModificationUtils.withSuccessivelyVariedQueryIds;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.NONSENSE_KEY;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.contract.hapi.ContractCallSuite.PAY_RECEIVABLE_CONTRACT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.AUTORENEW_DURATION_NOT_IN_RANGE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BAD_ENCODING;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_AUTORENEW_ACCOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_RENEWAL_PERIOD;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOPIC_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import com.hedera.services.bdd.spec.keys.KeyShape;
import org.junit.jupiter.api.DynamicTest;

public class TopicCreateSuite {
    public static final String TEST_TOPIC = "testTopic";
    public static final String TESTMEMO = "testmemo";

    @HapiTest
    final Stream<DynamicTest> adminKeyIsValidated() {
        return hapiTest(createTopic("testTopic")
                .adminKeyName(NONSENSE_KEY)
                .signedBy(GENESIS)
                .hasPrecheckFrom(OK, BAD_ENCODING)
                .hasKnownStatus(BAD_ENCODING));
    }

    @HapiTest
    final Stream<DynamicTest> submitKeyIsValidated() {
        return hapiTest(createTopic("testTopic")
                .submitKeyName(NONSENSE_KEY)
                .signedBy(GENESIS)
                .hasKnownStatus(BAD_ENCODING));
    }

    @HapiTest
    final Stream<DynamicTest> autoRenewAccountIsValidated() {
        return hapiTest(createTopic("testTopic")
                .autoRenewAccountId("1.2.3")
                .signedBy(GENESIS)
                .hasKnownStatus(INVALID_AUTORENEW_ACCOUNT));
    }

    //TOPIC_RENEW_1 - Public topic
    @HapiTest
    final Stream<DynamicTest> autoRenewAccountIdDoesntNeedAdminKey() {
        return hapiTest(
                cryptoCreate("payer"),
                cryptoCreate("autoRenewAccount"),
                // autoRenewAccount can be set on topic without adminKey
                createTopic("noAdminKeyExplicitAutoRenewAccount")
                        .payingWith("payer")
                        .autoRenewAccountId("autoRenewAccount")
                        .signedBy("payer", "autoRenewAccount"),
                getTopicInfo("noAdminKeyExplicitAutoRenewAccount")
                        .hasNoAdminKey()
                        .hasAutoRenewAccount("autoRenewAccount"));
    }

    //TOPIC_RENEW_1 - Private topic
    @HapiTest
    final Stream<DynamicTest> autoRenewAccountIdDoesntNeedAdminKeyPrivateTopic() {
        return hapiTest(
                newKeyNamed("submitKey"),
                cryptoCreate("payer"),
                cryptoCreate("autoRenewAccount"),
                // autoRenewAccount can be set on topic without adminKey
                createTopic("noAdminKeyExplicitAutoRenewAccount")
                        .payingWith("payer")
                        .autoRenewAccountId("autoRenewAccount")
                        .submitKeyName("submitKey")
                        .signedBy("payer", "autoRenewAccount"),
                getTopicInfo("noAdminKeyExplicitAutoRenewAccount")
                        .hasNoAdminKey()
                        .hasAutoRenewAccount("autoRenewAccount"));
    }

    //TOPIC_RENEW_2
    @HapiTest
    final Stream<DynamicTest> autoRenewAccountIdDoesntNeedAdminKeyRenewWith_ECDSA_Key() {
        return hapiTest(
                newKeyNamed("autoRenewAccountKey").shape(KeyShape.SECP256K1),
                cryptoCreate("payer"),
                cryptoCreate("autoRenewAccount").key("autoRenewAccountKey"),
                // autoRenewAccount can be set on topic without adminKey
                createTopic("noAdminKeyExplicitAutoRenewAccount")
                        .payingWith("payer")
                        .autoRenewAccountId("autoRenewAccount")
                        .signedBy("payer", "autoRenewAccount"),
                getTopicInfo("noAdminKeyExplicitAutoRenewAccount")
                        .hasNoAdminKey()
                        .hasAutoRenewAccount("autoRenewAccount"));
    }

    //TOPIC_RENEW_3
    @HapiTest
    final Stream<DynamicTest> autoRenewAccountIdDoesntNeedAdminKeyPayerWith_ECDSA_Key() {
        return hapiTest(
                newKeyNamed("payerKey").shape(KeyShape.SECP256K1),
                cryptoCreate("payer").key("payerKey"),
                cryptoCreate("autoRenewAccount"),
                // autoRenewAccount can be set on topic without adminKey
                createTopic("noAdminKeyExplicitAutoRenewAccount")
                        .payingWith("payer")
                        .autoRenewAccountId("autoRenewAccount")
                        .signedBy("payer", "autoRenewAccount"),
                getTopicInfo("noAdminKeyExplicitAutoRenewAccount")
                        .hasNoAdminKey()
                        .hasAutoRenewAccount("autoRenewAccount"));
    }

    //TOPIC_RENEW_4
    @HapiTest
    final Stream<DynamicTest> autoRenewAccountIdDoesntNeedAdminKeyAllWith_ECDSA_Key() {
        return hapiTest(
                newKeyNamed("payerKey").shape(KeyShape.SECP256K1),
                newKeyNamed("autoRenewAccountKey").shape(KeyShape.SECP256K1),
                cryptoCreate("payer").key("payerKey"),
                cryptoCreate("autoRenewAccount").key("autoRenewAccountKey"),
                // autoRenewAccount can be set on topic without adminKey
                createTopic("noAdminKeyExplicitAutoRenewAccount")
                        .payingWith("payer")
                        .autoRenewAccountId("autoRenewAccount")
                        .signedBy("payer", "autoRenewAccount"),
                getTopicInfo("noAdminKeyExplicitAutoRenewAccount")
                        .hasNoAdminKey()
                        .hasAutoRenewAccount("autoRenewAccount"));
    }

    //TOPIC_RENEW_5
    @HapiTest
    final Stream<DynamicTest> autoRenewAccountIdDoesntNeedAdminKeyAutoRenewIsAlsoPayer_ECDSA() {
        final var expectedPriceUsd = 0.0103;
        return hapiTest(
                newKeyNamed("autoRenewAccountKey").shape(KeyShape.SECP256K1),
                cryptoCreate("autoRenewAccount").key("autoRenewAccountKey").balance(ONE_HBAR),
                // autoRenewAccount can be set on topic without adminKey
                createTopic("noAdminKeyExplicitAutoRenewAccount")
                        .payingWith("autoRenewAccount")
                        .autoRenewAccountId("autoRenewAccount")
                        .signedBy("autoRenewAccount")
                        .via("createTopic"),
                getTopicInfo("noAdminKeyExplicitAutoRenewAccount")
                        .hasNoAdminKey()
                        .hasAutoRenewAccount("autoRenewAccount"),
                validateChargedUsd("createTopic", expectedPriceUsd, 1.0));
    }

    //TOPIC_RENEW_6 - Public topic
    @HapiTest
    final Stream<DynamicTest> autoRenewAccountIdDoesntNeedAdminKeyAutoRenewIsAlsoPayer() {
        final var expectedPriceUsd = 0.0103;
        return hapiTest(
                cryptoCreate("autoRenewAccount").balance(ONE_HBAR),
                // autoRenewAccount can be set on topic without adminKey
                createTopic("noAdminKeyExplicitAutoRenewAccount")
                        .payingWith("autoRenewAccount")
                        .autoRenewAccountId("autoRenewAccount")
                        .signedBy("autoRenewAccount")
                        .via("createTopic"),
                getTopicInfo("noAdminKeyExplicitAutoRenewAccount")
                        .hasNoAdminKey()
                        .hasAutoRenewAccount("autoRenewAccount"),
                validateChargedUsd("createTopic", expectedPriceUsd, 1.0));
    }

    //TOPIC_RENEW_6 - Private topic
    @HapiTest
    final Stream<DynamicTest> autoRenewAccountIdDoesntNeedAdminKeyAutoRenewIsAlsoPayerPrivateTopic() {
        final var expectedPriceUsd = 0.0105;
        return hapiTest(
                newKeyNamed("submitKey"),
                cryptoCreate("autoRenewAccount").balance(ONE_HBAR),
                // autoRenewAccount can be set on topic without adminKey
                createTopic("noAdminKeyExplicitAutoRenewAccount")
                        .payingWith("autoRenewAccount")
                        .autoRenewAccountId("autoRenewAccount")
                        .submitKeyName("submitKey")
                        .signedBy("autoRenewAccount")
                        .via("createTopic"),
                getTopicInfo("noAdminKeyExplicitAutoRenewAccount")
                        .hasNoAdminKey()
                        .hasAutoRenewAccount("autoRenewAccount"),
                validateChargedUsd("createTopic", expectedPriceUsd, 1.0));
    }

    @HapiTest
    final Stream<DynamicTest> idVariantsTreatedAsExpected() {
        final var autoRenewAccount = "autoRenewAccount";
        return hapiTest(
                cryptoCreate(autoRenewAccount),
                newKeyNamed("adminKey"),
                submitModified(
                        withSuccessivelyVariedBodyIds(),
                        () -> createTopic("topic").adminKeyName("adminKey").autoRenewAccountId(autoRenewAccount)));
    }

    @HapiTest
    final Stream<DynamicTest> autoRenewPeriodIsValidated() {
        final var tooShortAutoRenewPeriod = "tooShortAutoRenewPeriod";
        final var tooLongAutoRenewPeriod = "tooLongAutoRenewPeriod";
        return hapiTest(
                createTopic(tooShortAutoRenewPeriod)
                        .autoRenewPeriod(0L)
                        .hasKnownStatus(AUTORENEW_DURATION_NOT_IN_RANGE),
                createTopic(tooLongAutoRenewPeriod)
                        .autoRenewPeriod(Long.MAX_VALUE)
                        .hasKnownStatus(AUTORENEW_DURATION_NOT_IN_RANGE));
    }

    @HapiTest
    final Stream<DynamicTest> noAutoRenewPeriod() {
        return hapiTest(createTopic("testTopic")
                .clearAutoRenewPeriod()
                // No obvious reason to require INVALID_RENEWAL_PERIOD here
                .hasKnownStatusFrom(INVALID_RENEWAL_PERIOD, AUTORENEW_DURATION_NOT_IN_RANGE));
    }

    @HapiTest
    final Stream<DynamicTest> signingRequirementsEnforced() {
        long PAYER_BALANCE = 1_999_999_999L;
        final var contractWithAdminKey = "nonCryptoAccount";

        return hapiTest(
                newKeyNamed("adminKey"),
                newKeyNamed("contractAdminKey"),
                newKeyNamed("submitKey"),
                newKeyNamed("wrongKey"),
                cryptoCreate("payer").balance(PAYER_BALANCE),
                cryptoCreate("autoRenewAccount"),
                // This will have an admin key
                createDefaultContract(contractWithAdminKey).adminKey("contractAdminKey"),
                uploadInitCode(PAY_RECEIVABLE_CONTRACT),
                // And this won't
                contractCreate(PAY_RECEIVABLE_CONTRACT).omitAdminKey(),
                createTopic("testTopic")
                        .payingWith("payer")
                        .signedBy("wrongKey")
                        .hasPrecheck(INVALID_SIGNATURE),
                // But contracts without admin keys will get INVALID_SIGNATURE (can't sign!)
                createTopic("NotToBe")
                        .autoRenewAccountId(PAY_RECEIVABLE_CONTRACT)
                        .hasKnownStatusFrom(INVALID_SIGNATURE),
                // Auto-renew account should sign if set on a topic
                createTopic("testTopic")
                        .payingWith("payer")
                        .autoRenewAccountId("autoRenewAccount")
                        .signedBy("payer")
                        .hasKnownStatus(INVALID_SIGNATURE),
                createTopic("testTopic")
                        .payingWith("payer")
                        .autoRenewAccountId("autoRenewAccount")
                        .signedBy("autoRenewAccount")
                        .hasPrecheck(INVALID_SIGNATURE),
                createTopic("testTopic")
                        .payingWith("payer")
                        .adminKeyName("adminKey")
                        /* SigMap missing signature from adminKey. */
                        .signedBy("payer")
                        .hasKnownStatus(INVALID_SIGNATURE),
                createTopic("testTopic")
                        .payingWith("payer")
                        .adminKeyName("adminKey")
                        .autoRenewAccountId("autoRenewAccount")
                        /* SigMap missing signature from auto-renew account's key. */
                        .signedBy("payer", "adminKey")
                        .hasKnownStatus(INVALID_SIGNATURE),
                createTopic("testTopic")
                        .payingWith("payer")
                        .adminKeyName("adminKey")
                        .autoRenewAccountId("autoRenewAccount")
                        /* SigMap missing signature from adminKey. */
                        .signedBy("payer", "autoRenewAccount")
                        .hasKnownStatus(INVALID_SIGNATURE),
                // In hedera-app, we'll allow contracts with admin keys to be auto-renew accounts
                createTopic("withContractAutoRenew").adminKeyName("adminKey").autoRenewAccountId(contractWithAdminKey),
                createTopic("noAdminKeyNoAutoRenewAccount"),
                getTopicInfo("noAdminKeyNoAutoRenewAccount").hasNoAdminKey().logged(),
                createTopic("explicitAdminKeyNoAutoRenewAccount").adminKeyName("adminKey"),
                getTopicInfo("explicitAdminKeyNoAutoRenewAccount")
                        .hasAdminKey("adminKey")
                        .logged(),
                // Auto-renew account can be set along with admin key on topic
                createTopic("explicitAdminKeyExplicitAutoRenewAccount")
                        .adminKeyName("adminKey")
                        .autoRenewAccountId("autoRenewAccount"),
                getTopicInfo("explicitAdminKeyExplicitAutoRenewAccount")
                        .hasAdminKey("adminKey")
                        .hasAutoRenewAccount("autoRenewAccount")
                        .logged(),
                getTopicInfo("withContractAutoRenew")
                        .hasAdminKey("adminKey")
                        .hasAutoRenewAccount(contractWithAdminKey)
                        .logged());
    }

    @HapiTest
    final Stream<DynamicTest> allFieldsSetHappyCase() {
        return hapiTest(
                newKeyNamed("adminKey"),
                newKeyNamed("submitKey"),
                cryptoCreate("autoRenewAccount"),
                createTopic("testTopic")
                        .topicMemo("testmemo")
                        .adminKeyName("adminKey")
                        .submitKeyName("submitKey")
                        .autoRenewAccountId("autoRenewAccount"));
    }

    @HapiTest
    final Stream<DynamicTest> getInfoIdVariantsTreatedAsExpected() {
        return hapiTest(
                createTopic("topic"), sendModified(withSuccessivelyVariedQueryIds(), () -> getTopicInfo("topic")));
    }

    @HapiTest
    final Stream<DynamicTest> getInfoAllFieldsSetHappyCase() {
        // sequenceNumber should be 0 and runningHash should be 48 bytes all 0s.
        final AtomicReference<ByteString> targetLedgerId = new AtomicReference<>();
        return hapiTest(
                newKeyNamed("adminKey"),
                newKeyNamed("submitKey"),
                cryptoCreate("autoRenewAccount"),
                cryptoCreate("payer"),
                createTopic(TEST_TOPIC)
                        .topicMemo(TESTMEMO)
                        .adminKeyName("adminKey")
                        .submitKeyName("submitKey")
                        .autoRenewAccountId("autoRenewAccount")
                        .via("createTopic"),
                exposeTargetLedgerIdTo(targetLedgerId::set),
                sourcing(() -> getTopicInfo(TEST_TOPIC)
                        .hasEncodedLedgerId(targetLedgerId.get())
                        .hasMemo(TESTMEMO)
                        .hasAdminKey("adminKey")
                        .hasSubmitKey("submitKey")
                        .hasAutoRenewAccount("autoRenewAccount")
                        .hasSeqNo(0)
                        .hasRunningHash(new byte[48])),
                getTxnRecord("createTopic").logged(),
                submitMessageTo(TEST_TOPIC)
                        .blankMemo()
                        .payingWith("payer")
                        .message(new String("test".getBytes()))
                        .via("submitMessage"),
                getTxnRecord("submitMessage").logged(),
                sourcing(() -> getTopicInfo(TEST_TOPIC)
                        .hasEncodedLedgerId(targetLedgerId.get())
                        .hasMemo(TESTMEMO)
                        .hasAdminKey("adminKey")
                        .hasSubmitKey("submitKey")
                        .hasAutoRenewAccount("autoRenewAccount")
                        .hasSeqNo(1)
                        .logged()),
                updateTopic(TEST_TOPIC).topicMemo("Don't worry about the vase").via("updateTopic"),
                getTxnRecord("updateTopic").logged(),
                sourcing(() -> getTopicInfo(TEST_TOPIC)
                        .hasEncodedLedgerId(targetLedgerId.get())
                        .hasMemo("Don't worry about the vase")
                        .hasAdminKey("adminKey")
                        .hasSubmitKey("submitKey")
                        .hasAutoRenewAccount("autoRenewAccount")
                        .hasSeqNo(1)
                        .logged()),
                deleteTopic(TEST_TOPIC).via("deleteTopic"),
                getTxnRecord("deleteTopic").logged(),
                getTopicInfo(TEST_TOPIC).hasCostAnswerPrecheck(INVALID_TOPIC_ID).logged());
    }

    // Topic - AutoRenewal account negative test cases

    //TOPIC_RENEW_12
    @HapiTest
    final Stream<DynamicTest> topicCreateWithInvalidAdminKeyAndValidAutoRenewAccount() {
        return hapiTest(
                cryptoCreate("payer"),
                cryptoCreate("autoRenewAccount"),
                createTopic("testTopic")
                        .payingWith("payer")
                        .adminKeyName(NONSENSE_KEY)
                        .autoRenewAccountId("autoRenewAccount")
                        .signedBy("payer", "autoRenewAccount")
                .hasKnownStatus(BAD_ENCODING));
    }

    //TOPIC_RENEW_13
    @HapiTest
    final Stream<DynamicTest> topicCreateWithValidAdminKeyAndInvalidAutoRenewAccount() {
        return hapiTest(
                newKeyNamed("adminKey"),
                cryptoCreate("payer"),
                createTopic("testTopic")
                        .payingWith("payer")
                        .adminKeyName(NONSENSE_KEY)
                        .autoRenewAccountId("1.2.3")
                        .signedBy("payer", "adminKey")
                        .hasKnownStatus(INVALID_AUTORENEW_ACCOUNT));
    }

    //TOPIC_RENEW_14
    @HapiTest
    final Stream<DynamicTest> topicCreateWithInvalidAdminKeyAndInvalidAutoRenewAccount() {
        return hapiTest(
                cryptoCreate("payer"),
                createTopic("testTopic")
                        .payingWith("payer")
                        .autoRenewAccountId("1.2.3")
                        .adminKeyName(NONSENSE_KEY)
                        .signedBy("payer")
                        .hasKnownStatus(BAD_ENCODING));
    }
}
