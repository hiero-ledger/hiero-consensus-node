// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.consensus.impl.test.handlers;

import static com.hedera.node.app.service.consensus.impl.ConsensusServiceImpl.TOPICS_KEY;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.responseCode;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.QueryHeader;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.ResponseHeader;
import com.hedera.hapi.node.base.ResponseType;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TopicID;
import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.consensus.ConsensusGetTopicInfoQuery;
import com.hedera.hapi.node.consensus.ConsensusGetTopicInfoResponse;
import com.hedera.hapi.node.consensus.ConsensusTopicInfo;
import com.hedera.hapi.node.state.consensus.Topic;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.hapi.node.transaction.Response;
import com.hedera.node.app.service.consensus.ConsensusService;
import com.hedera.node.app.service.consensus.ReadableTopicStore;
import com.hedera.node.app.service.consensus.impl.ReadableTopicStoreImpl;
import com.hedera.node.app.service.consensus.impl.handlers.ConsensusGetTopicInfoHandler;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.QueryContext;
import com.hedera.node.config.converter.BytesConverter;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.test.fixtures.MapReadableKVState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ConsensusGetTopicInfoTest extends ConsensusTestBase {

    @Mock
    private QueryContext context;

    private ConsensusGetTopicInfoHandler subject;

    @BeforeEach
    void setUp() {
        subject = new ConsensusGetTopicInfoHandler();
    }

    @Test
    @DisplayName("Query header is extracted correctly")
    void extractsHeader() {
        final var query = createGetTopicInfoQuery(topicEntityNum);
        final var header = subject.extractHeader(query);
        final var op = query.consensusGetTopicInfoOrThrow();
        assertEquals(op.header(), header);
    }

    @Test
    @DisplayName("Check empty query response is created correctly")
    void createsEmptyResponse() {
        final var responseHeader = ResponseHeader.newBuilder()
                .nodeTransactionPrecheckCode(ResponseCodeEnum.FAIL_FEE)
                .build();
        final var response = subject.createEmptyResponse(responseHeader);
        final var expectedResponse = Response.newBuilder()
                .consensusGetTopicInfo(
                        ConsensusGetTopicInfoResponse.newBuilder().header(responseHeader))
                .build();
        assertEquals(expectedResponse, response);
    }

    @Test
    @DisplayName("Check node payment requirement is correct with each response type")
    void requiresPayment() {
        assertTrue(subject.requiresNodePayment(ResponseType.ANSWER_ONLY));
        assertTrue(subject.requiresNodePayment(ResponseType.ANSWER_STATE_PROOF));
        assertFalse(subject.requiresNodePayment(ResponseType.COST_ANSWER));
        assertFalse(subject.requiresNodePayment(ResponseType.COST_ANSWER_STATE_PROOF));
    }

    @Test
    @DisplayName("Check Answer Only Cost is correct with each response type")
    void needsAnswerOnlyCostForCostAnswer() {
        assertFalse(subject.needsAnswerOnlyCost(ResponseType.ANSWER_ONLY));
        assertFalse(subject.needsAnswerOnlyCost(ResponseType.ANSWER_STATE_PROOF));
        assertTrue(subject.needsAnswerOnlyCost(ResponseType.COST_ANSWER));
        assertFalse(subject.needsAnswerOnlyCost(ResponseType.COST_ANSWER_STATE_PROOF));
    }

    @Test
    @DisplayName("Validate query is good")
    void validatesQueryWhenValidTopic() {
        givenValidTopic();

        final var query = createGetTopicInfoQuery(topicEntityNum);
        given(context.query()).willReturn(query);
        given(context.createStore(ReadableTopicStore.class)).willReturn(readableStore);

        assertThatCode(() -> subject.validate(context)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Topic Id is needed during validate")
    void validatesQueryIfInvalidTopic() {
        readableTopicState.reset();
        final var state = MapReadableKVState.<Long, Topic>builder(ConsensusService.NAME, TOPICS_KEY)
                .build();
        given(readableStates.<Long, Topic>get(TOPICS_KEY)).willReturn(state);
        final var store = new ReadableTopicStoreImpl(readableStates, entityCounters);

        final var query = createGetTopicInfoQuery(topicEntityNum);
        when(context.query()).thenReturn(query);
        when(context.createStore(ReadableTopicStore.class)).thenReturn(store);

        assertThatThrownBy(() -> subject.validate(context))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(ResponseCodeEnum.INVALID_TOPIC_ID));
    }

    @Test
    @DisplayName("Topic Id in transaction is needed during validate")
    void validatesQueryIfInvalidTopicInTrans() throws Throwable {
        readableTopicState.reset();
        final var state = MapReadableKVState.<Long, Topic>builder(ConsensusService.NAME, TOPICS_KEY)
                .build();
        given(readableStates.<Long, Topic>get(TOPICS_KEY)).willReturn(state);
        final var store = new ReadableTopicStoreImpl(readableStates, readableEntityCounters);

        final var query = createEmptyGetTopicInfoQuery();
        when(context.query()).thenReturn(query);
        when(context.createStore(ReadableTopicStore.class)).thenReturn(store);

        assertThatThrownBy(() -> subject.validate(context))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(ResponseCodeEnum.INVALID_TOPIC_ID));
    }

    @Test
    @DisplayName("deleted topic is not valid")
    void validatesQueryIfDeletedTopic() throws Throwable {
        givenValidTopic(autoRenewId, true);
        readableTopicState = readableTopicState();
        given(readableStates.<TopicID, Topic>get(TOPICS_KEY)).willReturn(readableTopicState);
        readableStore = new ReadableTopicStoreImpl(readableStates, readableEntityCounters);

        final var query = createGetTopicInfoQuery(topicEntityNum);
        when(context.query()).thenReturn(query);
        when(context.createStore(ReadableTopicStore.class)).thenReturn(readableStore);

        assertThatThrownBy(() -> subject.validate(context))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(ResponseCodeEnum.INVALID_TOPIC_ID));
    }

    @Test
    @DisplayName("failed response is correctly handled in findResponse")
    void getsResponseIfFailedResponse() {
        final var responseHeader = ResponseHeader.newBuilder()
                .nodeTransactionPrecheckCode(ResponseCodeEnum.FAIL_FEE)
                .build();

        final var query = createGetTopicInfoQuery(topicEntityNum);
        when(context.query()).thenReturn(query);
        when(context.createStore(ReadableTopicStore.class)).thenReturn(readableStore);

        final var config =
                HederaTestConfigBuilder.create().withValue("ledger.id", "0x03").getOrCreateConfig();
        given(context.configuration()).willReturn(config);

        final var response = subject.findResponse(context, responseHeader);
        final var op = response.consensusGetTopicInfoOrThrow();
        assertEquals(ResponseCodeEnum.FAIL_FEE, op.header().nodeTransactionPrecheckCode());
        assertNull(op.topicInfo());
    }

    @Test
    @DisplayName("OK response is correctly handled in findResponse")
    void getsResponseIfOkResponse() {
        givenValidTopic();
        final var responseHeader = ResponseHeader.newBuilder()
                .nodeTransactionPrecheckCode(ResponseCodeEnum.OK)
                .build();
        final var expectedInfo = getExpectedInfo();

        final var query = createGetTopicInfoQuery(topicEntityNum);
        when(context.query()).thenReturn(query);
        when(context.createStore(ReadableTopicStore.class)).thenReturn(readableStore);

        final var config =
                HederaTestConfigBuilder.create().withValue("ledger.id", "0x03").getOrCreateConfig();
        given(context.configuration()).willReturn(config);

        final var response = subject.findResponse(context, responseHeader);
        final var topicInfoResponse = response.consensusGetTopicInfoOrThrow();
        assertEquals(ResponseCodeEnum.OK, topicInfoResponse.header().nodeTransactionPrecheckCode());
        assertEquals(expectedInfo, topicInfoResponse.topicInfo());
    }

    private ConsensusTopicInfo getExpectedInfo() {
        return ConsensusTopicInfo.newBuilder()
                .memo(topic.memo())
                .adminKey(key)
                .runningHash(Bytes.wrap("runningHash"))
                .sequenceNumber(topic.sequenceNumber())
                .expirationTime(Timestamp.newBuilder().seconds(topic.expirationSecond()))
                .submitKey(key)
                .autoRenewAccount(topic.autoRenewAccountId())
                .autoRenewPeriod(WELL_KNOWN_AUTO_RENEW_PERIOD)
                .ledgerId(new BytesConverter().convert("0x03"))
                .feeScheduleKey(feeScheduleKey)
                .feeExemptKeyList(key, anotherKey)
                .customFees(customFees)
                .build();
    }

    private Query createGetTopicInfoQuery(final long topicId) {
        final var data = ConsensusGetTopicInfoQuery.newBuilder()
                .topicID(TopicID.newBuilder().topicNum(topicId).build())
                .header(QueryHeader.newBuilder().payment(Transaction.DEFAULT).build())
                .build();

        return Query.newBuilder().consensusGetTopicInfo(data).build();
    }

    private Query createEmptyGetTopicInfoQuery() {
        final var data = ConsensusGetTopicInfoQuery.newBuilder()
                .header(QueryHeader.newBuilder().payment(Transaction.DEFAULT).build())
                .build();

        return Query.newBuilder().consensusGetTopicInfo(data).build();
    }
}
