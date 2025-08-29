// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.consensus;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTopicInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.deleteTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.submitMessageTo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.updateTopic;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedConsensusHbarFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedConsensusHtsFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.htsLimit;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.maxCustomFee;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsd;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CUSTOM_FEES_LIST_TOO_LONG;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOPIC_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MAX_CUSTOM_FEE_LIMIT_EXCEEDED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MAX_ENTRIES_FOR_FEE_EXEMPT_KEY_LIST_EXCEEDED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.UNAUTHORIZED;

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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;

@HapiTestLifecycle
public class AtomicBatchConsensusServiceEndToEndTest {

    private static final double BASE_FEE_BATCH_TRANSACTION = 0.001;
    private static final long HBAR_FEE = 1L;
    private static final long HTS_FEE = 1L;
    private static final long denomTokenInitialSupply = 1_000_000L;
    private static final long denomTokenPayerInitialSupply = 10L;
    private static final String DENOM_TOKEN = "testDenomToken";
    private static final String DENOM_TOKEN_SECOND = "testDenomTokenSecond";
    private static final String DENOM_TOKEN_THIRD = "testDenomTokenThird";
    private static final String DENOM_TOKEN_FOURTH = "testDenomTokenFourth";
    private static final String DENOM_TOKEN_FIFTH = "testDenomTokenFifth";
    private static final String DENOM_TOKEN_SIXTH = "testDenomTokenSixth";
    private static final String DENOM_TOKEN_SEVENTH = "testDenomTokenSeventh";
    private static final String DENOM_TOKEN_EIGHTH = "testDenomTokenEighth";
    private static final String DENOM_TOKEN_NINTH = "testDenomTokenNinth";
    private static final String DENOM_TOKEN_TENTH = "testDenomTokenTenth";
    private static final String DENOM_TOKEN_ELEVENTH = "testDenomTokenEleventh";
    private static final String BATCH_OPERATOR = "batchOperator";
    private static final String PAYER = "payer";
    private static final String PAYER_INSUFFICIENT_BALANCE = "payerInsufficientBalance";
    private static final String PAYER_EXEMPT_FEES = "payerExemptFees";
    private static final String PAYER_EXEMPT_FEES_SECOND = "payerExemptFeesSecond";
    private static final String PAYER_EXEMPT_FEES_THIRD = "payerExemptFeesThird";
    private static final String HBAR_COLLECTOR = "hbarCollector";
    private static final String HTS_COLLECTOR = "htsCollector";
    private static final String AUTO_RENEW_ACCOUNT = "autoRenewAccount";
    private static final String AUTO_RENEW_ACCOUNT_UPDATE = "autoRenewAccountUpdate";
    private static final String TREASURY_FOR_CUSTOM_FEE_TOKEN = "treasuryForCustomFeeToken";
    private static final String TOPIC_ID = "testTopic";
    private static final String TOPIC_ID_SECOND = "testTopicSecond";
    private static final String TOPIC_MESSAGE = "testMessage";
    private static final String TOPIC_MESSAGE_UPDATE = "topicMessageUpdate";
    private static final String TEST_MEMO = "Test topic for atomic batch consensus service end-to-end test";
    private static final String TEST_MEMO_UPDATE = "Updated topic for atomic batch consensus service end-to-end test";
    private static final String submitKey = "submitKey";
    private static final String newSubmitKey = "newSubmitKey";
    private static final String adminKey = "adminKey";
    private static final String newAdminKey = "newAdminKey";
    private static final String feeScheduleKey = "feeScheduleKey";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle lifecycle) {
        lifecycle.overrideInClass(Map.of("atomicBatch.isEnabled", "true", "atomicBatch.maxNumberOfTransactions", "50"));
    }

    @Nested
    @DisplayName(
            "Atomic Batch Consensus Service End-to-End Tests - Test Cases with Topic Submit Messages, Topic Updates and Deletes")
    class AtomicBatchConsensusServiceEndToEndTestsWithSubmitMessagesTopicUpdatesAndDeletes {
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
                    getTopicInfo(TOPIC_ID)
                            .hasCostAnswerPrecheck(INVALID_TOPIC_ID)
                            .logged()));
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
                    getTopicInfo(TOPIC_ID)
                            .hasCostAnswerPrecheck(INVALID_TOPIC_ID)
                            .logged()));
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
                    .batchKey(BATCH_OPERATOR)
                    .hasKnownStatus(INVALID_TOPIC_ID);

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
                    .batchKey(BATCH_OPERATOR)
                    .hasKnownStatus(INVALID_TOPIC_ID);

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
                    .batchKey(BATCH_OPERATOR)
                    .hasKnownStatus(INVALID_TOPIC_ID);

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
                    .batchKey(BATCH_OPERATOR)
                    .hasKnownStatus(INVALID_SIGNATURE);

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
                    .batchKey(BATCH_OPERATOR)
                    .hasKnownStatus(UNAUTHORIZED);

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
                    .batchKey(BATCH_OPERATOR)
                    .hasKnownStatus(UNAUTHORIZED);

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
            final var deleteTopicTransaction = deleteTopic(TOPIC_ID)
                    .payingWith(PAYER)
                    .via("deleteTopicTxn")
                    .batchKey(BATCH_OPERATOR)
                    .hasKnownStatus(UNAUTHORIZED);

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
        @Tag(MATS)
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
        public Stream<DynamicTest>
                updateMutableTopicWithNewAdminKeyAndSubmitMessagesNotSignedByOldAdminKeyFailsInBatch() {

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
                    .batchKey(BATCH_OPERATOR)
                    .hasKnownStatus(INVALID_SIGNATURE);

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
        public Stream<DynamicTest>
                updateMutableTopicWithNewAdminKeyAndSubmitMessagesNotSignedByNewAdminKeyFailsInBatch() {

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
                    .batchKey(BATCH_OPERATOR)
                    .hasKnownStatus(INVALID_SIGNATURE);

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
                    atomicBatch(
                                    submitMessageBeforeUpdate,
                                    updateTopicAdminKey,
                                    updateTopicMemo,
                                    submitMessageAfterUpdate)
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
                    .batchKey(BATCH_OPERATOR)
                    .hasKnownStatus(INVALID_SIGNATURE);

            return hapiTest(flattened(
                    createAccountsAndKeys(),
                    createMutableTopicWithSubmitKey(adminKey, submitKey, TOPIC_ID),
                    atomicBatch(
                                    submitMessageBeforeUpdate,
                                    updateTopicAdminKey,
                                    updateTopicMemo,
                                    submitMessageAfterUpdate)
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
    }

    @Nested
    @DisplayName("Atomic Batch Consensus Service End-to-End Tests with Submit Messages to Topics with Custom Fees")
    class AtomicBatchConsensusServiceWithCustomFees {
        @HapiTest
        public Stream<DynamicTest> submitMessagesToMutableTopicWithHBARCustomFeeAndUpdateTopicSuccessInBatch() {

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
                    createMutableTopicWithSubmitKeyAndHBARFixedFee(adminKey, submitKey, feeScheduleKey, TOPIC_ID),
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
                            .hasCustomFeeSize(1),

                    // confirm collector account balance
                    getAccountBalance(HBAR_COLLECTOR).hasTinyBars(2 * HBAR_FEE)));
        }

        @HapiTest
        public Stream<DynamicTest> submitMessagesToMutableTopicWithHTSCustomFeeAndUpdateTopicSuccessInBatch() {

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
                    createMutableTopicWithSubmitKeyAndHTSFixedFee(adminKey, submitKey, feeScheduleKey, TOPIC_ID),
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
                            .hasCustomFeeSize(1),

                    // confirm accounts balances
                    getAccountBalance(HTS_COLLECTOR).hasTokenBalance(DENOM_TOKEN, 2 * HTS_FEE),
                    getAccountBalance(PAYER).hasTokenBalance(DENOM_TOKEN, denomTokenPayerInitialSupply - 2 * HTS_FEE),
                    getAccountBalance(TREASURY_FOR_CUSTOM_FEE_TOKEN)
                            .hasTokenBalance(DENOM_TOKEN, denomTokenInitialSupply - denomTokenPayerInitialSupply)));
        }

        @HapiTest
        public Stream<DynamicTest> submitMessagesToMultipleTopicsWithCustomFeesSuccessInBatch() {

            // submit message to topic inner transactions
            final var submitMessageToFirstTopic = submitMessageTo(TOPIC_ID)
                    .message(TOPIC_MESSAGE)
                    .signedBy(PAYER, submitKey)
                    .payingWith(PAYER)
                    .via("innerTxnBeforeUpdate")
                    .batchKey(BATCH_OPERATOR);

            final var submitMessageToSecondTopic = submitMessageTo(TOPIC_ID_SECOND)
                    .message(TOPIC_MESSAGE)
                    .signedBy(PAYER, submitKey)
                    .payingWith(PAYER)
                    .via("innerTxnAfterUpdate")
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    createAccountsAndKeys(),
                    createMutableTopicWithSubmitKeyAndHBARFixedFee(adminKey, submitKey, feeScheduleKey, TOPIC_ID),
                    createMutableTopicWithSubmitKeyAndHTSFixedFee(adminKey, submitKey, feeScheduleKey, TOPIC_ID_SECOND),
                    atomicBatch(submitMessageToFirstTopic, submitMessageToSecondTopic)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(SUCCESS),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // confirm accounts balances
                    getAccountBalance(HBAR_COLLECTOR).hasTinyBars(HBAR_FEE),
                    getAccountBalance(HTS_COLLECTOR).hasTokenBalance(DENOM_TOKEN, HTS_FEE),
                    getAccountBalance(PAYER).hasTokenBalance(DENOM_TOKEN, denomTokenPayerInitialSupply - HTS_FEE),
                    getAccountBalance(TREASURY_FOR_CUSTOM_FEE_TOKEN)
                            .hasTokenBalance(DENOM_TOKEN, denomTokenInitialSupply - denomTokenPayerInitialSupply)));
        }

        @HapiTest
        public Stream<DynamicTest>
                submitMessagesToTopicWithCustomFeesWithInvalidInnerTxnFailInBatchAndNoCustomFeesAreTransferred() {

            // submit message to topic inner transactions
            final var submitMessageToFirstTopic = submitMessageTo(TOPIC_ID)
                    .message(TOPIC_MESSAGE)
                    .signedBy(PAYER, submitKey)
                    .payingWith(PAYER)
                    .via("innerTxnBeforeUpdate")
                    .batchKey(BATCH_OPERATOR);

            final var submitMessageToSecondTopic = submitMessageTo(TOPIC_ID_SECOND)
                    .message(TOPIC_MESSAGE)
                    .signedBy(PAYER, submitKey)
                    .payingWith(PAYER)
                    .via("innerTxnAfterUpdate")
                    .batchKey(BATCH_OPERATOR);

            // submit message to topic - invalid inner transaction
            final var submitMessageToTopicInvalidTransaction = submitMessageTo(TOPIC_ID)
                    .message(TOPIC_MESSAGE)
                    .signedBy(PAYER)
                    .payingWith(PAYER)
                    .via("innerTxnBeforeUpdate")
                    .batchKey(BATCH_OPERATOR)
                    .hasKnownStatus(INVALID_SIGNATURE);

            return hapiTest(flattened(
                    createAccountsAndKeys(),
                    createMutableTopicWithSubmitKeyAndHBARFixedFee(adminKey, submitKey, feeScheduleKey, TOPIC_ID),
                    createMutableTopicWithSubmitKeyAndHTSFixedFee(adminKey, submitKey, feeScheduleKey, TOPIC_ID_SECOND),
                    atomicBatch(
                                    submitMessageToFirstTopic,
                                    submitMessageToSecondTopic,
                                    submitMessageToTopicInvalidTransaction)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // confirm accounts balances
                    getAccountBalance(HBAR_COLLECTOR).hasTinyBars(0),
                    getAccountBalance(HTS_COLLECTOR).hasTokenBalance(DENOM_TOKEN, 0),
                    getAccountBalance(PAYER).hasTokenBalance(DENOM_TOKEN, denomTokenPayerInitialSupply),
                    getAccountBalance(TREASURY_FOR_CUSTOM_FEE_TOKEN)
                            .hasTokenBalance(DENOM_TOKEN, denomTokenInitialSupply - denomTokenPayerInitialSupply)));
        }

        @HapiTest
        public Stream<DynamicTest> submitMessagesToTopicWithCustomFeesWithInsufficientPayerBalanceFailsInBatch() {

            // submit message to topic inner transactions
            final var submitMessageToFirstTopic = submitMessageTo(TOPIC_ID)
                    .message(TOPIC_MESSAGE)
                    .signedBy(PAYER_INSUFFICIENT_BALANCE, submitKey)
                    .payingWith(PAYER_INSUFFICIENT_BALANCE)
                    .via("innerTxnBeforeUpdate")
                    .batchKey(BATCH_OPERATOR);

            final var submitMessageToSecondTopic = submitMessageTo(TOPIC_ID_SECOND)
                    .message(TOPIC_MESSAGE)
                    .signedBy(PAYER_INSUFFICIENT_BALANCE, submitKey)
                    .payingWith(PAYER_INSUFFICIENT_BALANCE)
                    .via("innerTxnAfterUpdate")
                    .batchKey(BATCH_OPERATOR)
                    .hasKnownStatus(INSUFFICIENT_PAYER_BALANCE);

            return hapiTest(flattened(
                    createAccountsAndKeys(),
                    createMutableTopicWithSubmitKeyAndHBARFixedFee(adminKey, submitKey, feeScheduleKey, TOPIC_ID),
                    createMutableTopicWithSubmitKeyAndHTSFixedFee(adminKey, submitKey, feeScheduleKey, TOPIC_ID_SECOND),
                    atomicBatch(submitMessageToFirstTopic, submitMessageToSecondTopic)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // confirm accounts balances
                    getAccountBalance(HBAR_COLLECTOR).hasTinyBars(0),
                    getAccountBalance(HTS_COLLECTOR).hasTokenBalance(DENOM_TOKEN, 0),
                    getAccountBalance(PAYER).hasTokenBalance(DENOM_TOKEN, denomTokenPayerInitialSupply),
                    getAccountBalance(TREASURY_FOR_CUSTOM_FEE_TOKEN)
                            .hasTokenBalance(DENOM_TOKEN, denomTokenInitialSupply - denomTokenPayerInitialSupply)));
        }

        @HapiTest
        public Stream<DynamicTest> submitMessagesToTopicWithMaxCustomFeeLessThanRequiredFeeFailsInBatch() {

            // set custom fees limit
            final var htsFeeLimit = htsLimit(DENOM_TOKEN, 1 / 2);

            // submit message to topic inner transactions
            final var submitMessageToFirstTopic = submitMessageTo(TOPIC_ID)
                    .message(TOPIC_MESSAGE)
                    .signedBy(PAYER, submitKey)
                    .payingWith(PAYER)
                    .via("innerTxnBeforeUpdate")
                    .batchKey(BATCH_OPERATOR);

            final var submitMessageToSecondTopic = submitMessageTo(TOPIC_ID_SECOND)
                    .maxCustomFee(maxCustomFee(PAYER, htsFeeLimit))
                    .message(TOPIC_MESSAGE)
                    .signedBy(PAYER, submitKey)
                    .payingWith(PAYER)
                    .via("innerTxnAfterUpdate")
                    .batchKey(BATCH_OPERATOR)
                    .hasKnownStatus(MAX_CUSTOM_FEE_LIMIT_EXCEEDED);

            return hapiTest(flattened(
                    createAccountsAndKeys(),
                    createMutableTopicWithSubmitKeyAndHBARFixedFee(adminKey, submitKey, feeScheduleKey, TOPIC_ID),
                    createMutableTopicWithSubmitKeyAndHTSFixedFee(adminKey, submitKey, feeScheduleKey, TOPIC_ID_SECOND),
                    atomicBatch(submitMessageToFirstTopic, submitMessageToSecondTopic)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // confirm accounts balances
                    getAccountBalance(HBAR_COLLECTOR).hasTinyBars(0),
                    getAccountBalance(HTS_COLLECTOR).hasTokenBalance(DENOM_TOKEN, 0),
                    getAccountBalance(PAYER).hasTokenBalance(DENOM_TOKEN, denomTokenPayerInitialSupply),
                    getAccountBalance(TREASURY_FOR_CUSTOM_FEE_TOKEN)
                            .hasTokenBalance(DENOM_TOKEN, denomTokenInitialSupply - denomTokenPayerInitialSupply)));
        }
    }

    @Nested
    @DisplayName("Atomic Batch Consensus Service End-to-End Tests with Updates of Topics with Custom Fees")
    class AtomicBatchConsensusServiceWithCustomFeesUpdates {
        @HapiTest
        @Tag(MATS)
        public Stream<DynamicTest> submitMessagesToMultipleTopicsWithCustomFeesUpdatesSuccessInBatch() {

            // submit message to topic inner transactions
            final var submitMessageToFirstTopicBeforeUpdate = submitMessageTo(TOPIC_ID)
                    .message(TOPIC_MESSAGE)
                    .signedBy(PAYER, submitKey)
                    .payingWith(PAYER)
                    .via("innerTxnBeforeUpdateFirst")
                    .batchKey(BATCH_OPERATOR);

            final var submitMessageToSecondTopicBeforeUpdate = submitMessageTo(TOPIC_ID_SECOND)
                    .message(TOPIC_MESSAGE)
                    .signedBy(PAYER, submitKey)
                    .payingWith(PAYER)
                    .via("innerTxnBeforeUpdateSecond")
                    .batchKey(BATCH_OPERATOR);

            final var submitMessageToFirstTopicAfterUpdate = submitMessageTo(TOPIC_ID)
                    .message(TOPIC_MESSAGE_UPDATE)
                    .signedBy(PAYER, submitKey)
                    .payingWith(PAYER)
                    .via("innerTxnAfterUpdateFirst")
                    .batchKey(BATCH_OPERATOR);

            final var submitMessageToSecondTopicAfterUpdate = submitMessageTo(TOPIC_ID_SECOND)
                    .message(TOPIC_MESSAGE_UPDATE)
                    .signedBy(PAYER, submitKey)
                    .payingWith(PAYER)
                    .via("innerTxnAfterUpdateSecond")
                    .batchKey(BATCH_OPERATOR);

            // update topic inner transaction
            final var updateTopic = updateTopic(TOPIC_ID)
                    .withConsensusCustomFee(fixedConsensusHbarFee(HBAR_FEE * 2, HBAR_COLLECTOR))
                    .withConsensusCustomFee(fixedConsensusHtsFee(HTS_FEE, DENOM_TOKEN, HTS_COLLECTOR))
                    .payingWith(PAYER)
                    .signedBy(PAYER, adminKey, feeScheduleKey)
                    .via("updateTopicTxn")
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    createAccountsAndKeys(),
                    createMutableTopicWithSubmitKeyAndHBARFixedFee(adminKey, submitKey, feeScheduleKey, TOPIC_ID),
                    createMutableTopicWithSubmitKeyAndHTSFixedFee(adminKey, submitKey, feeScheduleKey, TOPIC_ID_SECOND),
                    atomicBatch(
                                    submitMessageToFirstTopicBeforeUpdate,
                                    submitMessageToSecondTopicBeforeUpdate,
                                    updateTopic,
                                    submitMessageToFirstTopicAfterUpdate,
                                    submitMessageToSecondTopicAfterUpdate)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(SUCCESS),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // confirm topic is updated
                    getTopicInfo(TOPIC_ID)
                            .hasMemo(TEST_MEMO)
                            .hasAdminKey(adminKey)
                            .hasSubmitKey(submitKey)
                            .hasFeeScheduleKey(feeScheduleKey)
                            .hasCustomFeeSize(2),

                    // confirm collector accounts balances
                    getAccountBalance(HBAR_COLLECTOR).hasTinyBars(HBAR_FEE * 3),
                    getAccountBalance(HTS_COLLECTOR).hasTokenBalance(DENOM_TOKEN, HTS_FEE * 3),
                    getAccountBalance(PAYER).hasTokenBalance(DENOM_TOKEN, denomTokenPayerInitialSupply - HTS_FEE * 3),
                    getAccountBalance(TREASURY_FOR_CUSTOM_FEE_TOKEN)
                            .hasTokenBalance(DENOM_TOKEN, denomTokenInitialSupply - denomTokenPayerInitialSupply)));
        }

        @HapiTest
        public Stream<DynamicTest> submitMessagesToTopicsWithUpdatedToHTSCustomFeesSuccessInBatch() {

            // submit message to topic inner transactions
            final var submitMessageToFirstTopicBeforeUpdate = submitMessageTo(TOPIC_ID)
                    .message(TOPIC_MESSAGE)
                    .signedBy(PAYER, submitKey)
                    .payingWith(PAYER)
                    .via("innerTxnBeforeUpdateFirst")
                    .batchKey(BATCH_OPERATOR);

            final var submitMessageToFirstTopicAfterUpdate = submitMessageTo(TOPIC_ID)
                    .message(TOPIC_MESSAGE_UPDATE)
                    .signedBy(PAYER, submitKey)
                    .payingWith(PAYER)
                    .via("innerTxnAfterUpdateFirst")
                    .batchKey(BATCH_OPERATOR);

            // update topic inner transaction
            final var updateTopic = updateTopic(TOPIC_ID)
                    .withConsensusCustomFee(fixedConsensusHtsFee(HTS_FEE, DENOM_TOKEN, HTS_COLLECTOR))
                    .payingWith(PAYER)
                    .signedBy(PAYER, adminKey, feeScheduleKey)
                    .via("updateTopicTxn")
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    createAccountsAndKeys(),
                    createMutableTopicWithSubmitKeyAndHBARFixedFee(adminKey, submitKey, feeScheduleKey, TOPIC_ID),
                    atomicBatch(
                                    submitMessageToFirstTopicBeforeUpdate,
                                    updateTopic,
                                    submitMessageToFirstTopicAfterUpdate)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(SUCCESS),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // confirm topic is updated
                    getTopicInfo(TOPIC_ID)
                            .hasMemo(TEST_MEMO)
                            .hasAdminKey(adminKey)
                            .hasSubmitKey(submitKey)
                            .hasFeeScheduleKey(feeScheduleKey)
                            .hasCustomFeeSize(1),

                    // confirm collector accounts balances
                    getAccountBalance(HBAR_COLLECTOR).hasTinyBars(HBAR_FEE),
                    getAccountBalance(HTS_COLLECTOR).hasTokenBalance(DENOM_TOKEN, HTS_FEE),
                    getAccountBalance(PAYER).hasTokenBalance(DENOM_TOKEN, denomTokenPayerInitialSupply - HTS_FEE),
                    getAccountBalance(TREASURY_FOR_CUSTOM_FEE_TOKEN)
                            .hasTokenBalance(DENOM_TOKEN, denomTokenInitialSupply - denomTokenPayerInitialSupply)));
        }

        @HapiTest
        public Stream<DynamicTest> submitMessagesToTopicsWithUpdatedToHBARCustomFeesSuccessInBatch() {

            // submit message to topic inner transactions
            final var submitMessageToSecondTopicBeforeUpdate = submitMessageTo(TOPIC_ID_SECOND)
                    .message(TOPIC_MESSAGE)
                    .signedBy(PAYER, submitKey)
                    .payingWith(PAYER)
                    .via("innerTxnBeforeUpdateSecond")
                    .batchKey(BATCH_OPERATOR);

            final var submitMessageToSecondTopicAfterUpdate = submitMessageTo(TOPIC_ID_SECOND)
                    .message(TOPIC_MESSAGE_UPDATE)
                    .signedBy(PAYER, submitKey)
                    .payingWith(PAYER)
                    .via("innerTxnAfterUpdateSecond")
                    .batchKey(BATCH_OPERATOR);

            // update topic inner transaction
            final var updateTopic = updateTopic(TOPIC_ID_SECOND)
                    .withConsensusCustomFee(fixedConsensusHbarFee(HBAR_FEE, HBAR_COLLECTOR))
                    .payingWith(PAYER)
                    .signedBy(PAYER, adminKey, feeScheduleKey)
                    .via("updateTopicTxn")
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    createAccountsAndKeys(),
                    createMutableTopicWithSubmitKeyAndHTSFixedFee(adminKey, submitKey, feeScheduleKey, TOPIC_ID_SECOND),
                    atomicBatch(
                                    submitMessageToSecondTopicBeforeUpdate,
                                    updateTopic,
                                    submitMessageToSecondTopicAfterUpdate)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(SUCCESS),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // confirm topic is updated
                    getTopicInfo(TOPIC_ID_SECOND)
                            .hasMemo(TEST_MEMO)
                            .hasAdminKey(adminKey)
                            .hasSubmitKey(submitKey)
                            .hasFeeScheduleKey(feeScheduleKey)
                            .hasCustomFeeSize(1),

                    // confirm collector accounts balances
                    getAccountBalance(HBAR_COLLECTOR).hasTinyBars(HBAR_FEE),
                    getAccountBalance(HTS_COLLECTOR).hasTokenBalance(DENOM_TOKEN, HTS_FEE),
                    getAccountBalance(PAYER).hasTokenBalance(DENOM_TOKEN, denomTokenPayerInitialSupply - HTS_FEE),
                    getAccountBalance(TREASURY_FOR_CUSTOM_FEE_TOKEN)
                            .hasTokenBalance(DENOM_TOKEN, denomTokenInitialSupply - denomTokenPayerInitialSupply)));
        }

        @HapiTest
        public Stream<DynamicTest> submitMessagesToTopicsWithRemovedCustomFeesSuccessInBatch() {

            // submit message to topic inner transactions
            final var submitMessageToFirstTopicBeforeUpdate = submitMessageTo(TOPIC_ID)
                    .message(TOPIC_MESSAGE)
                    .signedBy(PAYER, submitKey)
                    .payingWith(PAYER)
                    .via("innerTxnBeforeUpdateFirst")
                    .batchKey(BATCH_OPERATOR);

            final var submitMessageToFirstTopicAfterUpdate = submitMessageTo(TOPIC_ID)
                    .message(TOPIC_MESSAGE_UPDATE)
                    .signedBy(PAYER, submitKey)
                    .payingWith(PAYER)
                    .via("innerTxnAfterUpdateFirst")
                    .batchKey(BATCH_OPERATOR);

            // update topic inner transaction
            final var updateTopic = updateTopic(TOPIC_ID)
                    .withEmptyCustomFee()
                    .payingWith(PAYER)
                    .signedBy(PAYER, adminKey, feeScheduleKey)
                    .via("updateTopicTxn")
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    createAccountsAndKeys(),
                    createMutableTopicWithSubmitKeyAndHBARFixedFee(adminKey, submitKey, feeScheduleKey, TOPIC_ID),
                    atomicBatch(
                                    submitMessageToFirstTopicBeforeUpdate,
                                    updateTopic,
                                    submitMessageToFirstTopicAfterUpdate)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(SUCCESS),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // confirm topic is updated
                    getTopicInfo(TOPIC_ID)
                            .hasMemo(TEST_MEMO)
                            .hasAdminKey(adminKey)
                            .hasSubmitKey(submitKey)
                            .hasFeeScheduleKey(feeScheduleKey)
                            .hasNoCustomFee(),

                    // confirm collector accounts balances
                    getAccountBalance(HBAR_COLLECTOR).hasTinyBars(HBAR_FEE)));
        }

        @HapiTest
        public Stream<DynamicTest> submitMessagesToMultipleTopicsWithCustomFeesUpdatesAddAndRemoveFeesSuccessInBatch() {

            // submit message to topic inner transactions
            final var submitMessageToFirstTopicBeforeFirstUpdate = submitMessageTo(TOPIC_ID)
                    .message(TOPIC_MESSAGE)
                    .signedBy(PAYER, submitKey)
                    .payingWith(PAYER)
                    .via("innerTxnBeforeUpdateFirst")
                    .batchKey(BATCH_OPERATOR);

            final var submitMessageToFirstTopicAfterFirstUpdate = submitMessageTo(TOPIC_ID)
                    .message(TOPIC_MESSAGE_UPDATE)
                    .signedBy(PAYER, submitKey)
                    .payingWith(PAYER)
                    .via("innerTxnAfterUpdateFirst")
                    .batchKey(BATCH_OPERATOR);

            final var submitMessageToFirstTopicAfterSecondUpdate = submitMessageTo(TOPIC_ID)
                    .message(TOPIC_MESSAGE_UPDATE)
                    .signedBy(PAYER, submitKey)
                    .payingWith(PAYER)
                    .via("innerTxnAfterUpdateSecond")
                    .batchKey(BATCH_OPERATOR);

            // update topic inner transaction
            final var updateTopicFirst = updateTopic(TOPIC_ID)
                    .withConsensusCustomFee(fixedConsensusHbarFee(HBAR_FEE * 2, HBAR_COLLECTOR))
                    .withConsensusCustomFee(fixedConsensusHtsFee(HTS_FEE, DENOM_TOKEN, HTS_COLLECTOR))
                    .payingWith(PAYER)
                    .signedBy(PAYER, adminKey, feeScheduleKey)
                    .via("updateTopicFirstTxn")
                    .batchKey(BATCH_OPERATOR);

            final var updateTopicSecond = updateTopic(TOPIC_ID)
                    .withConsensusCustomFee(fixedConsensusHtsFee(HTS_FEE * 2, DENOM_TOKEN, HTS_COLLECTOR))
                    .payingWith(PAYER)
                    .signedBy(PAYER, adminKey, feeScheduleKey)
                    .via("updateTopicSecondTxn")
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    createAccountsAndKeys(),
                    createMutableTopicWithSubmitKeyAndHBARFixedFee(adminKey, submitKey, feeScheduleKey, TOPIC_ID),
                    atomicBatch(
                                    submitMessageToFirstTopicBeforeFirstUpdate,
                                    updateTopicFirst,
                                    submitMessageToFirstTopicAfterFirstUpdate,
                                    updateTopicSecond,
                                    submitMessageToFirstTopicAfterSecondUpdate)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(SUCCESS),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // confirm topic is updated
                    getTopicInfo(TOPIC_ID)
                            .hasMemo(TEST_MEMO)
                            .hasAdminKey(adminKey)
                            .hasSubmitKey(submitKey)
                            .hasFeeScheduleKey(feeScheduleKey)
                            .hasCustomFeeSize(1),

                    // confirm collector accounts balances
                    getAccountBalance(HBAR_COLLECTOR).hasTinyBars(HBAR_FEE * 3),
                    getAccountBalance(HTS_COLLECTOR).hasTokenBalance(DENOM_TOKEN, HTS_FEE * 3),
                    getAccountBalance(PAYER).hasTokenBalance(DENOM_TOKEN, denomTokenPayerInitialSupply - HTS_FEE * 3),
                    getAccountBalance(TREASURY_FOR_CUSTOM_FEE_TOKEN)
                            .hasTokenBalance(DENOM_TOKEN, denomTokenInitialSupply - denomTokenPayerInitialSupply)));
        }

        @HapiTest
        public Stream<DynamicTest> topicCustomFeesUpdatesNotSignedByFeeScheduleKeyFailsInBatch() {

            // submit message to topic inner transactions
            final var submitMessageToTopicBeforeUpdate = submitMessageTo(TOPIC_ID)
                    .message(TOPIC_MESSAGE)
                    .signedBy(PAYER, submitKey)
                    .payingWith(PAYER)
                    .via("innerTxnBeforeUpdate")
                    .batchKey(BATCH_OPERATOR);

            final var submitMessageToTopicAfterUpdate = submitMessageTo(TOPIC_ID)
                    .message(TOPIC_MESSAGE_UPDATE)
                    .signedBy(PAYER, submitKey)
                    .payingWith(PAYER)
                    .via("innerTxnAfterUpdate")
                    .batchKey(BATCH_OPERATOR);

            // update topic inner transaction
            final var updateTopic = updateTopic(TOPIC_ID)
                    .withConsensusCustomFee(fixedConsensusHbarFee(HBAR_FEE * 2, HBAR_COLLECTOR))
                    .withConsensusCustomFee(fixedConsensusHtsFee(HTS_FEE, DENOM_TOKEN, HTS_COLLECTOR))
                    .topicMemo(TEST_MEMO_UPDATE)
                    .payingWith(PAYER)
                    .signedBy(PAYER, adminKey)
                    .via("updateTopicTxn")
                    .batchKey(BATCH_OPERATOR)
                    .hasKnownStatus(INVALID_SIGNATURE);

            return hapiTest(flattened(
                    createAccountsAndKeys(),
                    createMutableTopicWithSubmitKeyAndHBARFixedFee(adminKey, submitKey, feeScheduleKey, TOPIC_ID),
                    atomicBatch(submitMessageToTopicBeforeUpdate, updateTopic, submitMessageToTopicAfterUpdate)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // confirm topic is updated
                    getTopicInfo(TOPIC_ID)
                            .hasMemo(TEST_MEMO)
                            .hasAdminKey(adminKey)
                            .hasSubmitKey(submitKey)
                            .hasFeeScheduleKey(feeScheduleKey)
                            .hasCustomFeeSize(1),

                    // confirm collector accounts balances
                    getAccountBalance(HBAR_COLLECTOR).hasTinyBars(0),
                    getAccountBalance(HTS_COLLECTOR).hasTokenBalance(DENOM_TOKEN, 0),
                    getAccountBalance(PAYER).hasTokenBalance(DENOM_TOKEN, denomTokenPayerInitialSupply),
                    getAccountBalance(TREASURY_FOR_CUSTOM_FEE_TOKEN)
                            .hasTokenBalance(DENOM_TOKEN, denomTokenInitialSupply - denomTokenPayerInitialSupply)));
        }

        @HapiTest
        public Stream<DynamicTest> topicWithMoreThanTenCustomFeesFailsInBatch() {

            // submit message to topic inner transactions
            final var submitMessageToTopicBeforeUpdate = submitMessageTo(TOPIC_ID)
                    .message(TOPIC_MESSAGE)
                    .signedBy(PAYER, submitKey)
                    .payingWith(PAYER)
                    .via("innerTxnBeforeUpdate")
                    .batchKey(BATCH_OPERATOR);

            final var submitMessageToTopicAfterUpdate = submitMessageTo(TOPIC_ID)
                    .message(TOPIC_MESSAGE_UPDATE)
                    .signedBy(PAYER, submitKey)
                    .payingWith(PAYER)
                    .via("innerTxnAfterUpdate")
                    .batchKey(BATCH_OPERATOR);

            // update topic inner transaction
            final var updateTopic = updateTopic(TOPIC_ID)
                    .withConsensusCustomFee(fixedConsensusHbarFee(HBAR_FEE * 2, HBAR_COLLECTOR))
                    .withConsensusCustomFee(fixedConsensusHtsFee(HTS_FEE, DENOM_TOKEN, HTS_COLLECTOR))
                    .withConsensusCustomFee(fixedConsensusHtsFee(HTS_FEE, DENOM_TOKEN_SECOND, HTS_COLLECTOR))
                    .withConsensusCustomFee(fixedConsensusHtsFee(HTS_FEE, DENOM_TOKEN_THIRD, HTS_COLLECTOR))
                    .withConsensusCustomFee(fixedConsensusHtsFee(HTS_FEE, DENOM_TOKEN_FOURTH, HTS_COLLECTOR))
                    .withConsensusCustomFee(fixedConsensusHtsFee(HTS_FEE, DENOM_TOKEN_FIFTH, HTS_COLLECTOR))
                    .withConsensusCustomFee(fixedConsensusHtsFee(HTS_FEE, DENOM_TOKEN_SIXTH, HTS_COLLECTOR))
                    .withConsensusCustomFee(fixedConsensusHtsFee(HTS_FEE, DENOM_TOKEN_SEVENTH, HTS_COLLECTOR))
                    .withConsensusCustomFee(fixedConsensusHtsFee(HTS_FEE, DENOM_TOKEN_EIGHTH, HTS_COLLECTOR))
                    .withConsensusCustomFee(fixedConsensusHtsFee(HTS_FEE, DENOM_TOKEN_NINTH, HTS_COLLECTOR))
                    .withConsensusCustomFee(fixedConsensusHtsFee(HTS_FEE, DENOM_TOKEN_TENTH, HTS_COLLECTOR))
                    .withConsensusCustomFee(fixedConsensusHtsFee(HTS_FEE, DENOM_TOKEN_ELEVENTH, HTS_COLLECTOR))
                    .topicMemo(TEST_MEMO_UPDATE)
                    .payingWith(PAYER)
                    .signedBy(PAYER, adminKey, feeScheduleKey)
                    .via("updateTopicTxn")
                    .batchKey(BATCH_OPERATOR)
                    .hasKnownStatus(CUSTOM_FEES_LIST_TOO_LONG);

            return hapiTest(flattened(
                    createAccountsAndKeys(),
                    createMutableTopicWithSubmitKeyAndHBARFixedFee(adminKey, submitKey, feeScheduleKey, TOPIC_ID),
                    atomicBatch(submitMessageToTopicBeforeUpdate, updateTopic, submitMessageToTopicAfterUpdate)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // confirm topic is updated
                    getTopicInfo(TOPIC_ID)
                            .hasMemo(TEST_MEMO)
                            .hasAdminKey(adminKey)
                            .hasSubmitKey(submitKey)
                            .hasFeeScheduleKey(feeScheduleKey)
                            .hasCustomFeeSize(1),

                    // confirm collector accounts balances
                    getAccountBalance(HBAR_COLLECTOR).hasTinyBars(0),
                    getAccountBalance(HTS_COLLECTOR).hasTokenBalance(DENOM_TOKEN, 0),
                    getAccountBalance(PAYER).hasTokenBalance(DENOM_TOKEN, denomTokenPayerInitialSupply),
                    getAccountBalance(TREASURY_FOR_CUSTOM_FEE_TOKEN)
                            .hasTokenBalance(DENOM_TOKEN, denomTokenInitialSupply - denomTokenPayerInitialSupply)));
        }
    }

    @Nested
    @DisplayName("Atomic Batch Consensus Service End-to-End Tests with Custom Fees Exempt List")
    class AtomicBatchConsensusServiceWithCustomFeesExemptList {
        @HapiTest
        public Stream<DynamicTest> submitMessagesToTopicsWithCustomFeesExemptListSuccessInBatch() {

            // submit message to topic inner transactions
            final var submitFirstMessageToTopicExempt = submitMessageTo(TOPIC_ID)
                    .message(TOPIC_MESSAGE)
                    .signedBy(PAYER_EXEMPT_FEES, submitKey)
                    .payingWith(PAYER_EXEMPT_FEES)
                    .via("innerTxnSubmitFirst")
                    .batchKey(BATCH_OPERATOR);

            final var submitSecondMessageToTopicExempt = submitMessageTo(TOPIC_ID)
                    .message(TOPIC_MESSAGE_UPDATE)
                    .signedBy(PAYER_EXEMPT_FEES, submitKey)
                    .payingWith(PAYER_EXEMPT_FEES)
                    .via("innerTxnSubmitSecond")
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    createAccountsAndKeys(),
                    createMutableTopicWithHBARFixedFeeAndKeyExemptList(
                            adminKey, submitKey, feeScheduleKey, TOPIC_ID, PAYER_EXEMPT_FEES),
                    atomicBatch(submitFirstMessageToTopicExempt, submitSecondMessageToTopicExempt)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(SUCCESS),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // confirm topic is updated
                    getTopicInfo(TOPIC_ID)
                            .hasMemo(TEST_MEMO)
                            .hasAdminKey(adminKey)
                            .hasSubmitKey(submitKey)
                            .hasFeeScheduleKey(feeScheduleKey)
                            .hasCustomFeeSize(1),

                    // confirm collector accounts balances
                    getAccountBalance(HBAR_COLLECTOR).hasTinyBars(0)));
        }

        @HapiTest
        public Stream<DynamicTest> submitMessagesToTopicsWithCustomFeesExemptAndNonExemptPayersSuccessInBatch() {

            // submit message to topic inner transactions
            final var submitFirstMessageToTopicExempt = submitMessageTo(TOPIC_ID)
                    .message(TOPIC_MESSAGE)
                    .signedBy(PAYER_EXEMPT_FEES, submitKey)
                    .payingWith(PAYER_EXEMPT_FEES)
                    .via("innerTxnSubmitFirst")
                    .batchKey(BATCH_OPERATOR);

            final var submitSecondMessageToTopicNotExempt = submitMessageTo(TOPIC_ID)
                    .message(TOPIC_MESSAGE_UPDATE)
                    .signedBy(PAYER, submitKey)
                    .payingWith(PAYER)
                    .via("innerTxnSubmitSecond")
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    createAccountsAndKeys(),
                    createMutableTopicWithHBARFixedFeeAndKeyExemptList(
                            adminKey, submitKey, feeScheduleKey, TOPIC_ID, PAYER_EXEMPT_FEES),
                    atomicBatch(submitFirstMessageToTopicExempt, submitSecondMessageToTopicNotExempt)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(SUCCESS),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // confirm topic is updated
                    getTopicInfo(TOPIC_ID)
                            .hasMemo(TEST_MEMO)
                            .hasAdminKey(adminKey)
                            .hasSubmitKey(submitKey)
                            .hasFeeScheduleKey(feeScheduleKey)
                            .hasCustomFeeSize(1),

                    // confirm collector accounts balances
                    getAccountBalance(HBAR_COLLECTOR).hasTinyBars(HBAR_FEE)));
        }

        @HapiTest
        public Stream<DynamicTest>
                submitMessagesToTopicsWithCustomFeesUpdateFeesExemptListAddExemptPayersListSuccessInBatch() {

            // submit message to topic inner transactions
            final var submitFirstMessageToTopicBeforeExempt = submitMessageTo(TOPIC_ID)
                    .message(TOPIC_MESSAGE)
                    .signedBy(PAYER_EXEMPT_FEES, submitKey)
                    .payingWith(PAYER_EXEMPT_FEES)
                    .via("innerTxnSubmitFirst")
                    .batchKey(BATCH_OPERATOR);

            final var submitSecondMessageToTopicAfterExempt = submitMessageTo(TOPIC_ID)
                    .message(TOPIC_MESSAGE_UPDATE)
                    .signedBy(PAYER_EXEMPT_FEES, submitKey)
                    .payingWith(PAYER_EXEMPT_FEES)
                    .via("innerTxnSubmitSecond")
                    .batchKey(BATCH_OPERATOR);

            // update topic inner transaction
            final var updateTopic = updateTopic(TOPIC_ID)
                    .feeExemptKeys(PAYER_EXEMPT_FEES)
                    .topicMemo(TEST_MEMO_UPDATE)
                    .payingWith(PAYER_EXEMPT_FEES)
                    .signedBy(PAYER_EXEMPT_FEES, adminKey)
                    .via("updateTopicTxn")
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    createAccountsAndKeys(),
                    createMutableTopicWithHBARFixedFeeAndKeyExemptList(adminKey, submitKey, feeScheduleKey, TOPIC_ID),
                    atomicBatch(
                                    submitFirstMessageToTopicBeforeExempt,
                                    updateTopic,
                                    submitSecondMessageToTopicAfterExempt)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(SUCCESS),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // confirm topic is updated
                    getTopicInfo(TOPIC_ID)
                            .hasMemo(TEST_MEMO_UPDATE)
                            .hasAdminKey(adminKey)
                            .hasSubmitKey(submitKey)
                            .hasFeeScheduleKey(feeScheduleKey)
                            .hasFeeExemptKeys(List.of(PAYER_EXEMPT_FEES))
                            .hasCustomFeeSize(1),

                    // confirm collector accounts balances
                    getAccountBalance(HBAR_COLLECTOR).hasTinyBars(HBAR_FEE)));
        }

        @HapiTest
        public Stream<DynamicTest>
                submitMessagesToTopicsWithCustomFeesUpdateFeesExemptListRemoveExemptPayersListSuccessInBatch() {

            // submit message to topic inner transactions
            final var submitFirstMessageToTopicBeforeRemoveExempt = submitMessageTo(TOPIC_ID)
                    .message(TOPIC_MESSAGE)
                    .signedBy(PAYER_EXEMPT_FEES, submitKey)
                    .payingWith(PAYER_EXEMPT_FEES)
                    .via("innerTxnSubmitFirst")
                    .batchKey(BATCH_OPERATOR);

            final var submitSecondMessageToTopicAfterRemoveExempt = submitMessageTo(TOPIC_ID)
                    .message(TOPIC_MESSAGE_UPDATE)
                    .signedBy(PAYER_EXEMPT_FEES, submitKey)
                    .payingWith(PAYER_EXEMPT_FEES)
                    .via("innerTxnSubmitSecond")
                    .batchKey(BATCH_OPERATOR);

            // update topic inner transaction
            final var updateTopic = updateTopic(TOPIC_ID)
                    .feeExemptKeys()
                    .topicMemo(TEST_MEMO_UPDATE)
                    .payingWith(PAYER_EXEMPT_FEES)
                    .signedBy(PAYER_EXEMPT_FEES, adminKey)
                    .via("updateTopicTxn")
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    createAccountsAndKeys(),
                    createMutableTopicWithHBARFixedFeeAndKeyExemptList(
                            adminKey, submitKey, feeScheduleKey, TOPIC_ID, PAYER_EXEMPT_FEES),
                    atomicBatch(
                                    submitFirstMessageToTopicBeforeRemoveExempt,
                                    updateTopic,
                                    submitSecondMessageToTopicAfterRemoveExempt)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(SUCCESS),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // confirm topic is updated
                    getTopicInfo(TOPIC_ID)
                            .hasMemo(TEST_MEMO_UPDATE)
                            .hasAdminKey(adminKey)
                            .hasSubmitKey(submitKey)
                            .hasFeeScheduleKey(feeScheduleKey)
                            .hasEmptyFeeExemptKeyList()
                            .hasCustomFeeSize(1),

                    // confirm collector accounts balances
                    getAccountBalance(HBAR_COLLECTOR).hasTinyBars(HBAR_FEE)));
        }

        @HapiTest
        public Stream<DynamicTest> submitMessagesToTopicsWithCustomFeesExemptListWithMoreThanTenPayersFailsInBatch() {

            // submit message to topic inner transactions
            final var submitFirstMessageToTopicBeforeUpdateExempt = submitMessageTo(TOPIC_ID)
                    .message(TOPIC_MESSAGE)
                    .signedBy(PAYER, submitKey)
                    .payingWith(PAYER)
                    .via("innerTxnSubmitFirst")
                    .batchKey(BATCH_OPERATOR);

            final var submitSecondMessageToTopicAfterUpdateExempt = submitMessageTo(TOPIC_ID)
                    .message(TOPIC_MESSAGE_UPDATE)
                    .signedBy(PAYER, submitKey)
                    .payingWith(PAYER)
                    .via("innerTxnSubmitSecond")
                    .batchKey(BATCH_OPERATOR);

            // update topic inner transaction
            final var updateTopic = updateTopic(TOPIC_ID)
                    .feeExemptKeys(
                            PAYER,
                            PAYER_INSUFFICIENT_BALANCE,
                            PAYER_EXEMPT_FEES,
                            PAYER_EXEMPT_FEES_SECOND,
                            PAYER_EXEMPT_FEES_THIRD,
                            AUTO_RENEW_ACCOUNT,
                            AUTO_RENEW_ACCOUNT_UPDATE,
                            HBAR_COLLECTOR,
                            HTS_COLLECTOR,
                            TREASURY_FOR_CUSTOM_FEE_TOKEN,
                            BATCH_OPERATOR)
                    .topicMemo(TEST_MEMO_UPDATE)
                    .payingWith(PAYER_EXEMPT_FEES)
                    .signedBy(PAYER_EXEMPT_FEES, adminKey)
                    .via("updateTopicTxn")
                    .batchKey(BATCH_OPERATOR)
                    .hasKnownStatus(MAX_ENTRIES_FOR_FEE_EXEMPT_KEY_LIST_EXCEEDED);

            return hapiTest(flattened(
                    createAccountsAndKeys(),
                    createMutableTopicWithHBARFixedFeeAndKeyExemptList(adminKey, submitKey, feeScheduleKey, TOPIC_ID),
                    atomicBatch(
                                    submitFirstMessageToTopicBeforeUpdateExempt,
                                    updateTopic,
                                    submitSecondMessageToTopicAfterUpdateExempt)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // confirm topic is updated
                    getTopicInfo(TOPIC_ID)
                            .hasMemo(TEST_MEMO)
                            .hasAdminKey(adminKey)
                            .hasSubmitKey(submitKey)
                            .hasFeeScheduleKey(feeScheduleKey)
                            .hasEmptyFeeExemptKeyList()
                            .hasCustomFeeSize(1),

                    // confirm collector accounts balances
                    getAccountBalance(HBAR_COLLECTOR).hasTinyBars(0)));
        }
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

    private HapiTopicCreate createMutableTopicWithSubmitKeyAndHBARFixedFee(
            String adminKey, String submitKey, String feeScheduleKey, String topicId) {
        return new HapiTopicCreate(topicId)
                .adminKeyName(adminKey)
                .feeScheduleKeyName(feeScheduleKey)
                .submitKeyName(submitKey)
                .topicMemo(TEST_MEMO)
                .withConsensusCustomFee(fixedConsensusHbarFee(HBAR_FEE, HBAR_COLLECTOR))
                .via("createTopicTxn");
    }

    private HapiTopicCreate createMutableTopicWithHBARFixedFeeAndKeyExemptList(
            String adminKey, String submitKey, String feeScheduleKey, String topicId, String... feeExemptKeys) {
        return new HapiTopicCreate(topicId)
                .adminKeyName(adminKey)
                .feeScheduleKeyName(feeScheduleKey)
                .submitKeyName(submitKey)
                .feeExemptKeys(feeExemptKeys)
                .topicMemo(TEST_MEMO)
                .withConsensusCustomFee(fixedConsensusHbarFee(HBAR_FEE, HBAR_COLLECTOR))
                .via("createTopicTxn");
    }

    private HapiTopicCreate createMutableTopicWithSubmitKeyAndHTSFixedFee(
            String adminKey, String submitKey, String feeScheduleKey, String topicId) {
        return new HapiTopicCreate(topicId)
                .adminKeyName(adminKey)
                .feeScheduleKeyName(feeScheduleKey)
                .submitKeyName(submitKey)
                .topicMemo(TEST_MEMO)
                .withConsensusCustomFee(fixedConsensusHtsFee(HTS_FEE, DENOM_TOKEN, HTS_COLLECTOR))
                .via("createTopicTxn");
    }

    private List<SpecOperation> createAccountsAndKeys() {
        return List.of(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_HBAR),
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(PAYER_INSUFFICIENT_BALANCE).balance(ONE_HBAR),
                cryptoCreate(PAYER_EXEMPT_FEES).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(PAYER_EXEMPT_FEES_SECOND).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(PAYER_EXEMPT_FEES_THIRD).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(AUTO_RENEW_ACCOUNT).balance(ONE_HBAR),
                cryptoCreate(AUTO_RENEW_ACCOUNT_UPDATE).balance(ONE_HBAR),
                cryptoCreate(HBAR_COLLECTOR).balance(0L),
                cryptoCreate(HTS_COLLECTOR).balance(0L),
                cryptoCreate(TREASURY_FOR_CUSTOM_FEE_TOKEN).balance(0L),
                tokenCreate(DENOM_TOKEN).treasury(TREASURY_FOR_CUSTOM_FEE_TOKEN).initialSupply(denomTokenInitialSupply),
                tokenCreate(DENOM_TOKEN_SECOND)
                        .treasury(TREASURY_FOR_CUSTOM_FEE_TOKEN)
                        .initialSupply(denomTokenInitialSupply),
                tokenCreate(DENOM_TOKEN_THIRD)
                        .treasury(TREASURY_FOR_CUSTOM_FEE_TOKEN)
                        .initialSupply(denomTokenInitialSupply),
                tokenCreate(DENOM_TOKEN_FOURTH)
                        .treasury(TREASURY_FOR_CUSTOM_FEE_TOKEN)
                        .initialSupply(denomTokenInitialSupply),
                tokenCreate(DENOM_TOKEN_FIFTH)
                        .treasury(TREASURY_FOR_CUSTOM_FEE_TOKEN)
                        .initialSupply(denomTokenInitialSupply),
                tokenCreate(DENOM_TOKEN_SIXTH)
                        .treasury(TREASURY_FOR_CUSTOM_FEE_TOKEN)
                        .initialSupply(denomTokenInitialSupply),
                tokenCreate(DENOM_TOKEN_SEVENTH)
                        .treasury(TREASURY_FOR_CUSTOM_FEE_TOKEN)
                        .initialSupply(denomTokenInitialSupply),
                tokenCreate(DENOM_TOKEN_EIGHTH)
                        .treasury(TREASURY_FOR_CUSTOM_FEE_TOKEN)
                        .initialSupply(denomTokenInitialSupply),
                tokenCreate(DENOM_TOKEN_NINTH)
                        .treasury(TREASURY_FOR_CUSTOM_FEE_TOKEN)
                        .initialSupply(denomTokenInitialSupply),
                tokenCreate(DENOM_TOKEN_TENTH)
                        .treasury(TREASURY_FOR_CUSTOM_FEE_TOKEN)
                        .initialSupply(denomTokenInitialSupply),
                tokenCreate(DENOM_TOKEN_ELEVENTH)
                        .treasury(TREASURY_FOR_CUSTOM_FEE_TOKEN)
                        .initialSupply(denomTokenInitialSupply),
                tokenAssociate(HTS_COLLECTOR, DENOM_TOKEN),
                tokenAssociate(PAYER, DENOM_TOKEN),
                cryptoTransfer(moving(10, DENOM_TOKEN).between(TREASURY_FOR_CUSTOM_FEE_TOKEN, PAYER)),
                newKeyNamed(submitKey),
                newKeyNamed(newSubmitKey),
                newKeyNamed(adminKey),
                newKeyNamed(newAdminKey),
                newKeyNamed(feeScheduleKey));
    }
}
