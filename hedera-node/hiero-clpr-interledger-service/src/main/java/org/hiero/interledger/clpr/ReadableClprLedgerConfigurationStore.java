// SPDX-License-Identifier: Apache-2.0
package org.hiero.interledger.clpr;

import org.hiero.hapi.interledger.state.clpr.ClprLedgerId;
import org.hiero.hapi.interledger.state.clpr.ClprLedgerConfiguration;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Provides read-only methods for interacting with the underlying data storage mechanisms for
 * CLPR ledger configurations.
 */
public interface ReadableClprLedgerConfigurationStore {
    /**
     * Fetches an {@link ClprLedgerConfiguration} object from state for given {@link ClprLedgerId}.
     * If the ledger id is not provided, the CLPR configuration for this ledger is provided.
     * If there is no entry for the ledger id, {@code null} is returned.
     *
     * @param ledgerId the ledger id of the clpr ledger configuration to retrieve.
     * @return {@link ClprLedgerConfiguration} object if successfully fetched or {@code null} if the configuration
     * wdoes not exist.
     */
    @Nullable ClprLedgerConfiguration get(@Nullable ClprLedgerId ledgerId);
}
