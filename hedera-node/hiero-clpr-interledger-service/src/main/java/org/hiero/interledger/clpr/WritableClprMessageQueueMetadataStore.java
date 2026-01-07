// SPDX-License-Identifier: Apache-2.0
package org.hiero.interledger.clpr;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.hapi.interledger.state.clpr.ClprLedgerId;
import org.hiero.hapi.interledger.state.clpr.ClprMessageQueueMetadata;

/**
 * A writable store that wraps a writable key-value state and supports operations required to create or update
 * CLPR message queue metadata.
 */
public interface WritableClprMessageQueueMetadataStore extends ReadableClprMessageQueueMetadataStore {

    /**
     * Creates or updates the metadata for a specific CLPR message queue.
     *
     * @param ledgerId the unique identifier of the ledger to associate with this metadata
     * @param messageQueueMetadata the metadata object to persist in the state
     */
    void put(@NonNull ClprLedgerId ledgerId, @NonNull ClprMessageQueueMetadata messageQueueMetadata);
}
