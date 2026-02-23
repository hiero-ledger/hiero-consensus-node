// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.consensus.impl.test;

import static com.hedera.node.app.service.consensus.impl.schemas.V0490ConsensusSchema.TOPICS_STATE_ID;
import static com.hedera.node.app.service.consensus.impl.schemas.V0490ConsensusSchema.TOPICS_STATE_LABEL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.TopicID;
import com.hedera.hapi.node.state.consensus.Topic;
import com.hedera.node.app.hapi.utils.CommonPbjConverters;
import com.hedera.node.app.hapi.utils.EntityType;
import com.hedera.node.app.service.consensus.ReadableTopicStore;
import com.hedera.node.app.service.consensus.impl.ReadableTopicStoreImpl;
import com.hedera.node.app.service.consensus.impl.test.handlers.ConsensusTestBase;
import com.swirlds.state.test.fixtures.MapReadableKVState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReadableTopicStoreImplTest extends ConsensusTestBase {
    private ReadableTopicStore subject;

    @BeforeEach
    void setUp() {
        subject = new ReadableTopicStoreImpl(readableStates, readableEntityCounters);
    }

    @Test
    void getsTopicIfTopicExists() {
        givenValidTopic();
        final var topic = subject.getTopic(topicId);

        assertNotNull(topic);

        assertEquals(topicEntityNum, topic.topicId().topicNum());
        assertEquals(adminKey.toString(), topic.adminKey().toString());
        assertEquals(adminKey.toString(), topic.submitKey().toString());
        assertEquals(this.topic.sequenceNumber(), topic.sequenceNumber());
        assertEquals(this.topic.autoRenewPeriod(), topic.autoRenewPeriod());
        assertEquals(autoRenewId, topic.autoRenewAccountId());
        assertEquals(memo, topic.memo());
        assertFalse(topic.deleted());
        assertArrayEquals(runningHash, CommonPbjConverters.asBytes(topic.runningHash()));
    }

    @Test
    void getsTopicIfTopicExistsWithNoAutoRenewAccount() {
        final var accountId = AccountID.newBuilder().accountNum(0L).build();
        givenValidTopic(accountId);
        readableTopicState = readableTopicState();
        given(readableStates.<TopicID, Topic>get(TOPICS_STATE_ID)).willReturn(readableTopicState);
        readableStore = new ReadableTopicStoreImpl(readableStates, readableEntityCounters);
        subject = new ReadableTopicStoreImpl(readableStates, readableEntityCounters);

        final var topic = subject.getTopic(topicId);

        assertNotNull(topic);

        assertEquals(topicEntityNum, topic.topicId().topicNum());
        assertEquals(adminKey.toString(), topic.adminKey().toString());
        assertEquals(adminKey.toString(), topic.submitKey().toString());
        assertEquals(this.topic.sequenceNumber(), topic.sequenceNumber());
        assertEquals(this.topic.autoRenewPeriod(), topic.autoRenewPeriod());
        assertEquals(accountId, topic.autoRenewAccountId());
        assertEquals(memo, topic.memo());
        assertFalse(topic.deleted());
        assertArrayEquals(runningHash, CommonPbjConverters.asBytes(topic.runningHash()));
    }

    @Test
    void missingTopicIsNull() {
        readableTopicState.reset();
        final var state = MapReadableKVState.<Long, Topic>builder(TOPICS_STATE_ID, TOPICS_STATE_LABEL)
                .build();
        given(readableStates.<Long, Topic>get(TOPICS_STATE_ID)).willReturn(state);
        subject = new ReadableTopicStoreImpl(readableStates, readableEntityCounters);

        assertThat(subject.getTopic(topicId)).isNull();
    }

    @Test
    void constructorCreatesTopicState() {
        final var store = new ReadableTopicStoreImpl(readableStates, readableEntityCounters);
        assertNotNull(store);
    }

    @Test
    void nullArgsFail() {
        assertThrows(NullPointerException.class, () -> new ReadableTopicStoreImpl(null, null));
    }

    @Test
    void getSizeOfState() {
        final var store = new ReadableTopicStoreImpl(readableStates, readableEntityCounters);
        assertEquals(readableEntityCounters.getCounterFor(EntityType.TOPIC), store.sizeOfState());
    }
}
