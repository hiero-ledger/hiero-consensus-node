// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.consensus.impl;

import static com.hedera.node.app.service.consensus.impl.schemas.V0490ConsensusSchema.TOPICS_STATE_ID;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.TopicID;
import com.hedera.hapi.node.state.consensus.Topic;
import com.hedera.node.app.hapi.utils.EntityType;
import com.hedera.node.app.service.consensus.ReadableTopicStore;
import com.hedera.node.app.spi.ids.ReadableEntityCounters;
import com.swirlds.state.spi.ReadableKVState;
import com.swirlds.state.spi.ReadableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Provides read-only methods for interacting with the underlying data storage mechanisms for
 * working with Topics.
 *
 * <p>This class is not exported from the module. It is an internal implementation detail.
 */
public class ReadableTopicStoreImpl implements ReadableTopicStore {

    /** The underlying data storage class that holds the topic data. */
    private final ReadableKVState<TopicID, Topic> topicState;

    private final ReadableEntityCounters entityCounters;

    /**
     * Create a new {@link ReadableTopicStoreImpl} instance.
     *
     * @param states The state to use.
     */
    public ReadableTopicStoreImpl(
            @NonNull final ReadableStates states, @NonNull final ReadableEntityCounters entityCounters) {

        requireNonNull(states);
        this.entityCounters = requireNonNull(entityCounters);
        this.topicState = states.get(TOPICS_STATE_ID);
    }

    /**
     * Returns the topic needed. If the topic doesn't exist returns failureReason. If the
     * topic exists , the failure reason will be null.
     *
     * @param id topic id being looked up
     * @return topic
     */
    @Override
    @Nullable
    public Topic getTopic(@NonNull final TopicID id) {
        requireNonNull(id);
        return topicState.get(id);
    }

    /**
     * Returns the number of topics in the state.
     * @return the number of topics in the state
     */
    public long sizeOfState() {
        return entityCounters.getCounterFor(EntityType.TOPIC);
    }

    protected <T extends ReadableKVState<TopicID, Topic>> T topicState() {
        return (T) topicState;
    }
}
