// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.impl;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.history.History;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;

/**
 * Default implementation of {@link HistoryLibraryCodec}.
 */
public enum HistoryLibraryCodecImpl implements HistoryLibraryCodec {
    HISTORY_LIBRARY_CODEC;

    @Override
    public @NonNull Bytes encodeHistory(@NonNull final History history) {
        requireNonNull(history);
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public @NonNull Bytes encodeAddressBook(
            @NonNull final Map<Long, Long> weights, @NonNull final Map<Long, Bytes> publicKeys) {
        requireNonNull(weights);
        requireNonNull(publicKeys);
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public @NonNull Bytes encodeLedgerId(
            @NonNull final Bytes addressBookHash, @NonNull final Bytes snarkVerificationKey) {
        requireNonNull(addressBookHash);
        requireNonNull(snarkVerificationKey);
        throw new UnsupportedOperationException("Not implemented");
    }
}
