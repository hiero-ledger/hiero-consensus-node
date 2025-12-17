// SPDX-License-Identifier: Apache-2.0
package org.hiero.interledger.clpr.impl;

import static java.util.Objects.requireNonNull;
import static org.hiero.interledger.clpr.impl.schemas.V0700ClprSchema.CLPR_MESSAGE_QUEUES_STATE_ID;

import com.swirlds.state.spi.ReadableKVState;
import com.swirlds.state.spi.ReadableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hiero.hapi.interledger.state.clpr.ClprLedgerId;
import org.hiero.hapi.interledger.state.clpr.ClprMessageQueueMetadata;
import org.hiero.interledger.clpr.ReadableClprMessageQueueStore;

public class ReadableClprMessageQueueStoreImpl implements ReadableClprMessageQueueStore {

    private final ReadableKVState<ClprLedgerId, ClprMessageQueueMetadata> messageQueueState;

    public ReadableClprMessageQueueStoreImpl(@NonNull final ReadableStates states) {
        requireNonNull(states);
        messageQueueState = states.get(CLPR_MESSAGE_QUEUES_STATE_ID);
    }

    @Override
    public @Nullable ClprMessageQueueMetadata get(@NonNull ClprLedgerId ledgerId) {
        requireNonNull(ledgerId);
        return messageQueueState.get(ledgerId);
    }
}
