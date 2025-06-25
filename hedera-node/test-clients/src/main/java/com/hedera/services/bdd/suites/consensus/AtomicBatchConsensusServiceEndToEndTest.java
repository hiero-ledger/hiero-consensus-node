// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.consensus;

import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTopicInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.deleteTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.submitMessageTo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.updateTopic;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsd;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOPIC_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.transactions.consensus.HapiTopicCreate;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;

@HapiTestLifecycle
public class AtomicBatchConsensusServiceEndToEndTest {

    private static final double BASE_FEE_BATCH_TRANSACTION = 0.001;
    private static final String BATCH_OPERATOR = "batchOperator";
    private static final String PAYER = "payer";
    private static final String AUTO_RENEW_ACCOUNT = "autoRenewAccount";
    private static final String AUTO_RENEW_ACCOUNT_UPDATE = "autoRenewAccountUpdate";
    private static final String TOPIC_ID = "testTopic";
    private static final String TOPIC_MESSAGE = "testMessage";
    private static final String TOPIC_MESSAGE_UPDATE = "topicMessageUpdate";
    private static final String TEST_MEMO = "Test topic for atomic batch consensus service end-to-end test";
    private static final String TEST_MEMO_UPDATE = "Updated topic for atomic batch consensus service end-to-end test";
    private static final String submitKey = "submitKey";
    private static final String newSubmitKey = "newSubmitKey";
    private static final String adminKey = "adminKey";
    private static final String newAdminKey = "newAdminKey";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle lifecycle) {
        lifecycle.overrideInClass(Map.of("atomicBatch.isEnabled", "true", "atomicBatch.maxNumberOfTransactions", "50"));
    }

    @HapiTest
    public Stream<DynamicTest> submitMessagesToMutableTopicWithSubmitKeyAndUpdateTopicSuccessInBatch() {

        // submit message to topic inner transactions
        final var submitMessageBeforeUpdate = submitMessageTo(TOPIC_ID)
                .message(TOPIC_MESSAGE)
                .signedBy(PAYER, submitKey)
                .payingWith(PAYER)
                .via("innerTxnBeforeUpdate")
                .batchKey(BATCH_OPERATOR);

        final var submitMessageAfterUpdate = submitMessageTo(TOPIC_ID)
                .message(TOPIC_MESSAGE_UPDATE)
                .signedBy(PAYER, submitKey)
                .payingWith(PAYER)
                .via("innerTxnAfterUpdate")
                .batchKey(BATCH_OPERATOR);

        // update topic inner transaction
        final var updateTopic = updateTopic(TOPIC_ID)
                .topicMemo(TEST_MEMO_UPDATE)
                .payingWith(PAYER)
                .signedBy(PAYER, adminKey)
                .via("updateTopicTxn")
                .batchKey(BATCH_OPERATOR);

        return hapiTest(flattened(
                createAccountsAndKeys(),
                createMutableTopicWithSubmitKey(adminKey, submitKey, TOPIC_ID),
                atomicBatch(submitMessageBeforeUpdate, updateTopic, submitMessageAfterUpdate)
                        .payingWith(BATCH_OPERATOR)
                        .via("batchTxn")
                        .hasKnownStatus(SUCCESS),
                validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                // confirm topic memo is updated
                getTopicInfo(TOPIC_ID)
                        .hasMemo(TEST_MEMO_UPDATE)
                        .hasAdminKey(adminKey)
                        .hasSubmitKey(submitKey)
                        .hasNoCustomFee()));
    }

    @HapiTest
    public Stream<DynamicTest> submitMessageToMutableTopicWithSubmitKeyAndDeleteTheTopicSuccessInBatch() {

        // submit message to topic inner transaction
        final var submitMessageBeforeDelete = submitMessageTo(TOPIC_ID)
                .message(TOPIC_MESSAGE)
                .signedBy(PAYER, submitKey)
                .payingWith(PAYER)
                .via("innerTxnBeforeDelete")
                .batchKey(BATCH_OPERATOR);

        // delete topic inner transaction
        final var deleteTopicAfterSubmitMessage = deleteTopic(TOPIC_ID)
                .payingWith(PAYER)
                .signedBy(PAYER, adminKey)
                .via("deleteTopicTxn")
                .batchKey(BATCH_OPERATOR);

        return hapiTest(flattened(
                createAccountsAndKeys(),
                createMutableTopicWithSubmitKey(adminKey, submitKey, TOPIC_ID),
                atomicBatch(submitMessageBeforeDelete, deleteTopicAfterSubmitMessage)
                        .payingWith(BATCH_OPERATOR)
                        .via("batchTxn")
                        .hasKnownStatus(SUCCESS),
                validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                // confirm topic is deleted
                getTopicInfo(TOPIC_ID).hasCostAnswerPrecheck(INVALID_TOPIC_ID).logged()));
    }

    @HapiTest
    public Stream<DynamicTest> submitMultipleMessagesToMutableTopicWithSubmitKeySuccessInBatch() {

        // submit message to topic inner transactions
        final var submitMessageFirstTransaction = submitMessageTo(TOPIC_ID)
                .message(TOPIC_MESSAGE)
                .signedBy(PAYER, submitKey)
                .payingWith(PAYER)
                .via("innerTxnFirst")
                .batchKey(BATCH_OPERATOR);

        final var submitMessageSecondTransaction = submitMessageTo(TOPIC_ID)
                .message(TOPIC_MESSAGE)
                .signedBy(PAYER, submitKey)
                .payingWith(PAYER)
                .via("innerTxnSecond")
                .batchKey(BATCH_OPERATOR);

        final var submitMessageThirdTransaction = submitMessageTo(TOPIC_ID)
                .message(TOPIC_MESSAGE)
                .signedBy(PAYER, submitKey)
                .payingWith(PAYER)
                .via("innerTxnThird")
                .batchKey(BATCH_OPERATOR);

        return hapiTest(flattened(
                createAccountsAndKeys(),
                createMutableTopicWithSubmitKey(adminKey, submitKey, TOPIC_ID),
                atomicBatch(
                                submitMessageFirstTransaction,
                                submitMessageSecondTransaction,
                                submitMessageThirdTransaction)
                        .payingWith(BATCH_OPERATOR)
                        .via("batchTxn")
                        .hasKnownStatus(SUCCESS),
                validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION)));
    }

    @HapiTest
    public Stream<DynamicTest> submitMultipleMessagesToImmutableTopicWithSubmitKeySuccessInBatch() {

        // submit message to topic inner transactions
        final var submitMessageFirstTransaction = submitMessageTo(TOPIC_ID)
                .message(TOPIC_MESSAGE)
                .signedBy(PAYER, submitKey)
                .payingWith(PAYER)
                .via("innerTxnFirst")
                .batchKey(BATCH_OPERATOR);

        final var submitMessageSecondTransaction = submitMessageTo(TOPIC_ID)
                .message(TOPIC_MESSAGE)
                .signedBy(PAYER, submitKey)
                .payingWith(PAYER)
                .via("innerTxnSecond")
                .batchKey(BATCH_OPERATOR);

        final var submitMessageThirdTransaction = submitMessageTo(TOPIC_ID)
                .message(TOPIC_MESSAGE)
                .signedBy(PAYER, submitKey)
                .payingWith(PAYER)
                .via("innerTxnThird")
                .batchKey(BATCH_OPERATOR);

        return hapiTest(flattened(
                createAccountsAndKeys(),
                createImmutableTopicWithSubmitKey(submitKey, TOPIC_ID),
                atomicBatch(
                                submitMessageFirstTransaction,
                                submitMessageSecondTransaction,
                                submitMessageThirdTransaction)
                        .payingWith(BATCH_OPERATOR)
                        .via("batchTxn")
                        .hasKnownStatus(SUCCESS),
                validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION)));
    }

    @HapiTest
    public Stream<DynamicTest> updateMutableTopicWithSubmitKeySubmitMessageAndDeleteTheTopicSuccessInBatch() {

        // update topic inner transaction
        final var updateTopic = updateTopic(TOPIC_ID)
                .topicMemo(TEST_MEMO_UPDATE)
                .payingWith(PAYER)
                .signedBy(PAYER, adminKey)
                .via("updateTopicTxn")
                .batchKey(BATCH_OPERATOR);

        // submit message to topic inner transaction
        final var submitMessageBeforeDelete = submitMessageTo(TOPIC_ID)
                .message(TOPIC_MESSAGE)
                .signedBy(PAYER, submitKey)
                .payingWith(PAYER)
                .via("innerTxnBeforeDelete")
                .batchKey(BATCH_OPERATOR);

        // delete topic inner transaction
        final var deleteTopicAfterSubmitMessage = deleteTopic(TOPIC_ID)
                .payingWith(PAYER)
                .signedBy(PAYER, adminKey)
                .via("deleteTopicTxn")
                .batchKey(BATCH_OPERATOR);

        return hapiTest(flattened(
                createAccountsAndKeys(),
                createMutableTopicWithSubmitKey(adminKey, submitKey, TOPIC_ID),
                atomicBatch(updateTopic, submitMessageBeforeDelete, deleteTopicAfterSubmitMessage)
                        .payingWith(BATCH_OPERATOR)
                        .via("batchTxn")
                        .hasKnownStatus(SUCCESS),
                validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                // confirm topic is deleted
                getTopicInfo(TOPIC_ID).hasCostAnswerPrecheck(INVALID_TOPIC_ID).logged()));
    }

    @HapiTest
    public Stream<DynamicTest> submitMessagesToDeletedTopicFailsInBatch() {

        // submit message to topic inner transaction
        final var submitMessageBeforeDelete = submitMessageTo(TOPIC_ID)
                .message(TOPIC_MESSAGE)
                .signedBy(PAYER, submitKey)
                .payingWith(PAYER)
                .via("innerTxnBeforeDelete")
                .batchKey(BATCH_OPERATOR);

        final var submitMessageAfterDelete = submitMessageTo(TOPIC_ID)
                .message(TOPIC_MESSAGE)
                .signedBy(PAYER, submitKey)
                .payingWith(PAYER)
                .via("innerTxnAfterDelete")
                .batchKey(BATCH_OPERATOR);

        // delete topic inner transaction
        final var deleteTopicTransaction = deleteTopic(TOPIC_ID)
                .payingWith(PAYER)
                .signedBy(PAYER, adminKey)
                .via("deleteTopicTxn")
                .batchKey(BATCH_OPERATOR);

        return hapiTest(flattened(
                createAccountsAndKeys(),
                createMutableTopicWithSubmitKey(adminKey, submitKey, TOPIC_ID),
                atomicBatch(submitMessageBeforeDelete, deleteTopicTransaction, submitMessageAfterDelete)
                        .payingWith(BATCH_OPERATOR)
                        .via("batchTxn")
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                // confirm topic is not deleted
                getTxnRecord("innerTxnAfterDelete").logged(),
                getTopicInfo(TOPIC_ID)
                        .hasMemo(TEST_MEMO)
                        .hasAdminKey(adminKey)
                        .hasSubmitKey(submitKey)
                        .hasNoCustomFee()));
    }

    @HapiTest
    public Stream<DynamicTest> updateTopicDeleteTopicAndSubmitMessagesToTheDeletedTopicFailsInBatch() {

        // update topic inner transaction
        final var updateTopic = updateTopic(TOPIC_ID)
                .topicMemo(TEST_MEMO_UPDATE)
                .payingWith(PAYER)
                .signedBy(PAYER, adminKey)
                .via("updateTopicTxn")
                .batchKey(BATCH_OPERATOR);

        // delete topic inner transaction
        final var deleteTopicTransaction = deleteTopic(TOPIC_ID)
                .payingWith(PAYER)
                .signedBy(PAYER, adminKey)
                .via("deleteTopicTxn")
                .batchKey(BATCH_OPERATOR);

        // submit message to topic inner transaction
        final var submitMessageAfterDelete = submitMessageTo(TOPIC_ID)
                .message(TOPIC_MESSAGE)
                .signedBy(PAYER, submitKey)
                .payingWith(PAYER)
                .via("innerTxnAfterDelete")
                .batchKey(BATCH_OPERATOR);

        return hapiTest(flattened(
                createAccountsAndKeys(),
                createMutableTopicWithSubmitKey(adminKey, submitKey, TOPIC_ID),
                createMutableTopicWithSubmitKey(adminKey, submitKey, TOPIC_ID),
                atomicBatch(updateTopic, deleteTopicTransaction, submitMessageAfterDelete)
                        .payingWith(BATCH_OPERATOR)
                        .via("batchTxn")
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                // confirm topic is not deleted
                getTopicInfo(TOPIC_ID)
                        .hasMemo(TEST_MEMO)
                        .hasAdminKey(adminKey)
                        .hasSubmitKey(submitKey)
                        .hasNoCustomFee()));
    }

    @HapiTest
    public Stream<DynamicTest> submitMessageDeleteTopicAndUpdateTheDeletedTopicFailsInBatch() {

        // submit message to topic inner transaction
        final var submitMessageBeforeDelete = submitMessageTo(TOPIC_ID)
                .message(TOPIC_MESSAGE)
                .signedBy(PAYER, submitKey)
                .payingWith(PAYER)
                .via("innerTxnBeforeDelete")
                .batchKey(BATCH_OPERATOR);

        // delete topic inner transaction
        final var deleteTopicTransaction = deleteTopic(TOPIC_ID)
                .payingWith(PAYER)
                .signedBy(PAYER, adminKey)
                .via("deleteTopicTxn")
                .batchKey(BATCH_OPERATOR);

        // update topic inner transaction
        final var updateTopic = updateTopic(TOPIC_ID)
                .topicMemo(TEST_MEMO_UPDATE)
                .payingWith(PAYER)
                .signedBy(PAYER, adminKey)
                .via("updateTopicTxn")
                .batchKey(BATCH_OPERATOR);

        return hapiTest(flattened(
                createAccountsAndKeys(),
                createMutableTopicWithSubmitKey(adminKey, submitKey, TOPIC_ID),
                atomicBatch(submitMessageBeforeDelete, deleteTopicTransaction, updateTopic)
                        .payingWith(BATCH_OPERATOR)
                        .via("batchTxn")
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                // confirm topic is not deleted
                getTopicInfo(TOPIC_ID)
                        .hasMemo(TEST_MEMO)
                        .hasAdminKey(adminKey)
                        .hasSubmitKey(submitKey)
                        .hasNoCustomFee()));
    }

    @HapiTest
    public Stream<DynamicTest> updateMutableTopicWithNewAutoRenewAccountAndPeriodAndSubmitMessagesSuccessInBatch() {

        // submit message to topic inner transactions
        final var submitMessageBeforeUpdate = submitMessageTo(TOPIC_ID)
                .message(TOPIC_MESSAGE)
                .signedBy(PAYER, submitKey)
                .payingWith(PAYER)
                .via("innerTxnBeforeUpdate")
                .batchKey(BATCH_OPERATOR);

        final var submitMessageAfterUpdate = submitMessageTo(TOPIC_ID)
                .message(TOPIC_MESSAGE_UPDATE)
                .signedBy(PAYER, submitKey)
                .payingWith(PAYER)
                .via("innerTxnAfterUpdate")
                .batchKey(BATCH_OPERATOR);

        // update topic inner transaction
        final var updateTopic = updateTopic(TOPIC_ID)
                .autoRenewAccountId(AUTO_RENEW_ACCOUNT_UPDATE)
                .autoRenewPeriod(8_000_000L)
                .topicMemo(TEST_MEMO_UPDATE)
                .payingWith(PAYER)
                .signedBy(PAYER, adminKey, AUTO_RENEW_ACCOUNT_UPDATE)
                .via("updateTopicTxn")
                .batchKey(BATCH_OPERATOR);

        return hapiTest(flattened(
                createAccountsAndKeys(),
                createMutableTopicWithSubmitKeyAndAutoRenew(adminKey, submitKey, AUTO_RENEW_ACCOUNT, TOPIC_ID),
                atomicBatch(submitMessageBeforeUpdate, updateTopic, submitMessageAfterUpdate)
                        .payingWith(BATCH_OPERATOR)
                        .via("batchTxn")
                        .hasKnownStatus(SUCCESS),
                validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                // confirm topic is updated
                getTopicInfo(TOPIC_ID)
                        .hasMemo(TEST_MEMO_UPDATE)
                        .hasAdminKey(adminKey)
                        .hasSubmitKey(submitKey)
                        .hasAutoRenewAccount(AUTO_RENEW_ACCOUNT_UPDATE)
                        .hasAutoRenewPeriod(8_000_000L)
                        .hasNoCustomFee()));
    }

    @HapiTest
    public Stream<DynamicTest>
            updateMutableTopicWithoutAutoRenewWithAutoRenewAccountAndPeriodAndSubmitMessagesSuccessInBatch() {

        // submit message to topic inner transactions
        final var submitMessageBeforeUpdate = submitMessageTo(TOPIC_ID)
                .message(TOPIC_MESSAGE)
                .signedBy(PAYER, submitKey)
                .payingWith(PAYER)
                .via("innerTxnBeforeUpdate")
                .batchKey(BATCH_OPERATOR);

        final var submitMessageAfterUpdate = submitMessageTo(TOPIC_ID)
                .message(TOPIC_MESSAGE_UPDATE)
                .signedBy(PAYER, submitKey)
                .payingWith(PAYER)
                .via("innerTxnAfterUpdate")
                .batchKey(BATCH_OPERATOR);

        // update topic inner transaction
        final var updateTopic = updateTopic(TOPIC_ID)
                .autoRenewAccountId(AUTO_RENEW_ACCOUNT)
                .autoRenewPeriod(7_000_000L)
                .topicMemo(TEST_MEMO_UPDATE)
                .payingWith(PAYER)
                .signedBy(PAYER, adminKey, AUTO_RENEW_ACCOUNT)
                .via("updateTopicTxn")
                .batchKey(BATCH_OPERATOR);

        return hapiTest(flattened(
                createAccountsAndKeys(),
                createMutableTopicWithSubmitKey(adminKey, submitKey, TOPIC_ID),
                atomicBatch(submitMessageBeforeUpdate, updateTopic, submitMessageAfterUpdate)
                        .payingWith(BATCH_OPERATOR)
                        .via("batchTxn")
                        .hasKnownStatus(SUCCESS),
                validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                // confirm topic is updated
                getTopicInfo(TOPIC_ID)
                        .hasMemo(TEST_MEMO_UPDATE)
                        .hasAdminKey(adminKey)
                        .hasSubmitKey(submitKey)
                        .hasAutoRenewAccount(AUTO_RENEW_ACCOUNT)
                        .hasAutoRenewPeriod(7_000_000L)
                        .hasNoCustomFee()));
    }

    @HapiTest
    public Stream<DynamicTest>
            updateMutableTopicWithoutAutoRenewWithAutoRenewAndSubmitMessagesNotSignedByAutoRenewFailsInBatch() {

        // submit message to topic inner transactions
        final var submitMessageBeforeUpdate = submitMessageTo(TOPIC_ID)
                .message(TOPIC_MESSAGE)
                .signedBy(PAYER, submitKey)
                .payingWith(PAYER)
                .via("innerTxnBeforeUpdate")
                .batchKey(BATCH_OPERATOR);

        final var submitMessageAfterUpdate = submitMessageTo(TOPIC_ID)
                .message(TOPIC_MESSAGE_UPDATE)
                .signedBy(PAYER, submitKey)
                .payingWith(PAYER)
                .via("innerTxnAfterUpdate")
                .batchKey(BATCH_OPERATOR);

        // update topic inner transaction
        final var updateTopic = updateTopic(TOPIC_ID)
                .autoRenewAccountId(AUTO_RENEW_ACCOUNT)
                .autoRenewPeriod(7_000_000L)
                .topicMemo(TEST_MEMO_UPDATE)
                .payingWith(PAYER)
                .signedBy(PAYER, adminKey)
                .via("updateTopicTxn")
                .batchKey(BATCH_OPERATOR);

        return hapiTest(flattened(
                createAccountsAndKeys(),
                createMutableTopicWithSubmitKey(adminKey, submitKey, TOPIC_ID),
                atomicBatch(submitMessageBeforeUpdate, updateTopic, submitMessageAfterUpdate)
                        .payingWith(BATCH_OPERATOR)
                        .via("batchTxn")
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                // confirm topic is not updated
                getTopicInfo(TOPIC_ID)
                        .hasMemo(TEST_MEMO)
                        .hasAdminKey(adminKey)
                        .hasSubmitKey(submitKey)
                        .hasNoCustomFee()));
    }

    @HapiTest
    public Stream<DynamicTest> updateImmutableTopicWithNewAutoRenewAccountAndPeriodAndSubmitMessagesFailsInBatch() {

        // submit message to topic inner transactions
        final var submitMessageBeforeUpdate = submitMessageTo(TOPIC_ID)
                .message(TOPIC_MESSAGE)
                .signedBy(PAYER, submitKey)
                .payingWith(PAYER)
                .via("innerTxnBeforeUpdate")
                .batchKey(BATCH_OPERATOR);

        final var submitMessageAfterUpdate = submitMessageTo(TOPIC_ID)
                .message(TOPIC_MESSAGE_UPDATE)
                .signedBy(PAYER, submitKey)
                .payingWith(PAYER)
                .via("innerTxnAfterUpdate")
                .batchKey(BATCH_OPERATOR);

        // update topic inner transaction
        final var updateTopic = updateTopic(TOPIC_ID)
                .autoRenewAccountId(AUTO_RENEW_ACCOUNT_UPDATE)
                .autoRenewPeriod(8_000_000L)
                .topicMemo(TEST_MEMO_UPDATE)
                .payingWith(PAYER)
                .signedBy(PAYER, AUTO_RENEW_ACCOUNT)
                .via("updateTopicTxn")
                .batchKey(BATCH_OPERATOR);

        return hapiTest(flattened(
                createAccountsAndKeys(),
                createImmutableTopicWithSubmitKeyAndAutoRenew(submitKey, AUTO_RENEW_ACCOUNT, TOPIC_ID),
                atomicBatch(submitMessageBeforeUpdate, updateTopic, submitMessageAfterUpdate)
                        .payingWith(BATCH_OPERATOR)
                        .via("batchTxn")
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                // confirm topic is not updated
                getTopicInfo(TOPIC_ID)
                        .hasMemo(TEST_MEMO)
                        .hasSubmitKey(submitKey)
                        .hasNoCustomFee()));
    }

    @HapiTest
    public Stream<DynamicTest>
            updateImmutableTopicWithoutAutoRenewWithAutoRenewAccountAndPeriodAndSubmitMessagesFailsInBatch() {

        // submit message to topic inner transactions
        final var submitMessageBeforeUpdate = submitMessageTo(TOPIC_ID)
                .message(TOPIC_MESSAGE)
                .signedBy(PAYER, submitKey)
                .payingWith(PAYER)
                .via("innerTxnBeforeUpdate")
                .batchKey(BATCH_OPERATOR);

        final var submitMessageAfterUpdate = submitMessageTo(TOPIC_ID)
                .message(TOPIC_MESSAGE_UPDATE)
                .signedBy(PAYER, submitKey)
                .payingWith(PAYER)
                .via("innerTxnAfterUpdate")
                .batchKey(BATCH_OPERATOR);

        // update topic inner transaction
        final var updateTopic = updateTopic(TOPIC_ID)
                .autoRenewAccountId(AUTO_RENEW_ACCOUNT_UPDATE)
                .autoRenewPeriod(8_000_000L)
                .topicMemo(TEST_MEMO_UPDATE)
                .payingWith(PAYER)
                .signedBy(PAYER, AUTO_RENEW_ACCOUNT)
                .via("updateTopicTxn")
                .batchKey(BATCH_OPERATOR);

        return hapiTest(flattened(
                createAccountsAndKeys(),
                createImmutableTopicWithSubmitKey(submitKey, TOPIC_ID),
                atomicBatch(submitMessageBeforeUpdate, updateTopic, submitMessageAfterUpdate)
                        .payingWith(BATCH_OPERATOR)
                        .via("batchTxn")
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                // confirm topic is not updated
                getTopicInfo(TOPIC_ID)
                        .logged()
                        .hasMemo(TEST_MEMO)
                        .hasSubmitKey(submitKey)
                        .hasNoCustomFee()));
    }

    @HapiTest
    public Stream<DynamicTest> deleteImmutableTopicAndSubmitMessageFailsInBatch() {

        // submit message to topic inner transactions
        final var submitMessageBeforeUpdate = submitMessageTo(TOPIC_ID)
                .message(TOPIC_MESSAGE)
                .signedBy(PAYER, submitKey)
                .payingWith(PAYER)
                .via("innerTxnBeforeUpdate")
                .batchKey(BATCH_OPERATOR);

        final var submitMessageAfterUpdate = submitMessageTo(TOPIC_ID)
                .message(TOPIC_MESSAGE_UPDATE)
                .signedBy(PAYER, submitKey)
                .payingWith(PAYER)
                .via("innerTxnAfterUpdate")
                .batchKey(BATCH_OPERATOR);

        // delete topic inner transaction
        final var deleteTopicTransaction =
                deleteTopic(TOPIC_ID).payingWith(PAYER).via("deleteTopicTxn").batchKey(BATCH_OPERATOR);

        return hapiTest(flattened(
                createAccountsAndKeys(),
                createImmutableTopicWithSubmitKey(submitKey, TOPIC_ID),
                atomicBatch(submitMessageBeforeUpdate, deleteTopicTransaction, submitMessageAfterUpdate)
                        .payingWith(BATCH_OPERATOR)
                        .via("batchTxn")
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                // confirm topic is not updated
                getTopicInfo(TOPIC_ID)
                        .logged()
                        .hasMemo(TEST_MEMO)
                        .hasSubmitKey(submitKey)
                        .hasNoCustomFee()));
    }

    @HapiTest
    public Stream<DynamicTest> updateMutableTopicWithNewAdminKeyAndSubmitMessagesSuccessInBatch() {

        // submit message to topic inner transactions
        final var submitMessageBeforeUpdate = submitMessageTo(TOPIC_ID)
                .message(TOPIC_MESSAGE)
                .signedBy(PAYER, submitKey)
                .payingWith(PAYER)
                .via("innerTxnBeforeUpdate")
                .batchKey(BATCH_OPERATOR);

        final var submitMessageAfterUpdate = submitMessageTo(TOPIC_ID)
                .message(TOPIC_MESSAGE_UPDATE)
                .signedBy(PAYER, submitKey)
                .payingWith(PAYER)
                .via("innerTxnAfterUpdate")
                .batchKey(BATCH_OPERATOR);

        // update topic inner transaction
        final var updateTopic = updateTopic(TOPIC_ID)
                .adminKey(newAdminKey)
                .payingWith(PAYER)
                .signedBy(PAYER, adminKey, newAdminKey)
                .via("updateTopicTxn")
                .batchKey(BATCH_OPERATOR);

        return hapiTest(flattened(
                createAccountsAndKeys(),
                createMutableTopicWithSubmitKey(adminKey, submitKey, TOPIC_ID),
                atomicBatch(submitMessageBeforeUpdate, updateTopic, submitMessageAfterUpdate)
                        .payingWith(BATCH_OPERATOR)
                        .via("batchTxn")
                        .hasKnownStatus(SUCCESS),
                validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                // confirm topic is updated
                getTopicInfo(TOPIC_ID)
                        .hasMemo(TEST_MEMO)
                        .hasAdminKey(newAdminKey)
                        .hasSubmitKey(submitKey)
                        .hasNoCustomFee()));
    }

    @HapiTest
    public Stream<DynamicTest> updateMutableTopicWithNewAdminKeyAndSubmitMessagesNotSignedByOldAdminKeyFailsInBatch() {

        // submit message to topic inner transactions
        final var submitMessageBeforeUpdate = submitMessageTo(TOPIC_ID)
                .message(TOPIC_MESSAGE)
                .signedBy(PAYER, submitKey)
                .payingWith(PAYER)
                .via("innerTxnBeforeUpdate")
                .batchKey(BATCH_OPERATOR);

        final var submitMessageAfterUpdate = submitMessageTo(TOPIC_ID)
                .message(TOPIC_MESSAGE_UPDATE)
                .signedBy(PAYER, submitKey)
                .payingWith(PAYER)
                .via("innerTxnAfterUpdate")
                .batchKey(BATCH_OPERATOR);

        // update topic inner transaction
        final var updateTopic = updateTopic(TOPIC_ID)
                .adminKey(newAdminKey)
                .payingWith(PAYER)
                .signedBy(PAYER, newAdminKey)
                .via("updateTopicTxn")
                .batchKey(BATCH_OPERATOR);

        return hapiTest(flattened(
                createAccountsAndKeys(),
                createMutableTopicWithSubmitKey(adminKey, submitKey, TOPIC_ID),
                atomicBatch(submitMessageBeforeUpdate, updateTopic, submitMessageAfterUpdate)
                        .payingWith(BATCH_OPERATOR)
                        .via("batchTxn")
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                // confirm topic is not updated
                getTopicInfo(TOPIC_ID)
                        .hasMemo(TEST_MEMO)
                        .hasAdminKey(adminKey)
                        .hasSubmitKey(submitKey)
                        .hasNoCustomFee()));
    }

    @HapiTest
    public Stream<DynamicTest> updateMutableTopicWithNewAdminKeyAndSubmitMessagesNotSignedByNewAdminKeyFailsInBatch() {

        // submit message to topic inner transactions
        final var submitMessageBeforeUpdate = submitMessageTo(TOPIC_ID)
                .message(TOPIC_MESSAGE)
                .signedBy(PAYER, submitKey)
                .payingWith(PAYER)
                .via("innerTxnBeforeUpdate")
                .batchKey(BATCH_OPERATOR);

        final var submitMessageAfterUpdate = submitMessageTo(TOPIC_ID)
                .message(TOPIC_MESSAGE_UPDATE)
                .signedBy(PAYER, submitKey)
                .payingWith(PAYER)
                .via("innerTxnAfterUpdate")
                .batchKey(BATCH_OPERATOR);

        // update topic inner transaction
        final var updateTopic = updateTopic(TOPIC_ID)
                .adminKey(newAdminKey)
                .payingWith(PAYER)
                .signedBy(PAYER, adminKey)
                .via("updateTopicTxn")
                .batchKey(BATCH_OPERATOR);

        return hapiTest(flattened(
                createAccountsAndKeys(),
                createMutableTopicWithSubmitKey(adminKey, submitKey, TOPIC_ID),
                atomicBatch(submitMessageBeforeUpdate, updateTopic, submitMessageAfterUpdate)
                        .payingWith(BATCH_OPERATOR)
                        .via("batchTxn")
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                // confirm topic is not updated
                getTopicInfo(TOPIC_ID)
                        .hasMemo(TEST_MEMO)
                        .hasAdminKey(adminKey)
                        .hasSubmitKey(submitKey)
                        .hasNoCustomFee()));
    }

    @HapiTest
    public Stream<DynamicTest> updateMutableTopicWithNewAdminKeyAndUpdateTheTopicSuccessInBatch() {

        // submit message to topic inner transactions
        final var submitMessageBeforeUpdate = submitMessageTo(TOPIC_ID)
                .message(TOPIC_MESSAGE)
                .signedBy(PAYER, submitKey)
                .payingWith(PAYER)
                .via("innerTxnBeforeUpdate")
                .batchKey(BATCH_OPERATOR);

        final var submitMessageAfterUpdate = submitMessageTo(TOPIC_ID)
                .message(TOPIC_MESSAGE_UPDATE)
                .signedBy(PAYER, submitKey)
                .payingWith(PAYER)
                .via("innerTxnAfterUpdate")
                .batchKey(BATCH_OPERATOR);

        // update topic inner transactions
        final var updateTopicAdminKey = updateTopic(TOPIC_ID)
                .adminKey(newAdminKey)
                .payingWith(PAYER)
                .signedBy(PAYER, adminKey, newAdminKey)
                .via("updateTopicTxn")
                .batchKey(BATCH_OPERATOR);

        final var updateTopicMemo = updateTopic(TOPIC_ID)
                .topicMemo(TEST_MEMO_UPDATE)
                .payingWith(PAYER)
                .signedBy(PAYER, newAdminKey)
                .via("updateTopicTxn")
                .batchKey(BATCH_OPERATOR);

        return hapiTest(flattened(
                createAccountsAndKeys(),
                createMutableTopicWithSubmitKey(adminKey, submitKey, TOPIC_ID),
                atomicBatch(submitMessageBeforeUpdate, updateTopicAdminKey, updateTopicMemo, submitMessageAfterUpdate)
                        .payingWith(BATCH_OPERATOR)
                        .via("batchTxn")
                        .hasKnownStatus(SUCCESS),
                validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                // confirm topic is updated
                getTopicInfo(TOPIC_ID)
                        .hasMemo(TEST_MEMO_UPDATE)
                        .hasAdminKey(newAdminKey)
                        .hasSubmitKey(submitKey)
                        .hasNoCustomFee()));
    }

    @HapiTest
    public Stream<DynamicTest>
            updateMutableTopicWithNewAdminKeyAndUpdateTheTopicNotSignedByTheNewAdminKeyFailsInBatch() {

        // submit message to topic inner transactions
        final var submitMessageBeforeUpdate = submitMessageTo(TOPIC_ID)
                .message(TOPIC_MESSAGE)
                .signedBy(PAYER, submitKey)
                .payingWith(PAYER)
                .via("innerTxnBeforeUpdate")
                .batchKey(BATCH_OPERATOR);

        final var submitMessageAfterUpdate = submitMessageTo(TOPIC_ID)
                .message(TOPIC_MESSAGE_UPDATE)
                .signedBy(PAYER, submitKey)
                .payingWith(PAYER)
                .via("innerTxnAfterUpdate")
                .batchKey(BATCH_OPERATOR);

        // update topic inner transactions
        final var updateTopicAdminKey = updateTopic(TOPIC_ID)
                .adminKey(newAdminKey)
                .payingWith(PAYER)
                .signedBy(PAYER, adminKey, newAdminKey)
                .via("updateTopicTxn")
                .batchKey(BATCH_OPERATOR);

        final var updateTopicMemo = updateTopic(TOPIC_ID)
                .topicMemo(TEST_MEMO_UPDATE)
                .payingWith(PAYER)
                .signedBy(PAYER, adminKey)
                .via("updateTopicTxn")
                .batchKey(BATCH_OPERATOR);

        return hapiTest(flattened(
                createAccountsAndKeys(),
                createMutableTopicWithSubmitKey(adminKey, submitKey, TOPIC_ID),
                atomicBatch(submitMessageBeforeUpdate, updateTopicAdminKey, updateTopicMemo, submitMessageAfterUpdate)
                        .payingWith(BATCH_OPERATOR)
                        .via("batchTxn")
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                // confirm topic is not updated
                getTopicInfo(TOPIC_ID)
                        .hasMemo(TEST_MEMO)
                        .hasAdminKey(adminKey)
                        .hasSubmitKey(submitKey)
                        .hasNoCustomFee()));
    }

    @HapiTest
    public Stream<DynamicTest> updateMutableTopicWithNewSubmitKeyAndSubmitMessagesSuccessInBatch() {

        // submit message to topic inner transactions
        final var submitMessageBeforeUpdate = submitMessageTo(TOPIC_ID)
                .message(TOPIC_MESSAGE)
                .signedBy(PAYER, submitKey)
                .payingWith(PAYER)
                .via("innerTxnBeforeUpdate")
                .batchKey(BATCH_OPERATOR);

        final var submitMessageAfterUpdate = submitMessageTo(TOPIC_ID)
                .message(TOPIC_MESSAGE_UPDATE)
                .signedBy(PAYER, newSubmitKey)
                .payingWith(PAYER)
                .via("innerTxnAfterUpdate")
                .batchKey(BATCH_OPERATOR);

        // update topic inner transaction
        final var updateTopicSubmitKey = updateTopic(TOPIC_ID)
                .submitKey(newSubmitKey)
                .payingWith(PAYER)
                .signedBy(PAYER, adminKey)
                .via("updateTopicTxn")
                .batchKey(BATCH_OPERATOR);

        return hapiTest(flattened(
                createAccountsAndKeys(),
                createMutableTopicWithSubmitKey(adminKey, submitKey, TOPIC_ID),
                atomicBatch(submitMessageBeforeUpdate, updateTopicSubmitKey, submitMessageAfterUpdate)
                        .payingWith(BATCH_OPERATOR)
                        .via("batchTxn")
                        .hasKnownStatus(SUCCESS),
                validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                // confirm topic is updated
                getTopicInfo(TOPIC_ID)
                        .hasMemo(TEST_MEMO)
                        .hasAdminKey(adminKey)
                        .hasSubmitKey(newSubmitKey)
                        .hasNoCustomFee()));
    }

    @HapiTest
    public Stream<DynamicTest> updateMutableTopicWithNewAdminKeyAndNewSubmitKeyAndSubmitMessagesSuccessInBatch() {

        // submit message to topic inner transactions
        final var submitMessageBeforeUpdate = submitMessageTo(TOPIC_ID)
                .message(TOPIC_MESSAGE)
                .signedBy(PAYER, submitKey)
                .payingWith(PAYER)
                .via("innerTxnBeforeUpdate")
                .batchKey(BATCH_OPERATOR);

        final var submitMessageAfterUpdate = submitMessageTo(TOPIC_ID)
                .message(TOPIC_MESSAGE_UPDATE)
                .signedBy(PAYER, newSubmitKey)
                .payingWith(PAYER)
                .via("innerTxnAfterUpdate")
                .batchKey(BATCH_OPERATOR);

        // update topic inner transactions
        final var updateTopicAdminKey = updateTopic(TOPIC_ID)
                .adminKey(newAdminKey)
                .payingWith(PAYER)
                .signedBy(PAYER, adminKey, newAdminKey)
                .via("updateTopicTxn")
                .batchKey(BATCH_OPERATOR);

        final var updateTopicSubmitKey = updateTopic(TOPIC_ID)
                .submitKey(newSubmitKey)
                .payingWith(PAYER)
                .signedBy(PAYER, newAdminKey)
                .via("updateTopicTxn")
                .batchKey(BATCH_OPERATOR);

        return hapiTest(flattened(
                createAccountsAndKeys(),
                createMutableTopicWithSubmitKey(adminKey, submitKey, TOPIC_ID),
                atomicBatch(
                                submitMessageBeforeUpdate,
                                updateTopicAdminKey,
                                updateTopicSubmitKey,
                                submitMessageAfterUpdate)
                        .payingWith(BATCH_OPERATOR)
                        .via("batchTxn")
                        .hasKnownStatus(SUCCESS),
                validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                // confirm topic is updated
                getTopicInfo(TOPIC_ID)
                        .hasMemo(TEST_MEMO)
                        .hasAdminKey(newAdminKey)
                        .hasSubmitKey(newSubmitKey)
                        .hasNoCustomFee()));
    }

    private HapiTopicCreate createImmutableTopicWithSubmitKey(String submitKey, String topicId) {
        return new HapiTopicCreate(topicId)
                .submitKeyName(submitKey)
                .topicMemo(TEST_MEMO)
                .via("createTopicTxn");
    }

    private HapiTopicCreate createImmutableTopicWithSubmitKeyAndAutoRenew(
            String submitKey, String autoRenewAccount, String topicId) {
        return new HapiTopicCreate(topicId)
                .submitKeyName(submitKey)
                .autoRenewAccountId(autoRenewAccount)
                .autoRenewPeriod(7_000_000L)
                .topicMemo(TEST_MEMO)
                .via("createTopicTxn");
    }

    private HapiTopicCreate createMutableTopicWithSubmitKeyAndAutoRenew(
            String adminKey, String submitKey, String autoRenewAccount, String topicId) {
        return new HapiTopicCreate(topicId)
                .adminKeyName(adminKey)
                .submitKeyName(submitKey)
                .autoRenewAccountId(autoRenewAccount)
                .autoRenewPeriod(7_000_000L)
                .topicMemo(TEST_MEMO)
                .via("createTopicTxn");
    }

    private HapiTopicCreate createMutableTopicWithSubmitKey(String adminKey, String submitKey, String topicId) {
        return new HapiTopicCreate(topicId)
                .adminKeyName(adminKey)
                .submitKeyName(submitKey)
                .topicMemo(TEST_MEMO)
                .via("createTopicTxn");
    }

    private List<SpecOperation> createAccountsAndKeys() {
        return List.of(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_HBAR),
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(AUTO_RENEW_ACCOUNT).balance(ONE_HBAR),
                cryptoCreate(AUTO_RENEW_ACCOUNT_UPDATE).balance(ONE_HBAR),
                newKeyNamed(submitKey),
                newKeyNamed(newSubmitKey),
                newKeyNamed(adminKey),
                newKeyNamed(newAdminKey));
    }
}
