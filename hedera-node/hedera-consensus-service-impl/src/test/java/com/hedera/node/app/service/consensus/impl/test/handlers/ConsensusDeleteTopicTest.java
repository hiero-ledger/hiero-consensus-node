// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.consensus.impl.test.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOPIC_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.UNAUTHORIZED;
import static com.hedera.node.app.service.consensus.impl.schemas.V0490ConsensusSchema.TOPICS_STATE_ID;
import static com.hedera.node.app.service.consensus.impl.test.handlers.ConsensusTestUtils.SIMPLE_KEY_A;
import static com.hedera.node.app.service.consensus.impl.test.handlers.ConsensusTestUtils.SIMPLE_KEY_B;
import static com.hedera.node.app.spi.fixtures.Assertions.assertThrowsPreCheck;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.TopicID;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.consensus.ConsensusDeleteTopicTransactionBody;
import com.hedera.hapi.node.state.consensus.Topic;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.consensus.ReadableTopicStore;
import com.hedera.node.app.service.consensus.impl.ReadableTopicStoreImpl;
import com.hedera.node.app.service.consensus.impl.WritableTopicStore;
import com.hedera.node.app.service.consensus.impl.handlers.ConsensusDeleteTopicHandler;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.spi.fixtures.workflows.FakePreHandleContext;
import com.hedera.node.app.spi.ids.WritableEntityCounters;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ConsensusDeleteTopicTest extends ConsensusTestBase {

    @Mock
    private PureChecksContext pureChecksContext;

    @Mock
    private ReadableAccountStore accountStore;

    @Mock
    private ReadableTopicStore mockStore;

    @Mock
    private WritableEntityCounters entityCounters;

    private ConsensusDeleteTopicHandler subject;

    @BeforeEach
    void setUp() {
        subject = new ConsensusDeleteTopicHandler();

        writableTopicState = writableTopicStateWithOneKey();
        given(writableStates.<TopicID, Topic>get(TOPICS_STATE_ID)).willReturn(writableTopicState);
        writableStore = new WritableTopicStore(writableStates, entityCounters);
    }

    @Test
    @DisplayName("pureChecks fails if topic ID is missing")
    void failsIfMissTopicId() {
        givenValidTopic();
        given(pureChecksContext.body()).willReturn(newDeleteTxnMissTopicId());

        assertThrowsPreCheck(() -> subject.pureChecks(pureChecksContext), INVALID_TOPIC_ID);
    }

    @Test
    @DisplayName("Topic admin key sig required")
    void adminKeySigRequired() throws PreCheckException {
        // given:
        final var payerKey = mockPayerLookup();
        mockTopicLookup(SIMPLE_KEY_A, null);
        final var context = new FakePreHandleContext(accountStore, newDeleteTxn());
        context.registerStore(ReadableTopicStore.class, mockStore);

        assertDoesNotThrow(() -> subject.preHandle(context));

        // then:
        assertThat(context.payerKey()).isEqualTo(payerKey);
        final var expectedHederaAdminKey = SIMPLE_KEY_A;
        assertThat(context.requiredNonPayerKeys()).containsExactlyInAnyOrder(expectedHederaAdminKey);
    }

    @Test
    @DisplayName("Non-null topic submit key sig is NOT required")
    void submitKeyNotRequired() throws PreCheckException {
        // given:
        final var payerKey = mockPayerLookup();
        mockTopicLookup(SIMPLE_KEY_A, SIMPLE_KEY_B);
        final var context = new FakePreHandleContext(accountStore, newDeleteTxn());
        context.registerStore(ReadableTopicStore.class, mockStore);

        // when:
        assertDoesNotThrow(() -> subject.preHandle(context));

        // then:
        assertThat(context.payerKey()).isEqualTo(payerKey);
        final var unwantedHederaSubmitKey = SIMPLE_KEY_B;
        assertThat(context.requiredNonPayerKeys()).doesNotContain(unwantedHederaSubmitKey);
    }

    @Test
    @DisplayName("Topic not found returns error")
    void topicIdNotFound() throws PreCheckException {
        // given:
        mockPayerLookup();
        given(mockStore.getTopic(notNull())).willReturn(null);
        final var context = new FakePreHandleContext(accountStore, newDeleteTxn());
        context.registerStore(ReadableTopicStore.class, mockStore);

        // when:
        assertThrowsPreCheck(() -> subject.preHandle(context), INVALID_TOPIC_ID);
    }

    @Test
    @DisplayName("Topic without admin key returns error")
    void noTopicAdminKey() throws PreCheckException {
        // given:
        mockPayerLookup();
        mockTopicLookup(null, SIMPLE_KEY_A);
        final var context = new FakePreHandleContext(accountStore, newDeleteTxn());
        context.registerStore(ReadableTopicStore.class, mockStore);

        // when:
        assertThrowsPreCheck(() -> subject.preHandle(context), UNAUTHORIZED);
    }

    @Test
    @DisplayName("Fails preHandle if topic doesn't exist")
    void topicDoesntExist() throws PreCheckException {
        mockPayerLookup();
        final var txn = newDeleteTxn();

        readableTopicState = emptyReadableTopicState();
        given(readableStates.<TopicID, Topic>get(TOPICS_STATE_ID)).willReturn(readableTopicState);
        final var readableStore = new ReadableTopicStoreImpl(readableStates, readableEntityCounters);

        final var context = new FakePreHandleContext(accountStore, txn);
        context.registerStore(ReadableTopicStore.class, readableStore);

        assertThrowsPreCheck(() -> subject.preHandle(context), INVALID_TOPIC_ID);
    }

    @Test
    @DisplayName("Fails preHandle if topic deleted")
    void topicDeletedFail() throws PreCheckException {
        mockPayerLookup();
        final var txn = newDeleteTxn();

        readableTopicState = emptyReadableTopicState();
        given(readableStates.<TopicID, Topic>get(TOPICS_STATE_ID)).willReturn(readableTopicState);
        final var readableStore = new ReadableTopicStoreImpl(readableStates, readableEntityCounters);

        final var context = new FakePreHandleContext(accountStore, txn);
        context.registerStore(ReadableTopicStore.class, readableStore);

        assertThrowsPreCheck(() -> subject.preHandle(context), INVALID_TOPIC_ID);
    }

    @Test
    @DisplayName("Fails handle if admin key doesn't exist on topic to be deleted")
    void adminKeyDoesntExist() {
        final var txn = newDeleteTxn();
        given(handleContext.body()).willReturn(txn);

        topic = new Topic(
                topicId,
                sequenceNumber,
                expirationTime,
                autoRenewSecs,
                AccountID.newBuilder().accountNum(10L).build(),
                false,
                Bytes.wrap(runningHash),
                memo,
                null,
                null,
                null,
                null,
                null);

        writableTopicState = writableTopicStateWithOneKey();
        given(writableStates.<TopicID, Topic>get(TOPICS_STATE_ID)).willReturn(writableTopicState);
        writableStore = new WritableTopicStore(writableStates, entityCounters);
        given(storeFactory.writableStore(WritableTopicStore.class)).willReturn(writableStore);

        final var msg = assertThrows(HandleException.class, () -> subject.handle(handleContext));

        assertEquals(ResponseCodeEnum.UNAUTHORIZED, msg.getStatus());
    }

    @Test
    @DisplayName("Handle works as expected")
    void handleWorksAsExpected() {
        final var txn = newDeleteTxn();
        given(handleContext.body()).willReturn(txn);

        final var existingTopic = writableStore.getTopic(
                TopicID.newBuilder().topicNum(topicEntityNum).build());
        assertNotNull(existingTopic);
        assertFalse(existingTopic.deleted());
        given(storeFactory.writableStore(WritableTopicStore.class)).willReturn(writableStore);

        subject.handle(handleContext);

        final var changedTopic = writableStore.getTopic(
                TopicID.newBuilder().topicNum(topicEntityNum).build());

        assertNotNull(changedTopic);
        assertTrue(changedTopic.deleted());
    }

    private Key mockPayerLookup() {
        return ConsensusTestUtils.mockPayerLookup(A_COMPLEX_KEY, payerId, accountStore);
    }

    private void mockTopicLookup(final Key adminKey, final Key submitKey) {
        ConsensusTestUtils.mockTopicLookup(adminKey, submitKey, mockStore);
    }

    private TransactionBody newDeleteTxn() {
        final var txnId = TransactionID.newBuilder().accountID(payerId).build();
        final var deleteTopicBuilder =
                ConsensusDeleteTopicTransactionBody.newBuilder().topicID(topicId);
        return TransactionBody.newBuilder()
                .transactionID(txnId)
                .consensusDeleteTopic(deleteTopicBuilder.build())
                .build();
    }

    private TransactionBody newDeleteTxnMissTopicId() {
        final var txnId = TransactionID.newBuilder().accountID(payerId).build();
        final var deleteTopicBuilder = ConsensusDeleteTopicTransactionBody.newBuilder();
        return TransactionBody.newBuilder()
                .transactionID(txnId)
                .consensusDeleteTopic(deleteTopicBuilder.build())
                .build();
    }
}
