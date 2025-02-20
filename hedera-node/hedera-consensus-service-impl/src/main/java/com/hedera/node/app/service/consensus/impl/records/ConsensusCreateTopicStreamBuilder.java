// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.consensus.impl.records;

import com.hedera.hapi.node.base.TopicID;
import com.hedera.node.app.spi.workflows.record.StreamBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A {@code StreamBuilder} specialization for tracking the side effects of a {@code ConsensusCreateTopic} transaction.
 */
public interface ConsensusCreateTopicStreamBuilder extends StreamBuilder {
    /**
     * Tracks creation of a new topic by {@link TopicID}. Even if someday we support creating multiple topics within a
     * smart contract call, we will still only need to track one created topic per child record.
     *
     * @param topicID the {@link TopicID} the new topic
     * @return this builder
     */
    @NonNull
    ConsensusCreateTopicStreamBuilder topicID(@NonNull TopicID topicID);
}
