// SPDX-License-Identifier: Apache-2.0
package org.hiero.interledger.clpr;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.hapi.interledger.state.clpr.ClprLocalLedgerMetadata;

/**
 * Writable access to CLPR local ledger metadata.
 */
public interface WritableClprMetadataStore extends ReadableClprMetadataStore {
    /**
     * Stores the supplied metadata record.
     *
     * @param metadata the metadata to persist
     */
    void put(@NonNull ClprLocalLedgerMetadata metadata);
}
