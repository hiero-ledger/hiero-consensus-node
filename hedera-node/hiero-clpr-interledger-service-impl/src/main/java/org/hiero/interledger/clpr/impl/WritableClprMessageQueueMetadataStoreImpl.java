// SPDX-License-Identifier: Apache-2.0
package org.hiero.interledger.clpr.impl;

import static java.util.Objects.requireNonNull;
import static org.hiero.interledger.clpr.impl.schemas.V0700ClprSchema.CLPR_MESSAGE_QUEUE_METADATA_STATE_ID;

import com.swirlds.state.spi.WritableKVState;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.hapi.interledger.state.clpr.ClprLedgerId;
import org.hiero.hapi.interledger.state.clpr.ClprMessageQueueMetadata;
import org.hiero.interledger.clpr.WritableClprMessageQueueMetadataStore;

public class WritableClprMessageQueueMetadataStoreImpl extends ReadableClprMessageQueueMetadataStoreImpl
        implements WritableClprMessageQueueMetadataStore {

    private final WritableKVState<ClprLedgerId, ClprMessageQueueMetadata> writableMessageQueueState;

    public WritableClprMessageQueueMetadataStoreImpl(@NonNull final WritableStates states) {
        super(states);
        writableMessageQueueState = states.get(CLPR_MESSAGE_QUEUE_METADATA_STATE_ID);
    }

    @Override
    public void put(@NonNull ClprLedgerId ledgerId, @NonNull ClprMessageQueueMetadata messageQueueMetadata) {
        requireNonNull(ledgerId, "ledgerId must not be null");
        requireNonNull(messageQueueMetadata, "messageQueueMetadata must not be null");
        writableMessageQueueState.put(ledgerId, messageQueueMetadata);
    }
}
