// SPDX-License-Identifier: Apache-2.0
package org.hiero.interledger.clpr.impl;

import static java.util.Objects.requireNonNull;
import static org.hiero.interledger.clpr.impl.schemas.V0700ClprSchema.CLPR_LEDGER_METADATA_STATE_ID;

import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.hapi.interledger.state.clpr.ClprLocalLedgerMetadata;
import org.hiero.interledger.clpr.WritableClprMetadataStore;

/**
 * Writable metadata store for CLPR local ledger metadata.
 */
public class WritableClprMetadataStoreImpl extends ReadableClprMetadataStoreImpl implements WritableClprMetadataStore {
    private final WritableSingletonState<ClprLocalLedgerMetadata> metadataState;

    public WritableClprMetadataStoreImpl(@NonNull final WritableStates states) {
        super(states);
        this.metadataState = states.getSingleton(CLPR_LEDGER_METADATA_STATE_ID);
    }

    @Override
    public void put(@NonNull final ClprLocalLedgerMetadata metadata) {
        requireNonNull(metadata);
        metadataState.put(metadata);
    }
}
