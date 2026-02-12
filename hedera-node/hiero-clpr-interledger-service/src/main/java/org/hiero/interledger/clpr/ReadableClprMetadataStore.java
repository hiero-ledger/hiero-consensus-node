// SPDX-License-Identifier: Apache-2.0
package org.hiero.interledger.clpr;

import edu.umd.cs.findbugs.annotations.Nullable;
import org.hiero.hapi.interledger.state.clpr.ClprLocalLedgerMetadata;

/**
 * Read-only access to CLPR local ledger metadata.
 */
public interface ReadableClprMetadataStore {
    /**
     * Returns the stored metadata for the local ledger, if present.
     *
     * @return metadata or {@code null} if not yet initialized
     */
    @Nullable
    ClprLocalLedgerMetadata get();
}
