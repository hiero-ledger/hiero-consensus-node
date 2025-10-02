// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.consensus;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.keys.ControlForKey.forKey;
import static com.hedera.services.bdd.spec.keys.KeyShape.SIMPLE;
import static com.hedera.services.bdd.spec.keys.KeyShape.listOf;
import static com.hedera.services.bdd.spec.keys.KeyShape.sigs;
import static com.hedera.services.bdd.spec.keys.KeyShape.threshOf;
import static com.hedera.services.bdd.spec.keys.SigControl.OFF;
import static com.hedera.services.bdd.spec.keys.SigControl.ON;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTopicInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createDefaultContract;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.deleteTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.submitMessageTo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.updateTopic;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsd;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BUSY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_PAYER_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.keys.SigControl;
import com.hedera.services.bdd.spec.transactions.consensus.HapiTopicUpdate;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@HapiTestLifecycle
public class AtomicBatchConsensusServiceTest {

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle lifecycle) {
        lifecycle.overrideInClass(Map.of("atomicBatch.isEnabled", "true", "atomicBatch.maxNumberOfTransactions", "50"));
    }

    // Submit Message to Topic with Submit Key tests

    @HapiTest
    public Stream<DynamicTest> topicSubmitMessageWithSubmitKeyValidSignatureSuccessInBatch() {
        final double BASE_FEE_BATCH_TRANSACTION = 0.001;

        // Define a threshold submit key that requires two simple keys signatures
        KeyShape submitKeyShape = threshOf(2, SIMPLE, SIMPLE, listOf(2));

        // Create a valid signature with both simple keys signing
        SigControl validSig = submitKeyShape.signedWith(sigs(ON, OFF, sigs(ON, ON)));

        final var submitMessage_innerTxn = submitMessageTo("testTopic")
                .sigControl(forKey("testTopicSubmit", validSig))
                .via("innerTxn")
                .payingWith("batchOperator")
                .hasRetryPrecheckFrom(BUSY)
                .batchKey("batchOperator");

        return hapiTest(
                newKeyNamed("submitKey").shape(submitKeyShape),
                createTopic("testTopic").submitKeyName("submitKey"),
                cryptoCreate("batchOperator").balance(ONE_HBAR),
                atomicBatch(submitMessage_innerTxn)
                        .payingWith("batchOperator")
                        .via("batchTxn")
                        .hasKnownStatus(SUCCESS),
                validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION));
    }

    @HapiTest
    public Stream<DynamicTest> topicSubmitMessageWithSubmitKeyInvalidSignatureInBatch() {
        final double BASE_FEE_BATCH_TRANSACTION = 0.001;

        // Define a threshold submit key that requires two simple keys signatures
        KeyShape submitKeyShape = threshOf(2, SIMPLE, SIMPLE, listOf(2));

        // Create invalid signature with one simple key only signing
        SigControl invalidSig = submitKeyShape.signedWith(sigs(ON, OFF, sigs(ON, OFF)));

        final var submitMessage_innerTxn = submitMessageTo("testTopic")
                .sigControl(forKey("testTopicSubmit", invalidSig))
                .via("innerTxn")
                .payingWith("batchOperator")
                .hasKnownStatus(INVALID_SIGNATURE)
                .batchKey("batchOperator");

        return hapiTest(
                newKeyNamed("submitKey").shape(submitKeyShape),
                createTopic("testTopic").submitKeyName("submitKey"),
                cryptoCreate("batchOperator").balance(ONE_HBAR),
                atomicBatch(submitMessage_innerTxn)
                        .payingWith("batchOperator")
                        .via("batchTxn")
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION));
    }

    @HapiTest
    public Stream<DynamicTest> topicSubmitMessageWithSubmitKeyInvalidAndValidSignatureInBatch() {
        final double BASE_FEE_BATCH_TRANSACTION = 0.001;

        // Define a threshold submit key that requires two simple keys signatures
        KeyShape submitKeyShape = threshOf(2, SIMPLE, SIMPLE, listOf(2));

        // Create valid signature and invalid one with one simple key only signing
        SigControl validSig = submitKeyShape.signedWith(sigs(ON, OFF, sigs(ON, ON)));
        SigControl invalidSig = submitKeyShape.signedWith(sigs(ON, OFF, sigs(ON, OFF)));

        final var submitMessage_innerTxn1 = submitMessageTo("testTopic")
                .sigControl(forKey("testTopicSubmit", invalidSig))
                .via("innerTxn1")
                .payingWith("batchOperator")
                .hasKnownStatus(INVALID_SIGNATURE)
                .batchKey("batchOperator");
        final var submitMessage_innerTxn2 = submitMessageTo("testTopic")
                .sigControl(forKey("testTopicSubmit", validSig))
                .via("innerTxn2")
                .payingWith("batchOperator")
                .batchKey("batchOperator");

        return hapiTest(
                newKeyNamed("submitKey").shape(submitKeyShape),
                createTopic("testTopic").submitKeyName("submitKey"),
                cryptoCreate("batchOperator").balance(ONE_HBAR),
                atomicBatch(submitMessage_innerTxn1, submitMessage_innerTxn2)
                        .payingWith("batchOperator")
                        .via("batchTxn")
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION));
    }

    @HapiTest
    public Stream<DynamicTest> topicSubmitMessageWithSubmitKeyValidAndInvalidSignatureInBatch() {
        final double BASE_FEE_BATCH_TRANSACTION = 0.001;

        // Define a threshold submit key that requires two simple keys signatures
        KeyShape submitKeyShape = threshOf(2, SIMPLE, SIMPLE, listOf(2));

        // Create valid signature and invalid one with one simple key only signing
        SigControl validSig = submitKeyShape.signedWith(sigs(ON, OFF, sigs(ON, ON)));
        SigControl invalidSig = submitKeyShape.signedWith(sigs(ON, OFF, sigs(ON, OFF)));

        final var submitMessage_innerTxn1 = submitMessageTo("testTopic")
                .sigControl(forKey("testTopicSubmit", validSig))
                .via("innerTxn1")
                .payingWith("batchOperator")
                .batchKey("batchOperator");
        final var submitMessage_innerTxn2 = submitMessageTo("testTopic")
                .sigControl(forKey("testTopicSubmit", invalidSig))
                .via("innerTxn2")
                .payingWith("batchOperator")
                .hasKnownStatus(INVALID_SIGNATURE)
                .batchKey("batchOperator");

        return hapiTest(
                newKeyNamed("submitKey").shape(submitKeyShape),
                createTopic("testTopic").submitKeyName("submitKey"),
                cryptoCreate("batchOperator").balance(ONE_HBAR),
                atomicBatch(submitMessage_innerTxn1, submitMessage_innerTxn2)
                        .payingWith("batchOperator")
                        .via("batchTxn")
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION));
    }

    @HapiTest
    public Stream<DynamicTest> topicSubmitMessageWithSubmitKeyAllInvalidSignaturesInBatch() {
        final double BASE_FEE_BATCH_TRANSACTION = 0.001;

        // Define a threshold submit key that requires two simple keys signatures
        KeyShape submitKeyShape = threshOf(2, SIMPLE, SIMPLE, listOf(2));

        // Create invalid signature with one simple key only signing
        SigControl invalidSig = submitKeyShape.signedWith(sigs(ON, OFF, sigs(ON, OFF)));

        final var submitMessage_innerTxn1 = submitMessageTo("testTopic")
                .sigControl(forKey("testTopicSubmit", invalidSig))
                .via("innerTxn1")
                .payingWith("batchOperator")
                .hasKnownStatus(INVALID_SIGNATURE)
                .batchKey("batchOperator");
        final var submitMessage_innerTxn2 = submitMessageTo("testTopic")
                .sigControl(forKey("testTopicSubmit", invalidSig))
                .via("innerTxn2")
                .payingWith("batchOperator")
                .batchKey("batchOperator");

        return hapiTest(
                newKeyNamed("submitKey").shape(submitKeyShape),
                createTopic("testTopic").submitKeyName("submitKey"),
                cryptoCreate("batchOperator").balance(ONE_HBAR),
                atomicBatch(submitMessage_innerTxn1, submitMessage_innerTxn2)
                        .payingWith("batchOperator")
                        .via("batchTxn")
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION));
    }

    @HapiTest
    public Stream<DynamicTest> topicSubmitMessageToPublicTopicSuccessInBatch() {
        final double BASE_FEE_BATCH_TRANSACTION = 0.001;

        final var submitMessage_innerTxn = submitMessageTo("testTopic")
                .via("innerTxn")
                .payingWith("batchOperator")
                .hasRetryPrecheckFrom(BUSY)
                .batchKey("batchOperator");

        return hapiTest(
                createTopic("testTopic"),
                cryptoCreate("batchOperator").balance(ONE_HBAR),
                atomicBatch(submitMessage_innerTxn)
                        .payingWith("batchOperator")
                        .via("batchTxn")
                        .hasKnownStatus(SUCCESS),
                validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION));
    }

    // Create Topic tests

    @HapiTest
    final Stream<DynamicTest> createTopicWithAutorenewAccountSignedSuccessfullyInBatch() {
        final double BASE_FEE_BATCH_TRANSACTION = 0.001;
        long PAYER_BALANCE = 1_999_999_999L;
        return hapiTest(
                cryptoCreate("batchOperator").balance(ONE_HBAR),
                cryptoCreate("autoRenewAccount"),
                cryptoCreate("payer").balance(PAYER_BALANCE),
                atomicBatch(createTopic("testTopic")
                                .autoRenewAccountId("autoRenewAccount")
                                .payingWith("payer")
                                .signedBy("payer", "autoRenewAccount")
                                .batchKey("batchOperator"))
                        .payingWith("batchOperator")
                        .via("batchTxn")
                        .hasKnownStatus(SUCCESS),
                validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),
                getTopicInfo("testTopic").hasAutoRenewAccount("autoRenewAccount"));
    }

    @HapiTest
    final Stream<DynamicTest> createTopicWithAdminKeyAndAutorenewAccountSignedSuccessfullyInBatch() {
        final double BASE_FEE_BATCH_TRANSACTION = 0.001;
        long PAYER_BALANCE = 1_999_999_999L;

        return hapiTest(
                cryptoCreate("batchOperator").balance(ONE_HBAR),
                newKeyNamed("adminKey"),
                cryptoCreate("autoRenewAccount"),
                cryptoCreate("payer").balance(PAYER_BALANCE),
                atomicBatch(createTopic("testTopic")
                                .adminKeyName("adminKey")
                                .autoRenewAccountId("autoRenewAccount")
                                .payingWith("payer")
                                .signedBy("payer", "adminKey", "autoRenewAccount")
                                .batchKey("batchOperator"))
                        .payingWith("batchOperator")
                        .via("batchTxn")
                        .hasKnownStatus(SUCCESS),
                validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),
                getTopicInfo("testTopic")
                        .hasAutoRenewAccount("autoRenewAccount")
                        .hasAdminKey("adminKey"));
    }

    @HapiTest
    final Stream<DynamicTest> createTopicAndSignWithWrongKeyFailsInBatch() {
        final double BASE_FEE_BATCH_TRANSACTION = 0.001;
        long PAYER_BALANCE = 1_999_999_999L;

        return hapiTest(
                cryptoCreate("batchOperator").balance(ONE_HBAR),
                newKeyNamed("adminKey"),
                newKeyNamed("wrongKey"),
                cryptoCreate("payer").balance(PAYER_BALANCE),
                atomicBatch(createTopic("testTopic")
                                .adminKeyName("adminKey")
                                .payingWith("payer")
                                .signedBy("payer", "wrongKey")
                                .batchKey("batchOperator")
                                .hasKnownStatus(INVALID_SIGNATURE))
                        .payingWith("batchOperator")
                        .via("batchTxn")
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION));
    }

    @HapiTest
    final Stream<DynamicTest> createTopicWithAutorenewAccountNotSignedByPayerFailsInBatch() {
        return hapiTest(
                cryptoCreate("batchOperator").balance(ONE_HBAR),
                cryptoCreate("autoRenewAccount"),
                cryptoCreate("payer"),
                atomicBatch(createTopic("testTopic")
                                .autoRenewAccountId("autoRenewAccount")
                                .payingWith("payer")
                                .signedBy("autoRenewAccount")
                                .batchKey("batchOperator")
                                .hasKnownStatus(INVALID_PAYER_SIGNATURE))
                        .payingWith("batchOperator")
                        .hasPrecheck(INVALID_SIGNATURE));
        // Batch will fail on ingest, so no record is generated
    }

    @HapiTest
    final Stream<DynamicTest> createTopicWithAutorenewAccountNotSignedByAutorenewFailsInBatch() {
        final double BASE_FEE_BATCH_TRANSACTION = 0.001;
        long PAYER_BALANCE = 1_999_999_999L;

        return hapiTest(
                cryptoCreate("batchOperator").balance(ONE_HBAR),
                cryptoCreate("autoRenewAccount"),
                cryptoCreate("payer").balance(PAYER_BALANCE),
                atomicBatch(createTopic("testTopic")
                                .autoRenewAccountId("autoRenewAccount")
                                .payingWith("payer")
                                .signedBy("payer")
                                .batchKey("batchOperator")
                                .hasKnownStatus(INVALID_SIGNATURE))
                        .payingWith("batchOperator")
                        .via("batchTxn")
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION));
    }

    @HapiTest
    final Stream<DynamicTest> createTopicWithAdminKeyAndAutorenewAccountNotSignedByAutorenewFailsInBatch() {
        final double BASE_FEE_BATCH_TRANSACTION = 0.001;
        long PAYER_BALANCE = 1_999_999_999L;

        return hapiTest(
                cryptoCreate("batchOperator").balance(ONE_HBAR),
                newKeyNamed("adminKey"),
                cryptoCreate("autoRenewAccount"),
                cryptoCreate("payer").balance(PAYER_BALANCE),
                atomicBatch(createTopic("testTopic")
                                .adminKeyName("adminKey")
                                .autoRenewAccountId("autoRenewAccount")
                                .payingWith("payer")
                                .signedBy("payer", "adminKey")
                                .batchKey("batchOperator")
                                .hasKnownStatus(INVALID_SIGNATURE))
                        .payingWith("batchOperator")
                        .via("batchTxn")
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION));
    }

    @HapiTest
    final Stream<DynamicTest> createTopicWithAdminKeyAndAutorenewAccountNotSignedByAdminKeyFailsInBatch() {
        final double BASE_FEE_BATCH_TRANSACTION = 0.001;
        long PAYER_BALANCE = 1_999_999_999L;

        return hapiTest(
                cryptoCreate("batchOperator").balance(ONE_HBAR),
                newKeyNamed("adminKey"),
                cryptoCreate("autoRenewAccount"),
                cryptoCreate("payer").balance(PAYER_BALANCE),
                atomicBatch(createTopic("testTopic")
                                .adminKeyName("adminKey")
                                .autoRenewAccountId("autoRenewAccount")
                                .payingWith("payer")
                                .signedBy("payer", "autoRenewAccount")
                                .batchKey("batchOperator")
                                .hasKnownStatus(INVALID_SIGNATURE))
                        .payingWith("batchOperator")
                        .via("batchTxn")
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION));
    }

    @HapiTest
    final Stream<DynamicTest> createTopicWithAdminKeyAndAutorenewAccountNotSignedByPayerFailsInBatch() {
        final double BASE_FEE_BATCH_TRANSACTION = 0.001;
        long PAYER_BALANCE = 1_999_999_999L;

        return hapiTest(
                cryptoCreate("batchOperator").balance(ONE_HBAR),
                newKeyNamed("adminKey"),
                cryptoCreate("autoRenewAccount"),
                cryptoCreate("payer").balance(PAYER_BALANCE),
                atomicBatch(createTopic("testTopic")
                                .adminKeyName("adminKey")
                                .autoRenewAccountId("autoRenewAccount")
                                .payingWith("payer")
                                .signedBy("adminKey", "autoRenewAccount")
                                .batchKey("batchOperator")
                                .hasKnownStatus(INVALID_PAYER_SIGNATURE))
                        .payingWith("batchOperator")
                        .via("batchTxn")
                        .hasPrecheck(INVALID_SIGNATURE));
        //                validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION));
    }

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> topicCreateWithContractWithAdminKeyForAutoRenewAccountSuccessInBatch() {
        final double BASE_FEE_BATCH_TRANSACTION = 0.001;
        long PAYER_BALANCE = 1_999_999_999L;
        final var contractWithAdminKey = "nonCryptoAccount";

        return hapiTest(
                cryptoCreate("batchOperator").balance(ONE_HBAR),
                cryptoCreate("payer").balance(PAYER_BALANCE),
                newKeyNamed("contractAdminKey"),
                createDefaultContract(contractWithAdminKey).adminKey("contractAdminKey"),
                atomicBatch(createTopic("testTopic")
                                .payingWith("payer")
                                .autoRenewAccountId(contractWithAdminKey)
                                .signedBy("payer", contractWithAdminKey)
                                .batchKey("batchOperator"))
                        .payingWith("batchOperator")
                        .via("batchTxn")
                        .hasKnownStatus(SUCCESS),
                validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),
                getTopicInfo("testTopic").hasAutoRenewAccount(contractWithAdminKey));
    }

    @HapiTest
    final Stream<DynamicTest> topicCreateWithContractWithAdminKeyForAutoRenewAccountNotSignedByPayerFailsInBatch() {
        final var contractWithAdminKey = "nonCryptoAccount";

        return hapiTest(
                cryptoCreate("batchOperator").balance(ONE_HBAR),
                cryptoCreate("payer"),
                newKeyNamed("contractAdminKey"),
                createDefaultContract(contractWithAdminKey).adminKey("contractAdminKey"),
                atomicBatch(createTopic("testTopic")
                                .payingWith("payer")
                                .autoRenewAccountId(contractWithAdminKey)
                                .signedBy(contractWithAdminKey)
                                .batchKey("batchOperator")
                                .hasKnownStatus(INVALID_PAYER_SIGNATURE))
                        .payingWith("batchOperator")
                        .hasPrecheck(INVALID_SIGNATURE));
        // Batch will fail on ingest, so no record is generated
    }

    @HapiTest
    final Stream<DynamicTest> topicCreateWithContractWithAdminKeyForAutoRenewAccountNotSignedByContractFailsInBatch() {
        final double BASE_FEE_BATCH_TRANSACTION = 0.001;
        long PAYER_BALANCE = 1_999_999_999L;
        final var contractWithAdminKey = "nonCryptoAccount";

        return hapiTest(
                cryptoCreate("batchOperator").balance(ONE_HBAR),
                cryptoCreate("payer").balance(PAYER_BALANCE),
                newKeyNamed("contractAdminKey"),
                createDefaultContract(contractWithAdminKey).adminKey("contractAdminKey"),
                atomicBatch(createTopic("testTopic")
                                .payingWith("payer")
                                .autoRenewAccountId(contractWithAdminKey)
                                .signedBy("payer")
                                .batchKey("batchOperator")
                                .hasKnownStatus(INVALID_SIGNATURE))
                        .payingWith("batchOperator")
                        .via("batchTxn")
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION));
    }

    @HapiTest
    final Stream<DynamicTest> topicCreateWithAdminKeyWithContractWithAdminKeyForAutoRenewAccountSuccessInBatch() {
        final double BASE_FEE_BATCH_TRANSACTION = 0.001;
        long PAYER_BALANCE = 1_999_999_999L;
        final var contractWithAdminKey = "nonCryptoAccount";

        return hapiTest(
                cryptoCreate("batchOperator").balance(ONE_HBAR),
                cryptoCreate("payer").balance(PAYER_BALANCE),
                newKeyNamed("contractAdminKey"),
                newKeyNamed("adminKey"),
                createDefaultContract(contractWithAdminKey).adminKey("contractAdminKey"),
                atomicBatch(createTopic("testTopic")
                                .payingWith("payer")
                                .adminKeyName("adminKey")
                                .autoRenewAccountId(contractWithAdminKey)
                                .signedBy("payer", "adminKey", contractWithAdminKey)
                                .batchKey("batchOperator"))
                        .payingWith("batchOperator")
                        .via("batchTxn")
                        .hasKnownStatus(SUCCESS),
                validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),
                getTopicInfo("testTopic").hasAdminKey("adminKey").hasAutoRenewAccount(contractWithAdminKey));
    }

    @HapiTest
    final Stream<DynamicTest>
            topicCreateWithAdminKeyWithContractWithAdminKeyForAutoRenewAccountNotSignByPayerFailsInBatch() {
        final var contractWithAdminKey = "nonCryptoAccount";
        return hapiTest(
                cryptoCreate("batchOperator").balance(ONE_HBAR),
                cryptoCreate("payer"),
                newKeyNamed("contractAdminKey"),
                newKeyNamed("adminKey"),
                createDefaultContract(contractWithAdminKey).adminKey("contractAdminKey"),
                atomicBatch(createTopic("testTopic")
                                .payingWith("payer")
                                .adminKeyName("adminKey")
                                .autoRenewAccountId(contractWithAdminKey)
                                .signedBy("adminKey", contractWithAdminKey)
                                .batchKey("batchOperator")
                                .hasKnownStatus(INVALID_PAYER_SIGNATURE))
                        .payingWith("batchOperator")
                        .hasPrecheck(INVALID_SIGNATURE));
        // Batch will fail on ingest, so no record is generated
    }

    @HapiTest
    final Stream<DynamicTest>
            topicCreateWithAdminKeyWithContractWithAdminKeyForAutoRenewAccountNotSignByAdminKeyFailsInBatch() {
        final double BASE_FEE_BATCH_TRANSACTION = 0.001;
        long PAYER_BALANCE = 1_999_999_999L;
        final var contractWithAdminKey = "nonCryptoAccount";

        return hapiTest(
                cryptoCreate("batchOperator").balance(ONE_HBAR),
                cryptoCreate("payer").balance(PAYER_BALANCE),
                newKeyNamed("contractAdminKey"),
                newKeyNamed("adminKey"),
                createDefaultContract(contractWithAdminKey).adminKey("contractAdminKey"),
                atomicBatch(createTopic("testTopic")
                                .payingWith("payer")
                                .adminKeyName("adminKey")
                                .autoRenewAccountId(contractWithAdminKey)
                                .signedBy("payer", contractWithAdminKey)
                                .batchKey("batchOperator")
                                .hasKnownStatus(INVALID_SIGNATURE))
                        .payingWith("batchOperator")
                        .via("batchTxn")
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION));
    }

    @HapiTest
    final Stream<DynamicTest>
            topicCreateWithAdminKeyWithContractWithAdminKeyForAutoRenewAccountNotSignByContractFailsInBatch() {
        final double BASE_FEE_BATCH_TRANSACTION = 0.001;
        long PAYER_BALANCE = 1_999_999_999L;
        final var contractWithAdminKey = "nonCryptoAccount";

        return hapiTest(
                cryptoCreate("batchOperator").balance(ONE_HBAR),
                cryptoCreate("payer").balance(PAYER_BALANCE),
                newKeyNamed("contractAdminKey"),
                newKeyNamed("adminKey"),
                createDefaultContract(contractWithAdminKey).adminKey("contractAdminKey"),
                atomicBatch(createTopic("testTopic")
                                .payingWith("payer")
                                .adminKeyName("adminKey")
                                .autoRenewAccountId(contractWithAdminKey)
                                .signedBy("payer", "adminKey")
                                .batchKey("batchOperator")
                                .hasKnownStatus(INVALID_SIGNATURE))
                        .payingWith("batchOperator")
                        .via("batchTxn")
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION));
    }

    @HapiTest
    final Stream<DynamicTest> topicCreateWithContractWithoutAdminKeyForAutoRenewAccountFailsInBatch() {
        final double BASE_FEE_BATCH_TRANSACTION = 0.001;
        final var contractWithoutAdminKey = "nonCryptoAccount";

        return hapiTest(
                cryptoCreate("batchOperator").balance(ONE_HBAR),
                cryptoCreate("payer"),
                createDefaultContract(contractWithoutAdminKey).omitAdminKey(),
                atomicBatch(createTopic("testTopic")
                                .payingWith("payer")
                                .autoRenewAccountId(contractWithoutAdminKey)
                                .signedBy("payer", contractWithoutAdminKey)
                                .batchKey("batchOperator")
                                .hasKnownStatus(INVALID_SIGNATURE))
                        .payingWith("batchOperator")
                        .via("batchTxn")
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION));
    }

    // Delete Topic tests

    @HapiTest
    final Stream<DynamicTest> topicDeleteSuccessfulInBatch() {
        final double BASE_FEE_BATCH_TRANSACTION = 0.001;
        long PAYER_BALANCE = 1_999_999_999L;

        return hapiTest(
                cryptoCreate("batchOperator").balance(ONE_HBAR),
                newKeyNamed("adminKey"),
                cryptoCreate("payer").balance(PAYER_BALANCE),
                createTopic("testTopic").adminKeyName("adminKey"),
                atomicBatch(deleteTopic("testTopic")
                                .payingWith("payer")
                                .signedBy("payer", "adminKey")
                                .batchKey("batchOperator"))
                        .payingWith("batchOperator")
                        .via("batchTxn")
                        .hasKnownStatus(SUCCESS),
                validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION));
    }

    @HapiTest
    final Stream<DynamicTest> topicDeleteWithWrongKeyFailedInBatch() {
        final double BASE_FEE_BATCH_TRANSACTION = 0.001;
        long PAYER_BALANCE = 1_999_999_999L;

        return hapiTest(
                cryptoCreate("batchOperator").balance(ONE_HBAR),
                newKeyNamed("adminKey"),
                newKeyNamed("wrongKey"),
                cryptoCreate("payer").balance(PAYER_BALANCE),
                createTopic("testTopic").adminKeyName("adminKey"),
                atomicBatch(deleteTopic("testTopic")
                                .payingWith("payer")
                                .signedBy("payer", "wrongKey")
                                .batchKey("batchOperator")
                                .hasKnownStatus(INVALID_SIGNATURE))
                        .payingWith("batchOperator")
                        .via("batchTxn")
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION));
    }

    @HapiTest
    final Stream<DynamicTest> topicDeleteNotSignedByPayerFailedInBatch() {
        return hapiTest(
                cryptoCreate("batchOperator").balance(ONE_HBAR),
                newKeyNamed("adminKey"),
                cryptoCreate("payer"),
                createTopic("testTopic").adminKeyName("adminKey"),
                atomicBatch(deleteTopic("testTopic")
                                .payingWith("payer")
                                .signedBy("adminKey")
                                .batchKey("batchOperator")
                                .hasKnownStatus(INVALID_PAYER_SIGNATURE))
                        .payingWith("batchOperator")
                        .hasPrecheck(INVALID_SIGNATURE));
        // Batch will fail on ingest, so no record is generated
    }

    @HapiTest
    final Stream<DynamicTest> topicDeleteNotSignedByAdminFailedInBatch() {
        final double BASE_FEE_BATCH_TRANSACTION = 0.001;
        long PAYER_BALANCE = 1_999_999_999L;

        return hapiTest(
                cryptoCreate("batchOperator").balance(ONE_HBAR),
                newKeyNamed("adminKey"),
                cryptoCreate("payer").balance(PAYER_BALANCE),
                createTopic("testTopic").adminKeyName("adminKey"),
                atomicBatch(deleteTopic("testTopic")
                                .payingWith("payer")
                                .signedBy("payer")
                                .batchKey("batchOperator")
                                .hasKnownStatus(INVALID_SIGNATURE))
                        .payingWith("batchOperator")
                        .via("batchTxn")
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION));
    }

    // Update Topic tests

    @HapiTest
    final Stream<DynamicTest> topicUpdateWithAutoRenewAccountSignedBySameAdminKeySuccessInBatch() {
        final double BASE_FEE_BATCH_TRANSACTION = 0.001;
        long PAYER_BALANCE = 199_999_999_999L;
        Function<String[], HapiTopicUpdate> updateTopicSignedBy = (signers) -> updateTopic("testTopic")
                .payingWith("payer")
                .adminKey("adminKey")
                .autoRenewAccountId("newAutoRenewAccount")
                .signedBy(signers);

        return hapiTest(
                cryptoCreate("batchOperator").balance(ONE_HBAR),
                newKeyNamed("adminKey"),
                cryptoCreate("oldAutoRenewAccount"),
                cryptoCreate("newAutoRenewAccount"),
                cryptoCreate("payer").balance(PAYER_BALANCE),
                createTopic("testTopic").adminKeyName("adminKey").autoRenewAccountId("oldAutoRenewAccount"),
                atomicBatch(updateTopicSignedBy
                                .apply(new String[] {"payer", "adminKey", "newAutoRenewAccount"})
                                .batchKey("batchOperator"))
                        .payingWith("batchOperator")
                        .via("batchTxn"),
                validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),
                getTopicInfo("testTopic").hasAdminKey("adminKey").hasAutoRenewAccount("newAutoRenewAccount"));
    }

    @HapiTest
    final Stream<DynamicTest> topicUpdateWithAutoRenewAccountNotSignedBySameAdminKeyFailsInBatch() {
        final double BASE_FEE_BATCH_TRANSACTION = 0.001;
        long PAYER_BALANCE = 199_999_999_999L;
        Function<String[], HapiTopicUpdate> updateTopicSignedBy = (signers) -> updateTopic("testTopic")
                .payingWith("payer")
                .adminKey("adminKey")
                .autoRenewAccountId("newAutoRenewAccount")
                .signedBy(signers);

        return hapiTest(
                cryptoCreate("batchOperator").balance(ONE_HBAR),
                newKeyNamed("adminKey"),
                cryptoCreate("oldAutoRenewAccount"),
                cryptoCreate("newAutoRenewAccount"),
                cryptoCreate("payer").balance(PAYER_BALANCE),
                createTopic("testTopic").adminKeyName("adminKey").autoRenewAccountId("oldAutoRenewAccount"),
                atomicBatch(updateTopicSignedBy
                                .apply(new String[] {"payer", "newAutoRenewAccount"})
                                .batchKey("batchOperator")
                                .hasKnownStatus(INVALID_SIGNATURE))
                        .payingWith("batchOperator")
                        .via("batchTxn")
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION));
    }

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> topicUpdateWithAutoRenewAccountAndNewAdminKeySignedByAllSuccessInBatch() {
        final double BASE_FEE_BATCH_TRANSACTION = 0.001;
        long PAYER_BALANCE = 199_999_999_999L;
        Function<String[], HapiTopicUpdate> updateTopicSignedBy = (signers) -> updateTopic("testTopic")
                .payingWith("payer")
                .adminKey("newAdminKey")
                .autoRenewAccountId("newAutoRenewAccount")
                .signedBy(signers);

        return hapiTest(
                cryptoCreate("batchOperator").balance(ONE_HBAR),
                newKeyNamed("oldAdminKey"),
                cryptoCreate("oldAutoRenewAccount"),
                newKeyNamed("newAdminKey"),
                cryptoCreate("newAutoRenewAccount"),
                cryptoCreate("payer").balance(PAYER_BALANCE),
                createTopic("testTopic").adminKeyName("oldAdminKey").autoRenewAccountId("oldAutoRenewAccount"),
                atomicBatch(updateTopicSignedBy
                                .apply(new String[] {"payer", "oldAdminKey", "newAdminKey", "newAutoRenewAccount"})
                                .batchKey("batchOperator"))
                        .payingWith("batchOperator")
                        .via("batchTxn"),
                validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),
                getTopicInfo("testTopic").hasAdminKey("newAdminKey").hasAutoRenewAccount("newAutoRenewAccount"));
    }

    @HapiTest
    final Stream<DynamicTest> topicUpdateWithAutoRenewAccountNotSignedByNewAdminAndNewAutoRenewFailsInBatch() {
        final double BASE_FEE_BATCH_TRANSACTION = 0.001;
        long PAYER_BALANCE = 199_999_999_999L;
        Function<String[], HapiTopicUpdate> updateTopicSignedBy = (signers) -> updateTopic("testTopic")
                .payingWith("payer")
                .adminKey("newAdminKey")
                .autoRenewAccountId("newAutoRenewAccount")
                .signedBy(signers);

        return hapiTest(
                cryptoCreate("batchOperator").balance(ONE_HBAR),
                newKeyNamed("oldAdminKey"),
                cryptoCreate("oldAutoRenewAccount"),
                newKeyNamed("newAdminKey"),
                cryptoCreate("newAutoRenewAccount"),
                cryptoCreate("payer").balance(PAYER_BALANCE),
                createTopic("testTopic").adminKeyName("oldAdminKey").autoRenewAccountId("oldAutoRenewAccount"),
                atomicBatch(updateTopicSignedBy
                                .apply(new String[] {"payer", "oldAdminKey"})
                                .batchKey("batchOperator")
                                .hasKnownStatus(INVALID_SIGNATURE))
                        .payingWith("batchOperator")
                        .via("batchTxn")
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION));
    }

    @HapiTest
    final Stream<DynamicTest> topicUpdateWithAutoRenewAccountNotSignedByNewAutoRenewFailsInBatch() {
        final double BASE_FEE_BATCH_TRANSACTION = 0.001;
        long PAYER_BALANCE = 199_999_999_999L;
        Function<String[], HapiTopicUpdate> updateTopicSignedBy = (signers) -> updateTopic("testTopic")
                .payingWith("payer")
                .adminKey("newAdminKey")
                .autoRenewAccountId("newAutoRenewAccount")
                .signedBy(signers);

        return hapiTest(
                cryptoCreate("batchOperator").balance(ONE_HBAR),
                newKeyNamed("oldAdminKey"),
                cryptoCreate("oldAutoRenewAccount"),
                newKeyNamed("newAdminKey"),
                cryptoCreate("newAutoRenewAccount"),
                cryptoCreate("payer").balance(PAYER_BALANCE),
                createTopic("testTopic").adminKeyName("oldAdminKey").autoRenewAccountId("oldAutoRenewAccount"),
                atomicBatch(updateTopicSignedBy
                                .apply(new String[] {"payer", "oldAdminKey", "newAdminKey"})
                                .batchKey("batchOperator")
                                .hasKnownStatus(INVALID_SIGNATURE))
                        .payingWith("batchOperator")
                        .via("batchTxn")
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION));
    }

    @HapiTest
    final Stream<DynamicTest> topicUpdateWithAutoRenewAccountNotSignedByNewAdminKeyFailsInBatch() {
        final double BASE_FEE_BATCH_TRANSACTION = 0.001;
        long PAYER_BALANCE = 199_999_999_999L;
        Function<String[], HapiTopicUpdate> updateTopicSignedBy = (signers) -> updateTopic("testTopic")
                .payingWith("payer")
                .adminKey("newAdminKey")
                .autoRenewAccountId("newAutoRenewAccount")
                .signedBy(signers);

        return hapiTest(
                cryptoCreate("batchOperator").balance(ONE_HBAR),
                newKeyNamed("oldAdminKey"),
                cryptoCreate("oldAutoRenewAccount"),
                newKeyNamed("newAdminKey"),
                cryptoCreate("newAutoRenewAccount"),
                cryptoCreate("payer").balance(PAYER_BALANCE),
                createTopic("testTopic").adminKeyName("oldAdminKey").autoRenewAccountId("oldAutoRenewAccount"),
                atomicBatch(updateTopicSignedBy
                                .apply(new String[] {"payer", "oldAdminKey", "newAutoRenewAccount"})
                                .batchKey("batchOperator")
                                .hasKnownStatus(INVALID_SIGNATURE))
                        .payingWith("batchOperator")
                        .via("batchTxn")
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION));
    }

    @HapiTest
    final Stream<DynamicTest> topicUpdateWithAutoRenewAccountNotSignedByOldAdminKeyFailsInBatch() {
        final double BASE_FEE_BATCH_TRANSACTION = 0.001;
        long PAYER_BALANCE = 199_999_999_999L;
        Function<String[], HapiTopicUpdate> updateTopicSignedBy = (signers) -> updateTopic("testTopic")
                .payingWith("payer")
                .adminKey("newAdminKey")
                .autoRenewAccountId("newAutoRenewAccount")
                .signedBy(signers);

        return hapiTest(
                cryptoCreate("batchOperator").balance(ONE_HBAR),
                newKeyNamed("oldAdminKey"),
                cryptoCreate("oldAutoRenewAccount"),
                newKeyNamed("newAdminKey"),
                cryptoCreate("newAutoRenewAccount"),
                cryptoCreate("payer").balance(PAYER_BALANCE),
                createTopic("testTopic").adminKeyName("oldAdminKey").autoRenewAccountId("oldAutoRenewAccount"),
                atomicBatch(updateTopicSignedBy
                                .apply(new String[] {"payer", "newAdminKey", "newAutoRenewAccount"})
                                .batchKey("batchOperator")
                                .hasKnownStatus(INVALID_SIGNATURE))
                        .payingWith("batchOperator")
                        .via("batchTxn")
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION));
    }
}
