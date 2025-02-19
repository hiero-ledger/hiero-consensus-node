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

import com.hedera.hapi.node.state.history.History;
import com.hedera.node.app.history.HistoryLibrary;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;

/**
 * Utility to extract information from byte arrays returned by the {@link HistoryLibrary}, encode protobuf
 * messages in the form the library expects, and so on.
 */
public interface HistoryLibraryCodec {
    /**
     * Encodes the given address book hash and metadata into a history record to be signed via
     * {@link HistoryLibrary#signSchnorr(Bytes, Bytes)}.
     *
     * @param history the history record to encode
     * @return the bytes for signing
     */
    @NonNull
    Bytes encodeHistory(@NonNull History history);

    /**
     * Encodes the given roster and public keys into an address book for use with the {@link HistoryLibrary}.
     *
     * @param weights the roster
     * @param publicKeys the available Schnorr public keys for the nodes in the roster
     * @return the history address book
     */
    @NonNull
    Bytes encodeAddressBook(@NonNull Map<Long, Long> weights, @NonNull Map<Long, Bytes> publicKeys);

    /**
     * Encodes the given roster and public keys into an address book for use with the {@link HistoryLibrary}.
     *
     * @param addressBookHash the hash of the first address book to use TSS
     * @param snarkVerificationKey the verification key for the SNARK used to prove address book transitions
     * @return the history address book
     */
    @NonNull
    Bytes encodeLedgerId(@NonNull Bytes addressBookHash, @NonNull Bytes snarkVerificationKey);
}
