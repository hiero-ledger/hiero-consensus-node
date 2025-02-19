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

import com.hedera.node.app.history.HistoryLibrary;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Map;

/**
 * Default implementation of the {@link HistoryLibrary}.
 */
public class HistoryLibraryImpl implements HistoryLibrary {
    @Override
    public Bytes snarkVerificationKey() {
        throw new AssertionError("Not implemented");
    }

    @Override
    public Bytes newSchnorrKeyPair() {
        throw new AssertionError("Not implemented");
    }

    @Override
    public Bytes signSchnorr(@NonNull final Bytes message, @NonNull final Bytes privateKey) {
        requireNonNull(message);
        requireNonNull(privateKey);
        throw new AssertionError("Not implemented");
    }

    @Override
    public boolean verifySchnorr(
            @NonNull final Bytes signature, @NonNull final Bytes message, @NonNull final Bytes publicKey) {
        requireNonNull(signature);
        requireNonNull(message);
        requireNonNull(publicKey);
        throw new AssertionError("Not implemented");
    }

    @Override
    public Bytes hashAddressBook(@NonNull final Bytes addressBook) {
        requireNonNull(addressBook);
        throw new AssertionError("Not implemented");
    }

    @NonNull
    @Override
    public Bytes proveChainOfTrust(
            @NonNull final Bytes ledgerId,
            @Nullable final Bytes sourceProof,
            @NonNull final Bytes sourceAddressBook,
            @NonNull Map<Long, Bytes> sourceSignatures,
            @NonNull final Bytes targetAddressBookHash,
            @NonNull final Bytes targetMetadata) {
        requireNonNull(ledgerId);
        requireNonNull(sourceAddressBook);
        requireNonNull(sourceSignatures);
        requireNonNull(targetAddressBookHash);
        requireNonNull(targetMetadata);
        throw new AssertionError("Not implemented");
    }

    @Override
    public boolean verifyChainOfTrust(
            @NonNull final Bytes ledgerId,
            @NonNull final Bytes addressBookHash,
            @NonNull final Bytes metadata,
            @NonNull final Bytes proof) {
        requireNonNull(ledgerId);
        requireNonNull(addressBookHash);
        requireNonNull(metadata);
        requireNonNull(proof);
        throw new AssertionError("Not implemented");
    }
}
