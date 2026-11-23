// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl;

import static com.hedera.node.app.service.clpr.impl.schemas.V0780ClprSchema.CONNECTOR_REGISTRATION_HISTORY_STATE_ID;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.clpr.ClprConnectorKey;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.spi.WritableKVState;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Records each channel and connector ID pair permanently, including after deregistration or a ban.
 * The existing state also holds legacy 32-byte commitments. New keys concatenate two 32-byte IDs.
 */
public class WritableConnectorRegistrationStore {

    private final WritableKVState<ProtoBytes, ProtoBytes> state;

    public WritableConnectorRegistrationStore(@NonNull final WritableStates states) {
        requireNonNull(states);
        this.state = states.get(CONNECTOR_REGISTRATION_HISTORY_STATE_ID);
    }

    public boolean contains(@NonNull final ClprConnectorKey connector) {
        return state.contains(keyFor(connector));
    }

    public void put(@NonNull final ClprConnectorKey connector) {
        state.put(keyFor(connector), ProtoBytes.DEFAULT);
    }

    private static ProtoBytes keyFor(final ClprConnectorKey connector) {
        return new ProtoBytes(Bytes.merge(connector.channelId(), connector.connectorId()));
    }
}
