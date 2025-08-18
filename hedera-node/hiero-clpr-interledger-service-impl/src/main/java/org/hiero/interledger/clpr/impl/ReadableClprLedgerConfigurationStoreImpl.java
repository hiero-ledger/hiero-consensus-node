// SPDX-License-Identifier: Apache-2.0
package org.hiero.interledger.clpr.impl;

import static java.util.Objects.requireNonNull;
import static org.hiero.interledger.clpr.impl.schemas.V0650ClprSchema.CLPR_LEDGER_CONFIGURATION_KEY;

import com.hedera.node.app.spi.ids.ReadableEntityCounters;
import com.swirlds.state.spi.ReadableKVState;
import com.swirlds.state.spi.ReadableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hiero.hapi.interledger.state.clpr.ClprLedgerConfiguration;
import org.hiero.hapi.interledger.state.clpr.ClprLedgerId;
import org.hiero.interledger.clpr.ReadableClprLedgerConfigurationStore;

/**
 * Provides read-only methods for interacting with the underlying data storage mechanisms for
 * working with CLPR Ledger Configurations.
 */
public class ReadableClprLedgerConfigurationStoreImpl implements ReadableClprLedgerConfigurationStore {

    private final ReadableKVState<ClprLedgerId, ClprLedgerConfiguration> ledgerConfigurations;
    private final ReadableEntityCounters entityCounters;

    /**
     * Create a new {@link ReadableClprLedgerConfigurationStore} instance.
     *
     * @param states The state to use.
     */
    public ReadableClprLedgerConfigurationStoreImpl(
            @NonNull final ReadableStates states, @NonNull final ReadableEntityCounters entityCounters) {
        requireNonNull(states);
        ledgerConfigurations = states.get(CLPR_LEDGER_CONFIGURATION_KEY);
        this.entityCounters = requireNonNull(entityCounters);
    }

    @Override
    @Nullable
    public ClprLedgerConfiguration get(@NonNull final ClprLedgerId ledgerId) {
        requireNonNull(ledgerId);
        return ledgerConfigurations.get(ledgerId);
    }
}
