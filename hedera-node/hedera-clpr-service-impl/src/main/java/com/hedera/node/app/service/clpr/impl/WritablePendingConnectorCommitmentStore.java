// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl;

import static com.hedera.node.app.service.clpr.impl.schemas.V0770ClprSchema.PENDING_CONNECTOR_COMMITMENTS_STATE_ID;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.spi.WritableKVState;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Store for CLPR pending connector ownership commitments.
 * Acts as a set — the key and value are both the commitment hash.
 */
public class WritablePendingConnectorCommitmentStore {

    private final WritableKVState<ProtoBytes, ProtoBytes> state;

    public WritablePendingConnectorCommitmentStore(@NonNull final WritableStates states) {
        requireNonNull(states);
        this.state = states.get(PENDING_CONNECTOR_COMMITMENTS_STATE_ID);
    }

    /** Returns true if the commitment exists. */
    public boolean contains(@NonNull final Bytes commitment) {
        requireNonNull(commitment);
        return state.get(new ProtoBytes(commitment)) != null;
    }

    /** Adds a commitment. */
    public void put(@NonNull final Bytes commitment) {
        requireNonNull(commitment);
        final var key = new ProtoBytes(commitment);
        state.put(key, key);
    }

    /** Removes a commitment. */
    public void remove(@NonNull final Bytes commitment) {
        requireNonNull(commitment);
        state.remove(new ProtoBytes(commitment));
    }
}
