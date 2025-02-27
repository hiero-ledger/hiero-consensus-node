// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.consensus;

import com.hedera.hapi.node.base.TopicID;
import com.hedera.hapi.node.state.consensus.Topic;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Provides read-only methods for interacting with the underlying data storage mechanisms for
 * working with Topics.
 *
 * <p>This class is not exported from the module. It is an internal implementation detail.
 */
public interface ReadableTopicStore {

    /**
     * Returns the topic needed. If the topic doesn't exist returns failureReason. If the
     * topic exists , the failure reason will be null.
     *
     * @param id topic id being looked up
     * @return topic's metadata
     */
    @Nullable
    Topic getTopic(@NonNull TopicID id);

    /**
     * Returns the number of topics in the state.
     * @return the number of topics in the state
     */
    long sizeOfState();
}
