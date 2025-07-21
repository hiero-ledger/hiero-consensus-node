// SPDX-License-Identifier: Apache-2.0
package org.hiero.interledger.clpr.impl;

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
 * CLPR ledger configuration as a result of setRemoteLedgerConfiguration transactions and updated configuration
 * of this ledger through address book changes.
 */
public class WritableClprLedgerConfigurationStoreImpl extends ReadableClprLedgerConfigurationStoreImpl implements WritableClprLedgerConfigurationStore {
    private static final Logger logger = LogManager.getLogger(WritableClprLedgerConfigurationStoreImpl.class);

    private final WritableKVState<ClprLedgerId, ClprLedgerConfiguration> ledgerConfigurationsMutable;

    /**
     * Create a new {@link WritableClprLedgerConfigurationStoreImpl} instance.
     *
     * @param states The state to use.
     */
    public WritableClprLedgerConfigurationStoreImpl(
            @NonNull final WritableStates states, final WritableEntityCounters entityCounters) {
        super(states);
        //TODO: This needs to be updated with a schema sourced key.
        ledgerConfigurationsMutable = states.get("ClprLedgerConfigurations");
    }

    @Override
    public void setLedgerConfiguration(@NonNull final ClprLedgerConfiguration ledgerConfiguration) {
        throw new UnsupportedOperationException("Not supported yet.");
    }
}
