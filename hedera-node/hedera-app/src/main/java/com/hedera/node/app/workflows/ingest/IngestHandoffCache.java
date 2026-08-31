// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.ingest;

import static java.util.Objects.requireNonNull;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Node-local cache of ingest parse/verify results, keyed by the serialized signed transaction
 * submitted to the platform. Pre-handle on this node {@link #take}s the entry so it is used once.
 */
@Singleton
public final class IngestHandoffCache {
    private final ConcurrentHashMap<Bytes, IngestHandoff> handoffs = new ConcurrentHashMap<>();

    @Inject
    public IngestHandoffCache() {}

    public void put(@NonNull final Bytes serializedSignedTx, @NonNull final IngestHandoff handoff) {
        handoffs.put(requireNonNull(serializedSignedTx), requireNonNull(handoff));
    }

    @Nullable
    public IngestHandoff take(@NonNull final Bytes serializedSignedTx) {
        return handoffs.remove(requireNonNull(serializedSignedTx));
    }

    public void remove(@NonNull final Bytes serializedSignedTx) {
        handoffs.remove(requireNonNull(serializedSignedTx));
    }
}
