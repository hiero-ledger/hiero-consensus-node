/*
 * Copyright (C) 2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
