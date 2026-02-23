// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.consensus.impl.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.node.base.TopicID;
import com.hedera.hapi.node.state.consensus.Topic;
import com.hedera.node.app.service.consensus.impl.WritableTopicStore;
import com.hedera.node.app.service.consensus.impl.test.handlers.ConsensusTestBase;
import com.hedera.node.app.service.entityid.WritableEntityCounters;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WritableTopicStoreTest extends ConsensusTestBase {
    @Mock
    private WritableEntityCounters entityCounters;

    private Topic topic;

    @Test
    void throwsIfNullValuesAsArgs() {
        assertThrows(NullPointerException.class, () -> new WritableTopicStore(null, entityCounters));
        assertThrows(NullPointerException.class, () -> new WritableTopicStore(writableStates, null));
        assertThrows(NullPointerException.class, () -> writableStore.put(null));
    }

    @Test
    void constructorCreatesTopicState() {
        final var store = new WritableTopicStore(writableStates, entityCounters);
        assertNotNull(store);
    }

    @Test
    void commitsTopicChanges() {
        topic = createTopic();
        assertFalse(writableTopicState.contains(topicId));

        writableStore.put(topic);

        assertTrue(writableTopicState.contains(topicId));
        final var writtenTopic = writableTopicState.get(topicId);
        assertEquals(topic, writtenTopic);
    }

    @Test
    void getReturnsTopic() {
        topic = createTopic();
        writableStore.put(topic);

        final var maybeReadTopic = writableStore.getTopic(
                TopicID.newBuilder().topicNum(topicEntityNum).build());

        assertNotNull(maybeReadTopic);
        assertEquals(topic, maybeReadTopic);
    }
}
