// SPDX-License-Identifier: Apache-2.0
package org.hiero.interledger.clpr.impl;

import static java.util.Objects.requireNonNull;
import static org.hiero.interledger.clpr.impl.schemas.V0700ClprSchema.CLPR_MESSAGE_QUEUE_METADATA_STATE_ID;

import com.swirlds.state.spi.ReadableKVState;
import com.swirlds.state.spi.ReadableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hiero.hapi.interledger.state.clpr.ClprLedgerId;
import org.hiero.hapi.interledger.state.clpr.ClprMessageQueueMetadata;
import org.hiero.interledger.clpr.ReadableClprMessageQueueMetadataStore;

/**
 * Provides read-only methods for interacting with the underlying data storage mechanisms for
 * working with CLPR Message queue metadata.
 */
public class ReadableClprMessageQueueMetadataStoreImpl implements ReadableClprMessageQueueMetadataStore {

    private final ReadableKVState<ClprLedgerId, ClprMessageQueueMetadata> messageQueueState;

    /**
     * Constructs a new {@code ReadableClprMessageQueueMetadataStoreImpl}.
     *
     * @param states the {@link ReadableStates} instance used to retrieve the
     * underlying message queue metadata state
     * @throws NullPointerException if {@code states} is {@code null}
     */
    public ReadableClprMessageQueueMetadataStoreImpl(@NonNull final ReadableStates states) {
        requireNonNull(states);
        messageQueueState = states.get(CLPR_MESSAGE_QUEUE_METADATA_STATE_ID);
    }

    /**
     * Retrieves the metadata for a specific message queue identified by its ledger ID.
     *
     * @param ledgerId the unique identifier of the ledger whose queue metadata is requested
     * @return the {@link ClprMessageQueueMetadata} if found; {@code null} otherwise
     * @throws NullPointerException if {@code ledgerId} is {@code null}
     */
    @Override
    public @Nullable ClprMessageQueueMetadata get(@NonNull ClprLedgerId ledgerId) {
        requireNonNull(ledgerId);
        return messageQueueState.get(ledgerId);
    }
}
