// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.consensus.impl.test.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_AUTORENEW_ACCOUNT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.OK;
import static com.hedera.node.app.service.consensus.impl.ConsensusServiceImpl.TOPICS_KEY;
import static com.hedera.node.app.service.consensus.impl.test.handlers.ConsensusTestUtils.SIMPLE_KEY_A;
import static com.hedera.node.app.service.consensus.impl.test.handlers.ConsensusTestUtils.SIMPLE_KEY_B;
import static com.hedera.node.app.spi.fixtures.Assertions.assertThrowsPreCheck;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mock.Strictness.LENIENT;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.TopicID;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.consensus.ConsensusCreateTopicTransactionBody;
import com.hedera.hapi.node.state.consensus.Topic;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.transaction.FixedCustomFee;
import com.hedera.hapi.node.transaction.FixedFee;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.hapi.utils.EntityType;
import com.hedera.node.app.service.consensus.ReadableTopicStore;
import com.hedera.node.app.service.consensus.impl.WritableTopicStore;
import com.hedera.node.app.service.consensus.impl.handlers.ConsensusCreateTopicHandler;
import com.hedera.node.app.service.consensus.impl.records.ConsensusCreateTopicStreamBuilder;
import com.hedera.node.app.service.consensus.impl.validators.ConsensusCustomFeesValidator;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.ReadableTokenRelationStore;
import com.hedera.node.app.service.token.ReadableTokenStore;
import com.hedera.node.app.spi.fixtures.ids.FakeEntityIdFactoryImpl;
import com.hedera.node.app.spi.fixtures.workflows.FakePreHandleContext;
import com.hedera.node.app.spi.ids.EntityIdFactory;
import com.hedera.node.app.spi.ids.EntityNumGenerator;
import com.hedera.node.app.spi.ids.WritableEntityCounters;
import com.hedera.node.app.spi.validation.AttributeValidator;
import com.hedera.node.app.spi.validation.ExpiryMeta;
import com.hedera.node.app.spi.validation.ExpiryValidator;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ConsensusCreateTopicHandlerTest extends ConsensusTestBase {
    private static final long SHARD = 1L;
    private static final long REALM = 2L;

    @Mock
    private ReadableAccountStore accountStore;

    @Mock
    private ReadableTokenStore tokenStore;

    @Mock
    private ReadableTokenRelationStore tokenRelationStore;

    @Mock
    private AttributeValidator validator;

    @Mock
    private ExpiryValidator expiryValidator;

    @Mock
    private ConsensusCreateTopicStreamBuilder recordBuilder;

    @Mock(strictness = LENIENT)
    private HandleContext.SavepointStack stack;

    @Mock
    private EntityNumGenerator entityNumGenerator;

    @Mock
    private WritableEntityCounters entityCounters;

    private final EntityIdFactory idFactory = new FakeEntityIdFactoryImpl(SHARD, REALM);

    private WritableTopicStore topicStore;
    private Configuration config;
    private ConsensusCreateTopicHandler subject;

    private TransactionBody newCreateTxn(Key adminKey, Key submitKey, boolean hasAutoRenewAccount) {
        return newCreateTxn(adminKey, submitKey, hasAutoRenewAccount, null, null);
    }

    private TransactionBody newCreateTxn(
            Key adminKey,
            Key submitKey,
            boolean hasAutoRenewAccount,
            List<FixedCustomFee> customFees,
            List<Key> feeExemptKeyList) {
        final var txnId = TransactionID.newBuilder().accountID(payerId).build();
        final var createTopicBuilder = ConsensusCreateTopicTransactionBody.newBuilder();
        if (adminKey != null) {
            createTopicBuilder.adminKey(adminKey);
        }
        if (submitKey != null) {
            createTopicBuilder.submitKey(submitKey);
        }
        createTopicBuilder.autoRenewPeriod(WELL_KNOWN_AUTO_RENEW_PERIOD);
        createTopicBuilder.memo(memo);
        if (hasAutoRenewAccount) {
            createTopicBuilder.autoRenewAccount(autoRenewId);
        }
        if (customFees != null) {
            createTopicBuilder.customFees(customFees);
        }
        if (feeExemptKeyList != null) {
            createTopicBuilder.feeExemptKeyList(feeExemptKeyList);
        }
        return TransactionBody.newBuilder()
                .transactionID(txnId)
                .consensusCreateTopic(createTopicBuilder.build())
                .build();
    }

    @BeforeEach
    void setUp() {
        config = HederaTestConfigBuilder.create()
                .withValue("topics.maxNumber", 10L)
                .getOrCreateConfig();
        subject = new ConsensusCreateTopicHandler(idFactory, new ConsensusCustomFeesValidator());
        topicStore = new WritableTopicStore(writableStates, entityCounters);
        given(handleContext.configuration()).willReturn(config);

        given(handleContext.storeFactory().readableStore(ReadableTopicStore.class))
                .willReturn(topicStore);
        given(storeFactory.writableStore(WritableTopicStore.class)).willReturn(topicStore);
        given(handleContext.storeFactory().readableStore(ReadableAccountStore.class))
                .willReturn(accountStore);
        given(handleContext.storeFactory().readableStore(ReadableTokenStore.class))
                .willReturn(tokenStore);
        given(handleContext.storeFactory().readableStore(ReadableTokenRelationStore.class))
                .willReturn(tokenRelationStore);

        given(handleContext.savepointStack()).willReturn(stack);
        given(stack.getBaseBuilder(ConsensusCreateTopicStreamBuilder.class)).willReturn(recordBuilder);
        lenient().when(handleContext.entityNumGenerator()).thenReturn(entityNumGenerator);
    }

    @Test
    @DisplayName("All non-null key inputs required")
    void nonNullKeyInputsRequired() throws PreCheckException {
        // given:
        final var payerKey = mockPayerLookup(key);
        final var adminKey = SIMPLE_KEY_A;
        final var submitKey = SIMPLE_KEY_B;

        // when:
        final var context = new FakePreHandleContext(accountStore, newCreateTxn(adminKey, submitKey, false));
        subject.preHandle(context);

        // then:
        assertThat(context.payerKey()).isEqualTo(payerKey);
        assertThat(context.requiredNonPayerKeys()).containsExactlyInAnyOrder(adminKey);
    }

    @Test
    @DisplayName("Non-payer admin key is added")
    void differentAdminKey() throws PreCheckException {
        // given:
        final var payerKey = mockPayerLookup(key);
        final var adminKey = SIMPLE_KEY_A;

        // when:
        final var context = new FakePreHandleContext(accountStore, newCreateTxn(adminKey, null, false));
        subject.preHandle(context);

        // then:
        assertThat(context.payerKey()).isEqualTo(payerKey);
        assertThat(context.requiredNonPayerKeys()).isEqualTo(Set.of(adminKey));
    }

    @Test
    @DisplayName("Non-payer submit key is added")
    void createAddsDifferentSubmitKey() throws PreCheckException {
        // given:
        final var payerKey = mockPayerLookup(key);
        final var submitKey = SIMPLE_KEY_B;

        // when:
        final var context = new FakePreHandleContext(accountStore, newCreateTxn(null, submitKey, false));
        subject.preHandle(context);

        // then:
        assertThat(context.payerKey()).isEqualTo(payerKey);
        assertThat(context.requiredNonPayerKeys()).isEmpty();
    }

    @Test
    @DisplayName("Payer key can be added as admin")
    void createAddsPayerAsAdmin() throws PreCheckException {
        // given:
        final var protoPayerKey = SIMPLE_KEY_A;
        final var payerKey = mockPayerLookup(protoPayerKey);

        // when:
        final var context = new FakePreHandleContext(accountStore, newCreateTxn(protoPayerKey, null, false));
        subject.preHandle(context);

        // then:
        assertThat(context.payerKey()).isEqualTo(payerKey);
        assertThat(context.requiredNonPayerKeys()).isEmpty();
    }

    @Test
    @DisplayName("Payer key can be added as submitter")
    void createAddsPayerAsSubmitter() throws PreCheckException {
        // given:
        final var protoPayerKey = SIMPLE_KEY_B;
        final var payerKey = mockPayerLookup(protoPayerKey);

        // when:
        final var context = new FakePreHandleContext(accountStore, newCreateTxn(null, protoPayerKey, false));
        subject.preHandle(context);

        // then:
        assertThat(context.payerKey()).isEqualTo(payerKey);
        assertThat(context.requiredNonPayerKeys()).isEmpty();
    }

    @Test
    @DisplayName("Fails if auto account is returned with a null key")
    void autoRenewAccountKeyIsNull() throws PreCheckException {
        // given:
        mockPayerLookup(key);
        given(accountStore.getAccountById(autoRenewId)).willReturn(null);
        final var inputTxn = TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder().accountID(payerId).build())
                .consensusCreateTopic(ConsensusCreateTopicTransactionBody.newBuilder()
                        .autoRenewAccount(autoRenewId)
                        .build())
                .build();

        // when:
        final var context = new FakePreHandleContext(accountStore, inputTxn);
        assertThrowsPreCheck(() -> subject.preHandle(context), INVALID_AUTORENEW_ACCOUNT);
    }

    @Test
    @DisplayName("Only payer key is always required")
    void requiresPayerKey() throws PreCheckException {
        // given:
        final var payerKey = mockPayerLookup(key);
        final var context = new FakePreHandleContext(accountStore, newCreateTxn(null, null, false));

        // when:
        subject.preHandle(context);

        // then:
        assertThat(context.payerKey()).isEqualTo(payerKey);
        assertThat(context.requiredNonPayerKeys()).isEmpty();
    }

    @Test
    @DisplayName("Handle works as expected")
    void handleWorksAsExpected() {
        final var adminKey = SIMPLE_KEY_A;
        final var submitKey = SIMPLE_KEY_B;
        final var txnBody = newCreateTxn(adminKey, submitKey, true);
        final var op = txnBody.consensusCreateTopic();
        given(handleContext.body()).willReturn(txnBody);

        given(handleContext.consensusNow()).willReturn(Instant.ofEpochSecond(1_234_567L));
        given(handleContext.attributeValidator()).willReturn(validator);
        given(handleContext.expiryValidator()).willReturn(expiryValidator);
        given(expiryValidator.resolveCreationAttempt(anyBoolean(), any(), any()))
                .willReturn(new ExpiryMeta(
                        1_234_567L + op.autoRenewPeriod().seconds(),
                        op.autoRenewPeriod().seconds(),
                        op.autoRenewAccount()));
        given(entityNumGenerator.newEntityNum()).willReturn(1_234L);

        subject.handle(handleContext);

        final var topicID = TopicID.newBuilder()
                .shardNum(SHARD)
                .realmNum(REALM)
                .topicNum(1_234L)
                .build();
        final var createdTopic = topicStore.getTopic(topicID);
        assertNotNull(createdTopic);

        final var actualTopic = createdTopic;
        assertEquals(0L, actualTopic.sequenceNumber());
        assertEquals(memo, actualTopic.memo());
        assertEquals(adminKey, actualTopic.adminKey());
        assertEquals(submitKey, actualTopic.submitKey());
        assertEquals(1234667, actualTopic.expirationSecond());
        assertEquals(op.autoRenewPeriod().seconds(), actualTopic.autoRenewPeriod());
        assertEquals(autoRenewId, actualTopic.autoRenewAccountId());
        verify(recordBuilder).topicID(topicID);
        assertNotNull(topicStore.getTopic(topicID));
    }

    @Test
    @DisplayName("Handle works as expected without keys")
    void handleDoesntRequireKeys() {
        final var txnBody = newCreateTxn(SIMPLE_KEY_A, null, true);
        final var op = txnBody.consensusCreateTopic();
        given(handleContext.body()).willReturn(txnBody);

        given(handleContext.consensusNow()).willReturn(Instant.ofEpochSecond(1_234_567L));
        given(handleContext.attributeValidator()).willReturn(validator);
        given(handleContext.expiryValidator()).willReturn(expiryValidator);
        given(expiryValidator.resolveCreationAttempt(anyBoolean(), any(), any()))
                .willReturn(new ExpiryMeta(
                        1_234_567L + op.autoRenewPeriod().seconds(),
                        op.autoRenewPeriod().seconds(),
                        op.autoRenewAccount()));
        given(entityNumGenerator.newEntityNum()).willReturn(1_234L);

        subject.handle(handleContext);

        final var topicId = idFactory.newTopicId(1_234L);
        final var createdTopic = topicStore.getTopic(topicId);
        assertNotNull(createdTopic);

        final var actualTopic = createdTopic;
        assertEquals(0L, actualTopic.sequenceNumber());
        assertEquals(memo, actualTopic.memo());
        assertEquals(SIMPLE_KEY_A, actualTopic.adminKey());
        assertNull(actualTopic.submitKey());
        assertEquals(1_234_567L + op.autoRenewPeriod().seconds(), actualTopic.expirationSecond());
        assertEquals(op.autoRenewPeriod().seconds(), actualTopic.autoRenewPeriod());
        assertEquals(autoRenewId, actualTopic.autoRenewAccountId());
        verify(recordBuilder).topicID(topicId);
        assertNotNull(topicStore.getTopic(topicId));
    }

    @Test
    @DisplayName("Translates INVALID_EXPIRATION_TIME to AUTO_RENEW_DURATION_NOT_IN_RANGE")
    void translatesInvalidExpiryException() {
        final var txnBody = newCreateTxn(SIMPLE_KEY_A, null, true);
        given(handleContext.body()).willReturn(txnBody);

        given(handleContext.consensusNow()).willReturn(Instant.ofEpochSecond(1_234_567L));

        given(handleContext.attributeValidator()).willReturn(validator);
        given(handleContext.expiryValidator()).willReturn(expiryValidator);
        given(expiryValidator.resolveCreationAttempt(anyBoolean(), any(), any()))
                .willThrow(new HandleException(ResponseCodeEnum.INVALID_EXPIRATION_TIME));
        final var msg = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertEquals(ResponseCodeEnum.AUTORENEW_DURATION_NOT_IN_RANGE, msg.getStatus());
    }

    @Test
    @DisplayName("Doesnt translate INVALID_AUTORENEW_ACCOUNT")
    void doesntTranslateInvalidAutoRenewNum() {
        final var txnBody = newCreateTxn(SIMPLE_KEY_A, null, true);
        given(handleContext.body()).willReturn(txnBody);

        given(handleContext.consensusNow()).willReturn(Instant.ofEpochSecond(1_234_567L));

        given(handleContext.attributeValidator()).willReturn(validator);
        given(handleContext.expiryValidator()).willReturn(expiryValidator);
        given(expiryValidator.resolveCreationAttempt(anyBoolean(), any(), any()))
                .willThrow(new HandleException(INVALID_AUTORENEW_ACCOUNT));

        final var msg = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertEquals(INVALID_AUTORENEW_ACCOUNT, msg.getStatus());
    }

    @Test
    @DisplayName("Memo Validation Failure will throw")
    void handleThrowsIfAttributeValidatorFails() {
        final var txnBody = newCreateTxn(SIMPLE_KEY_A, SIMPLE_KEY_B, true);
        given(handleContext.body()).willReturn(txnBody);

        given(handleContext.attributeValidator()).willReturn(validator);
        given(handleContext.expiryValidator()).willReturn(expiryValidator);

        doThrow(new HandleException(ResponseCodeEnum.MEMO_TOO_LONG))
                .when(validator)
                .validateMemo(txnBody.consensusCreateTopicOrThrow().memo());

        assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertEquals(0, topicStore.sizeOfState());
    }

    @Test
    @DisplayName("Key Validation Failure will throw")
    void handleThrowsIfKeyValidatorFails() {
        final var adminKey = SIMPLE_KEY_A;
        final var submitKey = SIMPLE_KEY_B;
        final var txnBody = newCreateTxn(adminKey, submitKey, true);
        given(handleContext.body()).willReturn(txnBody);

        given(handleContext.attributeValidator()).willReturn(validator);

        doThrow(new HandleException(ResponseCodeEnum.BAD_ENCODING))
                .when(validator)
                .validateKey(adminKey);
        assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertEquals(0, topicStore.sizeOfState());
    }

    @Test
    @DisplayName("Fails when the allowed topics are already created")
    void failsWhenMaxRegimeExceeds() {
        final var adminKey = SIMPLE_KEY_A;
        final var submitKey = SIMPLE_KEY_B;
        final var txnBody = newCreateTxn(adminKey, submitKey, true);
        final var op = txnBody.consensusCreateTopic();
        given(handleContext.body()).willReturn(txnBody);
        final var writableState = writableTopicStateWithOneKey();

        given(writableStates.<TopicID, Topic>get(TOPICS_KEY)).willReturn(writableState);
        given(entityCounters.getCounterFor(EntityType.TOPIC)).willReturn(1L);
        final var topicStore = new WritableTopicStore(writableStates, entityCounters);
        assertEquals(1, topicStore.sizeOfState());
        given(storeFactory.writableStore(WritableTopicStore.class)).willReturn(topicStore);
        given(storeFactory.readableStore(ReadableTopicStore.class)).willReturn(topicStore);
        given(handleContext.expiryValidator()).willReturn(expiryValidator);
        given(handleContext.attributeValidator()).willReturn(validator);
        final var config = HederaTestConfigBuilder.create()
                .withValue("topics.maxNumber", 1L)
                .getOrCreateConfig();
        given(handleContext.configuration()).willReturn(config);

        given(handleContext.consensusNow()).willReturn(Instant.ofEpochSecond(1_234_567L));

        final var msg = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertEquals(ResponseCodeEnum.MAX_ENTITIES_IN_PRICE_REGIME_HAVE_BEEN_CREATED, msg.getStatus());
        assertEquals(0, topicStore.modifiedTopics().size());
    }

    @Test
    @DisplayName("Validates AutoRenewAccount")
    void validatedAutoRenewAccount() {
        final var adminKey = SIMPLE_KEY_A;
        final var submitKey = SIMPLE_KEY_B;
        final var txnBody = newCreateTxn(adminKey, submitKey, true);
        given(handleContext.body()).willReturn(txnBody);
        final var writableState = writableTopicStateWithOneKey();

        given(handleContext.consensusNow()).willReturn(Instant.ofEpochSecond(1_234_567L));
        given(writableStates.<TopicID, Topic>get(TOPICS_KEY)).willReturn(writableState);
        given(entityCounters.getCounterFor(EntityType.TOPIC)).willReturn(1L);
        final var topicStore = new WritableTopicStore(writableStates, entityCounters);
        assertEquals(1, topicStore.sizeOfState());

        given(handleContext.attributeValidator()).willReturn(validator);
        given(handleContext.expiryValidator()).willReturn(expiryValidator);
        doThrow(HandleException.class).when(expiryValidator).resolveCreationAttempt(anyBoolean(), any(), any());

        final var failure = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertEquals(0, topicStore.modifiedTopics().size());
    }

    @Test
    @DisplayName("Handle works as expected wit custom fees and FEKL")
    void validatedCustomFees() {
        final var customFees = List.of(FixedCustomFee.newBuilder()
                .fixedFee(FixedFee.newBuilder().amount(1).build())
                .feeCollectorAccountId(AccountID.DEFAULT)
                .build());
        final var feeExemptKeyList = List.of(SIMPLE_KEY_A, SIMPLE_KEY_B);
        final var txnBody = newCreateTxn(adminKey, null, true, customFees, feeExemptKeyList);
        given(handleContext.body()).willReturn(txnBody);

        given(accountStore.getAliasedAccountById(any())).willReturn(Account.DEFAULT);

        // mock validators
        given(handleContext.consensusNow()).willReturn(Instant.ofEpochSecond(1_234_567L));
        given(handleContext.attributeValidator()).willReturn(validator);
        given(handleContext.expiryValidator()).willReturn(expiryValidator);
        given(entityNumGenerator.newEntityNum()).willReturn(1_234L);
        given(expiryValidator.expirationStatus(any(), anyBoolean(), anyLong())).willReturn(OK);
        final var op = txnBody.consensusCreateTopic();
        given(expiryValidator.resolveCreationAttempt(anyBoolean(), any(), any()))
                .willReturn(new ExpiryMeta(
                        1_234_567L + op.autoRenewPeriod().seconds(),
                        op.autoRenewPeriod().seconds(),
                        op.autoRenewAccount()));

        subject.handle(handleContext);

        final var createdTopic = topicStore.getTopic(TopicID.newBuilder()
                .shardNum(SHARD)
                .realmNum(REALM)
                .topicNum(1_234L)
                .build());
        assertNotNull(createdTopic);

        final var actualTopic = createdTopic;
        assertEquals(0L, actualTopic.sequenceNumber());
        assertEquals(memo, actualTopic.memo());
        assertEquals(adminKey, actualTopic.adminKey());
        assertEquals(1_234_567L + op.autoRenewPeriod().seconds(), actualTopic.expirationSecond());
        assertEquals(op.autoRenewPeriod().seconds(), actualTopic.autoRenewPeriod());
        assertEquals(autoRenewId, actualTopic.autoRenewAccountId());
        final var topicID = idFactory.newTopicId(1_234L);
        verify(recordBuilder).topicID(topicID);
        assertNotNull(topicStore.getTopic(topicID));
    }

    @Test
    @DisplayName("Handle works as expected with auto-renew account and without adminKey set")
    void autoRenewAccountSetWithoutAdminKey() {
        final var customFees = List.of(FixedCustomFee.newBuilder()
                .fixedFee(FixedFee.newBuilder().amount(1).build())
                .feeCollectorAccountId(AccountID.DEFAULT)
                .build());
        final var feeExemptKeyList = List.of(SIMPLE_KEY_A, SIMPLE_KEY_B);
        final var txnBody = newCreateTxn(null, null, true, customFees, feeExemptKeyList);
        given(handleContext.body()).willReturn(txnBody);
        given(accountStore.getAliasedAccountById(any())).willReturn(Account.DEFAULT);

        // mock validators
        given(handleContext.consensusNow()).willReturn(Instant.ofEpochSecond(1_234_567L));
        given(handleContext.attributeValidator()).willReturn(validator);
        given(handleContext.expiryValidator()).willReturn(expiryValidator);
        given(entityNumGenerator.newEntityNum()).willReturn(1_234L);
        given(expiryValidator.expirationStatus(any(), anyBoolean(), anyLong())).willReturn(OK);
        final var op = txnBody.consensusCreateTopic();
        given(expiryValidator.resolveCreationAttempt(anyBoolean(), any(), any()))
                .willReturn(new ExpiryMeta(
                        1_234_567L + op.autoRenewPeriod().seconds(),
                        op.autoRenewPeriod().seconds(),
                        op.autoRenewAccount()));

        subject.handle(handleContext);

        final var createdTopic = topicStore.getTopic(TopicID.newBuilder()
                .shardNum(SHARD)
                .realmNum(REALM)
                .topicNum(1_234L)
                .build());

        assertNotNull(createdTopic);
        assertEquals(0L, createdTopic.sequenceNumber());
        assertEquals(memo, createdTopic.memo());
        assertFalse(createdTopic.hasAdminKey());
        assertEquals(1_234_567L + op.autoRenewPeriod().seconds(), createdTopic.expirationSecond());
        assertEquals(op.autoRenewPeriod().seconds(), createdTopic.autoRenewPeriod());
        assertEquals(autoRenewId, createdTopic.autoRenewAccountId());

        final var topicID = idFactory.newTopicId(1_234L);
        verify(recordBuilder).topicID(topicID);
        assertNotNull(topicStore.getTopic(topicID));
    }

    @Test
    @DisplayName("Handle fail with toо many fee exempt keys")
    void failWithTooManyFeeExemptKeys() {
        final var feeExemptKeyList = buildFEKL(100);
        final var txnBody = newCreateTxn(adminKey, null, true, null, feeExemptKeyList);
        given(handleContext.body()).willReturn(txnBody);

        // mock validators
        given(handleContext.consensusNow()).willReturn(Instant.ofEpochSecond(1_234_567L));
        given(handleContext.attributeValidator()).willReturn(validator);
        given(handleContext.expiryValidator()).willReturn(expiryValidator);

        final var msg = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertEquals(ResponseCodeEnum.MAX_ENTRIES_FOR_FEE_EXEMPT_KEY_LIST_EXCEEDED, msg.getStatus());
        assertEquals(0, topicStore.modifiedTopics().size());
    }

    @Test
    @DisplayName("Handle fail with invalid custom fee amount")
    void failWithInvalidFeeAmount() {
        final var customFees = List.of(FixedCustomFee.newBuilder()
                .fixedFee(FixedFee.newBuilder().amount(-1).build())
                .feeCollectorAccountId(AccountID.DEFAULT)
                .build());
        final var txnBody = newCreateTxn(adminKey, null, true, customFees, null);
        given(handleContext.body()).willReturn(txnBody);

        given(accountStore.getAliasedAccountById(any())).willReturn(Account.DEFAULT);

        // mock validators
        given(handleContext.consensusNow()).willReturn(Instant.ofEpochSecond(1_234_567L));
        given(handleContext.attributeValidator()).willReturn(validator);
        given(handleContext.expiryValidator()).willReturn(expiryValidator);
        given(expiryValidator.expirationStatus(any(), anyBoolean(), anyLong())).willReturn(OK);

        final var msg = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertEquals(ResponseCodeEnum.CUSTOM_FEE_MUST_BE_POSITIVE, msg.getStatus());
        assertEquals(0, topicStore.modifiedTopics().size());
    }

    @Test
    @DisplayName("Handle fail with invalid collector")
    void failWithInvalidCollector() {
        final var customFees = List.of(FixedCustomFee.newBuilder()
                .fixedFee(FixedFee.newBuilder().amount(1).build())
                .feeCollectorAccountId(AccountID.DEFAULT)
                .build());
        final var txnBody = newCreateTxn(adminKey, null, true, customFees, null);
        given(handleContext.body()).willReturn(txnBody);

        given(accountStore.getAliasedAccountById(any())).willReturn(null);

        // mock
        given(handleContext.consensusNow()).willReturn(Instant.ofEpochSecond(1_234_567L));

        given(handleContext.attributeValidator()).willReturn(validator);
        given(handleContext.expiryValidator()).willReturn(expiryValidator);

        final var msg = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertEquals(ResponseCodeEnum.INVALID_CUSTOM_FEE_COLLECTOR, msg.getStatus());
        assertEquals(0, topicStore.modifiedTopics().size());
    }

    // Note: there are more tests in ConsensusCreateTopicHandlerParityTest.java

    private Key mockPayerLookup(Key key) {
        final var account = mock(Account.class);
        given(account.key()).willReturn(key);
        given(accountStore.getAccountById(payerId)).willReturn(account);
        return key;
    }

    private List<Key> buildFEKL(int count) {
        final var list = new ArrayList<Key>();
        for (int i = 0; i < count; i++) {
            final var value = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" + i;
            list.add(Key.newBuilder().ed25519(Bytes.wrap(value.getBytes())).build());
        }
        return list;
    }
}
