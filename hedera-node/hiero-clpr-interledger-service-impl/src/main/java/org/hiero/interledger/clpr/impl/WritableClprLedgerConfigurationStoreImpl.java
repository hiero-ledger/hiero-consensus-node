// SPDX-License-Identifier: Apache-2.0
package org.hiero.interledger.clpr.impl;

import static java.util.Objects.requireNonNull;
import static org.hiero.interledger.clpr.impl.schemas.V0650ClprSchema.CLPR_LEDGER_CONFIGURATIONS_STATE_ID;

import com.hedera.node.app.spi.ids.WritableEntityCounters;
import com.swirlds.state.spi.WritableKVState;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.hapi.interledger.state.clpr.ClprLedgerConfiguration;
import org.hiero.hapi.interledger.state.clpr.ClprLedgerId;
import org.hiero.interledger.clpr.WritableClprLedgerConfigurationStore;

/**
 * A writable store that wraps a writable key-value state and supports operations required to create or update
 * CLPR ledger configuration as a result of setLedgerConfiguration transactions and updated configuration
 * of this ledger through address book changes.
 */
public class WritableClprLedgerConfigurationStoreImpl extends ReadableClprLedgerConfigurationStoreImpl
        implements WritableClprLedgerConfigurationStore {
    private static final Logger logger = LogManager.getLogger(WritableClprLedgerConfigurationStoreImpl.class);

    private final WritableKVState<ClprLedgerId, ClprLedgerConfiguration> ledgerConfigurationsMutable;
    private final WritableEntityCounters entityCounters;

    /**
     * Create a new {@link WritableClprLedgerConfigurationStoreImpl} instance.
     *
     * @param states The state to use.
     */
    public WritableClprLedgerConfigurationStoreImpl(
            @NonNull final WritableStates states, @NonNull final WritableEntityCounters entityCounters) {
        super(states, entityCounters);
        ledgerConfigurationsMutable = states.get(CLPR_LEDGER_CONFIGURATIONS_STATE_ID);
        this.entityCounters = entityCounters;
    }

    @Override
    public void put(@NonNull final ClprLedgerConfiguration ledgerConfiguration) {
        final var ledgerId = requireNonNull(ledgerConfiguration.ledgerId());
        ledgerConfigurationsMutable.put(ledgerId, ledgerConfiguration);
    }
}
