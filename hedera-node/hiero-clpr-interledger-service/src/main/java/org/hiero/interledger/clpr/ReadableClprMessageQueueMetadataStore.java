// SPDX-License-Identifier: Apache-2.0
package org.hiero.interledger.clpr;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hiero.hapi.interledger.state.clpr.ClprLedgerId;
import org.hiero.hapi.interledger.state.clpr.ClprMessageQueueMetadata;

/**
 * Provides read-only methods for interacting with the underlying data storage mechanisms for
 * working with CLPR Message queue metadata.
 */
public interface ReadableClprMessageQueueMetadataStore {

    /**
     * Retrieves the metadata for a specific message queue identified by its ledger ID.
     *
     * @param ledgerId the unique identifier of the ledger whose queue metadata is requested
     * @return the {@link ClprMessageQueueMetadata} if found; {@code null} otherwise
     */
    @Nullable
    ClprMessageQueueMetadata get(@NonNull ClprLedgerId ledgerId);
}
