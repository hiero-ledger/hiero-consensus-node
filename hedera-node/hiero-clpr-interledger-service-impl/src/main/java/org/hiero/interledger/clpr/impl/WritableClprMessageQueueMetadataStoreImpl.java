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

/**
 * A writable store that wraps a writable key-value state and supports operations required to create or update
 * CLPR message queue metadata.
 */
public class WritableClprMessageQueueMetadataStoreImpl extends ReadableClprMessageQueueMetadataStoreImpl
        implements WritableClprMessageQueueMetadataStore {

    private final WritableKVState<ClprLedgerId, ClprMessageQueueMetadata> writableMessageQueueState;

    /**
     * Constructs a new {@code WritableClprMessageQueueMetadataStoreImpl}.
     *
     * @param states the {@link WritableStates} instance used to retrieve the
     * underlying writable message queue metadata state
     * @throws NullPointerException if {@code states} is {@code null}
     */
    public WritableClprMessageQueueMetadataStoreImpl(@NonNull final WritableStates states) {
        super(states);
        writableMessageQueueState = states.get(CLPR_MESSAGE_QUEUE_METADATA_STATE_ID);
    }

    /**
     * Creates or updates the metadata for a specific CLPR message queue.
     *
     * @param ledgerId the unique identifier of the ledger to associate with this metadata
     * @param messageQueueMetadata the metadata object to persist in the state
     * @throws NullPointerException if {@code ledgerId} or {@code messageQueueMetadata} is {@code null}
     */
    @Override
    public void put(@NonNull ClprLedgerId ledgerId, @NonNull ClprMessageQueueMetadata messageQueueMetadata) {
        requireNonNull(ledgerId, "ledgerId must not be null");
        requireNonNull(messageQueueMetadata, "messageQueueMetadata must not be null");
        writableMessageQueueState.put(ledgerId, messageQueueMetadata);
    }
}
