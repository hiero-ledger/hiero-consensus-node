// SPDX-License-Identifier: Apache-2.0
package org.hiero.interledger.clpr.impl;

import static java.util.Objects.requireNonNull;
import static org.hiero.interledger.clpr.impl.schemas.V0700ClprSchema.CLPR_LEDGER_METADATA_STATE_ID;

import com.swirlds.state.spi.ReadableSingletonState;
import com.swirlds.state.spi.ReadableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hiero.hapi.interledger.state.clpr.ClprLocalLedgerMetadata;
import org.hiero.interledger.clpr.ReadableClprMetadataStore;

/**
 * Read-only metadata store for CLPR local ledger metadata.
 */
public class ReadableClprMetadataStoreImpl implements ReadableClprMetadataStore {

    private final ReadableSingletonState<ClprLocalLedgerMetadata> metadataState;

    public ReadableClprMetadataStoreImpl(@NonNull final ReadableStates states) {
        requireNonNull(states);
        this.metadataState = states.getSingleton(CLPR_LEDGER_METADATA_STATE_ID);
    }

    @Override
    @Nullable
    public ClprLocalLedgerMetadata get() {
        return metadataState.get();
    }
}
