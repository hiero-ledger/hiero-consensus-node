/*
 * Copyright (C) 2022-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hedera.node.app.service.consensus.impl;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.TopicID;
import com.hedera.hapi.node.state.consensus.Topic;
import com.hedera.node.app.hapi.utils.EntityType;
import com.hedera.node.app.spi.ids.WritableEntityCounters;
import com.swirlds.state.spi.WritableKVState;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

/**
 * Provides write methods for modifying underlying data storage mechanisms for
 * working with Topics.
 *
 * <p>This class is not exported from the module. It is an internal implementation detail.
 * This class is not complete, it will be extended with other methods like remove, update etc.,
 */
public class WritableTopicStore extends ReadableTopicStoreImpl {
    private final WritableEntityCounters entityCounters;
    /**
     * Create a new {@link WritableTopicStore} instance.
     *
     * @param states The state to use.
     */
    public WritableTopicStore(
            @NonNull final WritableStates states, @NonNull final WritableEntityCounters entityCounters) {
        super(states, entityCounters);
        this.entityCounters = entityCounters;
    }

    @Override
    protected WritableKVState<TopicID, Topic> topicState() {
        return super.topicState();
    }

    /**
     * Persists an updated {@link Topic} into the state, as well as exporting its ID to the transaction
     * receipt. If a topic with the same ID already exists, it will be overwritten.
     *
     * @param topic - the topic to be persisted.
     */
    public void put(@NonNull final Topic topic) {
        requireNonNull(topic);
        requireNonNull(topic.topicId());
        topicState().put(topic.topicId(), topic);
    }

    /**
     * Persists a new {@link Topic} into the state, as well as exporting its ID to the transaction
     * receipt. It also increments the entity count for {@link EntityType#TOPIC}.
     *
     * @param topic - the topic to be persisted.
     */
    public void putAndIncrementCount(@NonNull final Topic topic) {
        put(topic);
        entityCounters.incrementEntityTypeCount(EntityType.TOPIC);
    }

    /**
     * Returns the {@link Topic} with the given number using {@link WritableKVState#get}.
     * If no such topic exists, returns {@code null}
     *
     * @param topicID - the id of the topic to be retrieved.
     * @return the retrieved topic
     */
    public Topic get(@NonNull final TopicID topicID) {
        requireNonNull(topicID);
        return topicState().get(topicID);
    }

    /**
     * Returns the set of topics modified in existing state.
     * @return the set of topics modified in existing state
     */
    public Set<TopicID> modifiedTopics() {
        return topicState().modifiedKeys();
    }
}
