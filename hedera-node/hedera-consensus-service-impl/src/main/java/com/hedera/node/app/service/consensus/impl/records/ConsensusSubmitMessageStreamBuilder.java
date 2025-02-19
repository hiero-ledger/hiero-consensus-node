/*
 * Copyright (C) 2023-2025 Hedera Hashgraph, LLC
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

package com.hedera.node.app.service.consensus.impl.records;

import com.hedera.hapi.node.transaction.AssessedCustomFee;
import com.hedera.node.app.service.token.records.CryptoTransferStreamBuilder;
import com.hedera.node.app.spi.workflows.record.StreamBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;

/**
 * A {@code StreamBuilder} specialization for tracking the side effects of a {@code ConsensusSubmitMessage}
 * transaction.
 */
public interface ConsensusSubmitMessageStreamBuilder extends StreamBuilder {
    /**
     * Tracks the sequence number for the topic receiving the submitted message in the associated transaction.
     *
     * @param topicSequenceNumber the new sequence number of the topic
     * @return this builder
     */
    @NonNull
    ConsensusSubmitMessageStreamBuilder topicSequenceNumber(long topicSequenceNumber);

    /**
     * Tracks the running hash for the topic receiving the submitted message in the associated transaction.
     *
     * @param topicRunningHash the new running hash of the topic
     * @return this builder
     */
    @NonNull
    ConsensusSubmitMessageStreamBuilder topicRunningHash(@NonNull Bytes topicRunningHash);

    /**
     * Tracks the running hash version for the topic receiving the submitted message in the associated transaction.
     *
     * @param topicRunningHashVersion the running hash version used to compute the new running hash
     * @return this builder
     */
    @NonNull
    ConsensusSubmitMessageStreamBuilder topicRunningHashVersion(long topicRunningHashVersion);

    /**
     * Tracks the total custom fees assessed in the transaction.
     * @param assessedCustomFees the total custom fees assessed in the transaction
     * @return this builder
     */
    @NonNull
    CryptoTransferStreamBuilder assessedCustomFees(@NonNull List<AssessedCustomFee> assessedCustomFees);
}
