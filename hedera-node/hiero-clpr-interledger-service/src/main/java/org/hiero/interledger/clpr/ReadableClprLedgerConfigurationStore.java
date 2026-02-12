// SPDX-License-Identifier: Apache-2.0
package org.hiero.interledger.clpr;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hiero.hapi.interledger.state.clpr.ClprLedgerConfiguration;
import org.hiero.hapi.interledger.state.clpr.ClprLedgerId;

/**
 * Provides read-only methods for interacting with the underlying data storage mechanisms for
 * CLPR ledger configurations.
 */
public interface ReadableClprLedgerConfigurationStore {
    /**
     * Fetches an {@link ClprLedgerConfiguration} object from state for given {@link ClprLedgerId}.
     * If there is no entry for the ledger id, {@code null} is returned.
     *
     * @param ledgerId the ledger id of the clpr ledger configuration to retrieve.
     * @return {@link ClprLedgerConfiguration} object if successfully fetched or {@code null} if the configuration
     * does not exist.
     */
    @Nullable
    ClprLedgerConfiguration get(@NonNull ClprLedgerId ledgerId);
}
