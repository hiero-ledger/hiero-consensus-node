// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.consensus.impl.test.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.BAD_ENCODING;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_AUTORENEW_ACCOUNT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOPIC_ID;
import static com.hedera.node.app.service.consensus.impl.test.handlers.ConsensusTestUtils.A_NONNULL_KEY;
import static com.hedera.node.app.service.consensus.impl.test.handlers.ConsensusTestUtils.EMPTY_KEYLIST;
import static com.hedera.node.app.service.consensus.impl.test.handlers.ConsensusTestUtils.EMPTY_THRESHOLD_KEY;
import static com.hedera.node.app.spi.fixtures.Assertions.assertThrowsPreCheck;
import static com.hedera.node.app.spi.validation.ExpiryMeta.NA;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.doThrow;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Duration;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TopicID;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.consensus.ConsensusUpdateTopicTransactionBody;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.consensus.ReadableTopicStore;
import com.hedera.node.app.service.consensus.impl.handlers.ConsensusUpdateTopicHandler;
import com.hedera.node.app.service.consensus.impl.validators.ConsensusCustomFeesValidator;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.spi.fixtures.workflows.FakePreHandleContext;
import com.hedera.node.app.spi.validation.AttributeValidator;
import com.hedera.node.app.spi.validation.ExpiryMeta;
import com.hedera.node.app.spi.validation.ExpiryValidator;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ConsensusUpdateTopicHandlerTest extends ConsensusTestBase {
    private final ConsensusUpdateTopicTransactionBody.Builder OP_BUILDER =
            ConsensusUpdateTopicTransactionBody.newBuilder();

    private final ExpiryMeta currentExpiryMeta = new ExpiryMeta(expirationTime, autoRenewSecs, autoRenewId);

    @Mock
    private PureChecksContext pureChecksContext;

    @Mock
    private ReadableAccountStore accountStore;

    @Mock
    private Account account;

    @Mock
    private Account autoRenewAccount;

    @Mock
    private ExpiryValidator expiryValidator;

    @Mock
    private AttributeValidator attributeValidator;

    @Mock
    private ConsensusCustomFeesValidator customFeesValidator;

    private ConsensusUpdateTopicHandler subject;

    @BeforeEach
    void setUp() {
        subject = new ConsensusUpdateTopicHandler(customFeesValidator);
    }

    @Test
    @DisplayName("Update a missing topic ID fails")
    void rejectsMissingTopic() {
        final var txBody =
                TransactionBody.newBuilder().consensusUpdateTopic(OP_BUILDER).build();
        given(pureChecksContext.body()).willReturn(txBody);
        assertThrowsPreCheck(() -> subject.pureChecks(pureChecksContext), INVALID_TOPIC_ID);
    }

    @Test
    @DisplayName("No admin key to update memo fails")
    void rejectsNonExpiryMutationOfImmutableTopic() {
        givenValidTopic(AccountID.newBuilder().accountNum(0).build(), false, false);
        refreshStoresWithCurrentTopicInBothReadableAndWritable();

        final var txBody = TransactionBody.newBuilder()
                .consensusUpdateTopic(OP_BUILDER.topicID(topicId).memo("Please mind the vase"))
                .build();
        given(handleContext.body()).willReturn(txBody);

        // expect:
        assertFailsWith(ResponseCodeEnum.UNAUTHORIZED, () -> subject.handle(handleContext));
    }

    @Test
    @DisplayName("Invalid new admin key update fails")
    void validatesNewAdminKey() {
        givenValidTopic(AccountID.newBuilder().accountNum(0).build(), false);
        refreshStoresWithCurrentTopicInBothReadableAndWritable();

        final var txBody = TransactionBody.newBuilder()
                .consensusUpdateTopic(OP_BUILDER.topicID(topicId).adminKey(key))
                .build();
        given(handleContext.body()).willReturn(txBody);
        given(handleContext.attributeValidator()).willReturn(attributeValidator);
        willThrow(new HandleException(BAD_ENCODING)).given(attributeValidator).validateKey(key);

        // expect:
        assertFailsWith(BAD_ENCODING, () -> subject.handle(handleContext));
    }

    @Test
    @DisplayName("Update admin key as expected")
    void appliesNewAdminKey() {
        givenValidTopic(AccountID.newBuilder().accountNum(0).build(), false);
        refreshStoresWithCurrentTopicInBothReadableAndWritable();

        final var txBody = TransactionBody.newBuilder()
                .consensusUpdateTopic(OP_BUILDER.topicID(topicId).adminKey(anotherKey))
                .build();
        given(handleContext.body()).willReturn(txBody);
        given(handleContext.attributeValidator()).willReturn(attributeValidator);

        subject.handle(handleContext);

        final var newTopic = writableTopicState.get(topicId);
        assertEquals(anotherKey, newTopic.adminKey());
    }

    @Test
    @DisplayName("Delete admin key with Key.DEFAULT failed")
    void appliesDeleteAdminKeyWithDEFAULTKey() {
        givenValidTopic(null, false);
        refreshStoresWithCurrentTopicInBothReadableAndWritable();

        final var op = OP_BUILDER.topicID(topicId).adminKey(A_NONNULL_KEY).build();
        final var txn = TransactionBody.newBuilder().consensusUpdateTopic(op).build();
        given(handleContext.body()).willReturn(txn);
        given(handleContext.attributeValidator()).willReturn(attributeValidator);
        given(handleContext.body())
                .willReturn(
                        TransactionBody.newBuilder().consensusUpdateTopic(op).build());
        doThrow(new HandleException(BAD_ENCODING)).when(attributeValidator).validateKey(any());

        assertFailsWith(BAD_ENCODING, () -> subject.handle(handleContext));

        final var newTopic = writableTopicState.get(topicId);
        assertNotNull(newTopic.adminKey());
    }

    @Test
    @DisplayName("Delete admin key with empty KeyList succeeded")
    void appliesDeleteAdminKeyWithEmptyKeyList() {
        givenValidTopic(null, false);
        refreshStoresWithCurrentTopicInBothReadableAndWritable();

        final var op = OP_BUILDER.topicID(topicId).adminKey(EMPTY_KEYLIST).build();
        final var txn = TransactionBody.newBuilder().consensusUpdateTopic(op).build();
        given(handleContext.body()).willReturn(txn);
        given(handleContext.attributeValidator()).willReturn(attributeValidator);
        given(handleContext.body())
                .willReturn(
                        TransactionBody.newBuilder().consensusUpdateTopic(op).build());

        subject.handle(handleContext);

        final var newTopic = writableTopicState.get(topicId);
        assertNull(newTopic.adminKey());
    }

    @Test
    @DisplayName("Delete admin key with empty Threshold key failed")
    void appliesDeleteEmptyAdminKeyWithThresholdKeyList() {
        givenValidTopic(null, false);
        refreshStoresWithCurrentTopicInBothReadableAndWritable();

        final var op = OP_BUILDER.topicID(topicId).adminKey(EMPTY_THRESHOLD_KEY).build();
        final var txn = TransactionBody.newBuilder().consensusUpdateTopic(op).build();
        given(handleContext.body()).willReturn(txn);
        given(handleContext.attributeValidator()).willReturn(attributeValidator);
        doThrow(new HandleException(BAD_ENCODING)).when(attributeValidator).validateKey(any());
        given(handleContext.body())
                .willReturn(
                        TransactionBody.newBuilder().consensusUpdateTopic(op).build());

        assertFailsWith(BAD_ENCODING, () -> subject.handle(handleContext));

        final var newTopic = writableTopicState.get(topicId);
        assertNotNull(newTopic.adminKey());
    }

    @Test
    @DisplayName("Invalid new submit key update fails")
    void validatesNewSubmitKey() {
        givenValidTopic(AccountID.newBuilder().accountNum(0).build(), false);
        refreshStoresWithCurrentTopicInBothReadableAndWritable();

        final var op = OP_BUILDER.topicID(topicId).submitKey(key).build();
        final var txn = TransactionBody.newBuilder().consensusUpdateTopic(op).build();
        given(handleContext.body()).willReturn(txn);
        given(handleContext.attributeValidator()).willReturn(attributeValidator);
        willThrow(new HandleException(BAD_ENCODING)).given(attributeValidator).validateKey(key);

        // expect:
        assertFailsWith(BAD_ENCODING, () -> subject.handle(handleContext));
    }

    @Test
    @DisplayName("Update submit key as expected")
    void appliesNewSubmitKey() {
        givenValidTopic(AccountID.newBuilder().accountNum(0).build(), false);
        refreshStoresWithCurrentTopicInBothReadableAndWritable();

        final var op = OP_BUILDER.topicID(topicId).submitKey(anotherKey).build();
        final var txn = TransactionBody.newBuilder().consensusUpdateTopic(op).build();
        given(handleContext.body()).willReturn(txn);
        given(handleContext.attributeValidator()).willReturn(attributeValidator);

        subject.handle(handleContext);

        final var newTopic = writableTopicState.get(topicId);
        assertEquals(anotherKey, newTopic.submitKey());
    }

    @Test
    @DisplayName("Too long memo update fails")
    void validatesNewMemo() {
        givenValidTopic(AccountID.newBuilder().accountNum(0).build(), false);
        refreshStoresWithCurrentTopicInBothReadableAndWritable();

        final var op = OP_BUILDER.topicID(topicId).memo("Please mind the vase").build();
        final var txn = TransactionBody.newBuilder().consensusUpdateTopic(op).build();
        given(handleContext.body()).willReturn(txn);
        given(handleContext.attributeValidator()).willReturn(attributeValidator);
        willThrow(new HandleException(ResponseCodeEnum.MEMO_TOO_LONG))
                .given(attributeValidator)
                .validateMemo(op.memo());

        // expect:
        assertFailsWith(ResponseCodeEnum.MEMO_TOO_LONG, () -> subject.handle(handleContext));
    }

    @Test
    @DisplayName("Update memo as expected")
    void appliesNewMemo() {
        final var newMemo = "Please mind the vase";
        givenValidTopic(AccountID.newBuilder().accountNum(0).build(), false);
        refreshStoresWithCurrentTopicInBothReadableAndWritable();

        final var op = OP_BUILDER.topicID(topicId).memo(newMemo).build();
        final var txn = TransactionBody.newBuilder().consensusUpdateTopic(op).build();
        given(handleContext.body()).willReturn(txn);
        given(handleContext.attributeValidator()).willReturn(attributeValidator);
        subject.handle(handleContext);

        final var newTopic = writableTopicState.get(topicId);
        assertEquals(newMemo, newTopic.memo());
    }

    @Test
    @DisplayName("ExpiryMeta validation fails")
    void validatesNewExpiryViaMeta() {
        refreshStoresWithCurrentTopicInBothReadableAndWritable();

        final var expiry = Timestamp.newBuilder().seconds(123L).build();
        final var op = OP_BUILDER.topicID(topicId).expirationTime(expiry).build();
        final var txn = TransactionBody.newBuilder().consensusUpdateTopic(op).build();
        given(handleContext.body()).willReturn(txn);
        given(handleContext.expiryValidator()).willReturn(expiryValidator);
        given(handleContext.attributeValidator()).willReturn(attributeValidator);
        final var impliedMeta = new ExpiryMeta(123L, NA, null);
        willThrow(new HandleException(ResponseCodeEnum.INVALID_EXPIRATION_TIME))
                .given(expiryValidator)
                .resolveUpdateAttempt(currentExpiryMeta, impliedMeta);

        // expect:
        assertFailsWith(ResponseCodeEnum.INVALID_EXPIRATION_TIME, () -> subject.handle(handleContext));
    }

    @Test
    @DisplayName("Update expiry as expected")
    void appliesNewExpiryViaMeta() {
        refreshStoresWithCurrentTopicInBothReadableAndWritable();

        final var expiry = Timestamp.newBuilder().seconds(123L).build();
        final var op = OP_BUILDER.topicID(topicId).expirationTime(expiry).build();
        final var txn = TransactionBody.newBuilder().consensusUpdateTopic(op).build();
        given(handleContext.body()).willReturn(txn);
        given(handleContext.expiryValidator()).willReturn(expiryValidator);
        given(handleContext.attributeValidator()).willReturn(attributeValidator);
        final var impliedMeta = new ExpiryMeta(123L, NA, null);
        given(expiryValidator.resolveUpdateAttempt(currentExpiryMeta, impliedMeta))
                .willReturn(new ExpiryMeta(
                        123L, currentExpiryMeta.autoRenewPeriod(), currentExpiryMeta.autoRenewAccountId()));

        subject.handle(handleContext);

        final var newTopic = writableTopicState.get(topicId);
        assertEquals(123L, newTopic.expirationSecond());
    }

    @Test
    @DisplayName("Invalid new auto renew period update fails")
    void validatesNewAutoRenewPeriodViaMeta() {
        refreshStoresWithCurrentTopicInBothReadableAndWritable();

        final var autoRenewPeriod = Duration.newBuilder().seconds(123L).build();
        final var op =
                OP_BUILDER.topicID(topicId).autoRenewPeriod(autoRenewPeriod).build();
        final var txn = TransactionBody.newBuilder().consensusUpdateTopic(op).build();
        given(handleContext.body()).willReturn(txn);
        given(handleContext.expiryValidator()).willReturn(expiryValidator);
        given(handleContext.attributeValidator()).willReturn(attributeValidator);
        final var impliedMeta = new ExpiryMeta(NA, 123L, null);
        willThrow(new HandleException(ResponseCodeEnum.AUTORENEW_DURATION_NOT_IN_RANGE))
                .given(expiryValidator)
                .resolveUpdateAttempt(currentExpiryMeta, impliedMeta);

        // expect:
        assertFailsWith(ResponseCodeEnum.AUTORENEW_DURATION_NOT_IN_RANGE, () -> subject.handle(handleContext));
    }

    @Test
    @DisplayName("Update auto renew period as expected")
    void appliesNewAutoRenewPeriodViaMeta() {
        refreshStoresWithCurrentTopicInBothReadableAndWritable();
        final var autoRenewPeriod = Duration.newBuilder().seconds(123L).build();
        final var op =
                OP_BUILDER.topicID(topicId).autoRenewPeriod(autoRenewPeriod).build();
        final var txn = TransactionBody.newBuilder().consensusUpdateTopic(op).build();
        given(handleContext.body()).willReturn(txn);
        given(handleContext.attributeValidator()).willReturn(attributeValidator);
        given(handleContext.expiryValidator()).willReturn(expiryValidator);
        final var impliedMeta = new ExpiryMeta(NA, 123L, null);
        given(expiryValidator.resolveUpdateAttempt(currentExpiryMeta, impliedMeta))
                .willReturn(new ExpiryMeta(currentExpiryMeta.expiry(), 123L, null));

        subject.handle(handleContext);

        final var newTopic = writableTopicState.get(topicId);
        assertEquals(123L, newTopic.autoRenewPeriod());
    }

    @Test
    @DisplayName("Invalid new auto renew account update fails")
    void validatesNewAutoRenewAccountViaMeta() {
        refreshStoresWithCurrentTopicInBothReadableAndWritable();

        final var op = OP_BUILDER.topicID(topicId).autoRenewAccount(autoRenewId).build();
        final var txn = TransactionBody.newBuilder().consensusUpdateTopic(op).build();
        given(handleContext.body()).willReturn(txn);
        given(handleContext.expiryValidator()).willReturn(expiryValidator);
        given(handleContext.attributeValidator()).willReturn(attributeValidator);
        final var impliedMeta = new ExpiryMeta(NA, NA, autoRenewId);
        willThrow(new HandleException(INVALID_AUTORENEW_ACCOUNT))
                .given(expiryValidator)
                .resolveUpdateAttempt(currentExpiryMeta, impliedMeta);

        // expect:
        assertFailsWith(INVALID_AUTORENEW_ACCOUNT, () -> subject.handle(handleContext));
    }

    @Test
    @DisplayName("Update auto renew account as expected")
    void appliesNewAutoRenewNumViaMeta() {
        refreshStoresWithCurrentTopicInBothReadableAndWritable();

        final var autoRenewAccount = AccountID.newBuilder().accountNum(666).build();
        final var op =
                OP_BUILDER.topicID(topicId).autoRenewAccount(autoRenewAccount).build();
        final var txn = TransactionBody.newBuilder().consensusUpdateTopic(op).build();
        given(handleContext.body()).willReturn(txn);
        given(handleContext.expiryValidator()).willReturn(expiryValidator);
        given(handleContext.attributeValidator()).willReturn(attributeValidator);
        final var impliedMeta = new ExpiryMeta(
                NA,
                NA,
                AccountID.newBuilder().shardNum(0).realmNum(0).accountNum(666).build());
        given(expiryValidator.resolveUpdateAttempt(currentExpiryMeta, impliedMeta))
                .willReturn(new ExpiryMeta(
                        currentExpiryMeta.expiry(),
                        currentExpiryMeta.autoRenewPeriod(),
                        AccountID.newBuilder()
                                .shardNum(0)
                                .realmNum(0)
                                .accountNum(666)
                                .build()));

        subject.handle(handleContext);

        final var newTopic = writableTopicState.get(topicId);
        assertEquals(autoRenewAccount, newTopic.autoRenewAccountId());
    }

    @Test
    @DisplayName("Topic is not updated if no fields are changed")
    void nothingHappensIfUpdateIsNoop() {
        refreshStoresWithCurrentTopicInBothReadableAndWritable();

        given(handleContext.expiryValidator()).willReturn(expiryValidator);
        given(handleContext.attributeValidator()).willReturn(attributeValidator);
        // No-op
        final var op = OP_BUILDER.topicID(topicId).build();
        final var txn = TransactionBody.newBuilder().consensusUpdateTopic(op).build();
        given(handleContext.body()).willReturn(txn);

        subject.handle(handleContext);

        final var newTopic = writableTopicState.get(topicId);
        assertEquals(topic, newTopic);
    }

    @Test
    @DisplayName("Check if there is memo update")
    void memoMutationsIsNonExpiry() {
        final var op = OP_BUILDER.memo("HI").build();
        assertTrue(ConsensusUpdateTopicHandler.wantsToMutateNonExpiryField(op));
    }

    @Test
    @DisplayName("Check if there is adminKey update")
    void adminKeyMutationIsNonExpiry() {
        final var op = OP_BUILDER.adminKey(key).build();
        assertTrue(ConsensusUpdateTopicHandler.wantsToMutateNonExpiryField(op));
    }

    @Test
    @DisplayName("Check if there is submitKey update")
    void submitKeyMutationIsNonExpiry() {
        final var op = OP_BUILDER.submitKey(key).build();
        assertTrue(ConsensusUpdateTopicHandler.wantsToMutateNonExpiryField(op));
    }

    @Test
    @DisplayName("Validate Mutate NonExpiryField autoRenewPeriod as expected")
    void autoRenewPeriodMutationIsNonExpiry() {
        final var autoRenewPeriod = Duration.newBuilder().seconds(123L).build();
        final var op = OP_BUILDER.autoRenewPeriod(autoRenewPeriod).build();
        assertTrue(ConsensusUpdateTopicHandler.wantsToMutateNonExpiryField(op));
    }

    @Test
    @DisplayName("Check if there is autoRenewAccount update")
    void autoRenewAccountMutationIsNonExpiry() {
        final var op = OP_BUILDER.autoRenewAccount(autoRenewId).build();
        assertTrue(ConsensusUpdateTopicHandler.wantsToMutateNonExpiryField(op));
    }

    @Test
    @DisplayName("Check if there is autoRenewPeriod update")
    void expiryMutationIsExpiry() {
        final var expiryTime = Timestamp.newBuilder().seconds(123L).build();
        final var op = OP_BUILDER.expirationTime(expiryTime).build();
        assertFalse(ConsensusUpdateTopicHandler.wantsToMutateNonExpiryField(op));
    }

    @Test
    @DisplayName("payer key as admin key is allowed")
    void noneOfFieldsSetHaveNoRequiredKeys() throws PreCheckException {
        given(accountStore.getAccountById(payerId)).willReturn(account);
        given(account.key()).willReturn(adminKey);

        final var op = OP_BUILDER
                .expirationTime(Timestamp.newBuilder().build())
                .topicID(TopicID.newBuilder().topicNum(topicEntityNum).build())
                .build();
        final var context = new FakePreHandleContext(accountStore, txnWith(op));
        context.registerStore(ReadableTopicStore.class, readableStore);

        assertDoesNotThrow(() -> subject.preHandle(context));

        assertThat(context.payerKey()).isEqualTo(adminKey);
        assertThat(context.requiredNonPayerKeys()).isEmpty();
    }

    @Test
    @DisplayName("Missing topicId from ReadableTopicStore fails")
    void missingTopicFails() throws PreCheckException {
        given(accountStore.getAccountById(payerId)).willReturn(account);
        given(account.key()).willReturn(adminKey);

        final var op =
                OP_BUILDER.topicID(TopicID.newBuilder().topicNum(123L).build()).build();
        final var context = new FakePreHandleContext(accountStore, txnWith(op));
        context.registerStore(ReadableTopicStore.class, readableStore);

        assertThrowsPreCheck(() -> subject.preHandle(context), INVALID_TOPIC_ID);
    }

    @Test
    @DisplayName("Update a deleted topic ID fails")
    void rejectsDeletedTopic() throws PreCheckException {
        givenValidTopic(payerId, true);
        refreshStoresWithCurrentTopicInBothReadableAndWritable();

        final var txBody = TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder().accountID(payerId))
                .consensusUpdateTopic(OP_BUILDER.topicID(topicId))
                .build();
        given(accountStore.getAccountById(payerId)).willReturn(account);
        given(account.key()).willReturn(adminKey);
        final var context = new FakePreHandleContext(accountStore, txBody);
        context.registerStore(ReadableTopicStore.class, readableStore);

        assertThrowsPreCheck(() -> subject.preHandle(context), INVALID_TOPIC_ID);
    }

    @Test
    @DisplayName("Update admin key in preHandle as expected")
    void adminKeyAndOpAdminKeyAdded() throws PreCheckException {
        given(accountStore.getAccountById(payerId)).willReturn(account);
        given(account.key()).willReturn(adminKey);

        final var op = OP_BUILDER
                .adminKey(anotherKey)
                .topicID(TopicID.newBuilder().topicNum(topicEntityNum).build())
                .build();
        final var context = new FakePreHandleContext(accountStore, txnWith(op));
        context.registerStore(ReadableTopicStore.class, readableStore);

        assertDoesNotThrow(() -> subject.preHandle(context));

        assertThat(context.payerKey()).isEqualTo(adminKey);
        // adminKey is same as payer key. So will not be added to required keys.
        // and op admin key is different, so will be added.
        assertEquals(1, context.requiredNonPayerKeys().size());
        assertTrue(context.requiredNonPayerKeys().contains(anotherKey));
    }

    @Test
    @DisplayName("Update autoRenewAccount in preHandle as expected")
    void autoRenewAccountKeyAdded() throws PreCheckException {
        given(accountStore.getAccountById(autoRenewId)).willReturn(autoRenewAccount);
        given(autoRenewAccount.key()).willReturn(autoRenewKey);
        given(accountStore.getAccountById(payerId)).willReturn(account);
        given(account.key()).willReturn(adminKey);

        final var op = OP_BUILDER.autoRenewAccount(autoRenewId).topicID(topicId).build();
        final var context = new FakePreHandleContext(accountStore, txnWith(op));
        context.registerStore(ReadableTopicStore.class, readableStore);

        assertDoesNotThrow(() -> subject.preHandle(context));

        assertThat(context.payerKey()).isEqualTo(adminKey);
        // auto-renew key
        assertEquals(1, context.requiredNonPayerKeys().size());
    }

    @Test
    @DisplayName("Missing autoRenewAccount update fails")
    void missingAutoRenewAccountFails() throws PreCheckException {
        given(accountStore.getAccountById(autoRenewId)).willReturn(null);
        given(accountStore.getAccountById(payerId)).willReturn(account);
        given(account.key()).willReturn(adminKey);

        final var op = OP_BUILDER.autoRenewAccount(autoRenewId).topicID(topicId).build();

        final var context = new FakePreHandleContext(accountStore, txnWith(op));
        context.registerStore(ReadableTopicStore.class, readableStore);

        assertThrowsPreCheck(() -> subject.preHandle(context), INVALID_AUTORENEW_ACCOUNT);
    }

    private void assertFailsWith(final ResponseCodeEnum status, final Runnable something) {
        final var ex = assertThrows(HandleException.class, something::run);
        assertEquals(status, ex.getStatus());
    }

    private TransactionBody txnWith(final ConsensusUpdateTopicTransactionBody op) {
        return TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder().accountID(payerId))
                .consensusUpdateTopic(op)
                .build();
    }
}
