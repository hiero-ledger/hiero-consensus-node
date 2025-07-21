// SPDX-License-Identifier: Apache-2.0
package org.hiero.interledger.clpr.impl;

import org.hiero.hapi.interledger.state.clpr.ClprLedgerConfiguration;
import org.hiero.hapi.interledger.state.clpr.ClprLedgerId;
import com.swirlds.state.spi.ReadableKVState;
import com.swirlds.state.spi.ReadableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hiero.interledger.clpr.ReadableClprLedgerConfigurationStore;

import static java.util.Objects.requireNonNull;

/**
 * Provides read-only methods for interacting with the underlying data storage mechanisms for
 * working with CLPR Ledger Configurations.
 */
public class ReadableClprLedgerConfigurationStoreImpl implements ReadableClprLedgerConfigurationStore {

    private final ReadableKVState<ClprLedgerId, ClprLedgerConfiguration> ledgerConfigurations;

    /**
     * Create a new {@link ReadableClprLedgerConfigurationStore} instance.
     *
     * @param states The state to use.
     */
    public ReadableClprLedgerConfigurationStoreImpl(@NonNull final ReadableStates states) {
        requireNonNull(states);
        //TODO: Need to create a schema for this following usage.
        ledgerConfigurations = states.get("ClprLedgerConfigurations");
    }


    /**
     * Get a {@link ClprLedgerConfiguration} referenced by {@link ClprLedgerId}.
     * If the CLPR Ledger Id is null, return the configuration for this ledger.
     * If the CLPR Ledger Id does not have a key, return {@code null}.
     *
     * @param ledgerId the clpr ledger id
     * @return The CLPR ledger configuration corresponding to the provided ledger id or null if it does not exist.
     */
    @Override
    @Nullable
    public ClprLedgerConfiguration get(@Nullable final ClprLedgerId ledgerId) {
        return null;
    }

}
