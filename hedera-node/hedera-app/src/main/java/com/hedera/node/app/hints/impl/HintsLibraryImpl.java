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

package com.hedera.node.app.hints.impl;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.hints.HintsLibrary;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;

/**
 * Default implementation of {@link HintsLibrary}.
 */
public class HintsLibraryImpl implements HintsLibrary {
    @Override
    public Bytes newCrs(final int n) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Bytes updateCrs(@NonNull final Bytes crs, @NonNull final Bytes entropy) {
        requireNonNull(crs);
        requireNonNull(entropy);
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean verifyCrsUpdate(@NonNull Bytes oldCrs, @NonNull Bytes newCrs, @NonNull Bytes proof) {
        requireNonNull(oldCrs);
        requireNonNull(newCrs);
        requireNonNull(proof);
        throw new UnsupportedOperationException();
    }

    @Override
    public Bytes newBlsKeyPair() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Bytes computeHints(@NonNull final Bytes blsPrivateKey, final int partyId, final int n) {
        requireNonNull(blsPrivateKey);
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean validateHintsKey(@NonNull final Bytes hintsKey, final int partyId, final int n) {
        requireNonNull(hintsKey);
        throw new UnsupportedOperationException();
    }

    @Override
    public Bytes preprocess(
            @NonNull final Map<Integer, Bytes> hintsKeys, @NonNull final Map<Integer, Long> weights, final int n) {
        requireNonNull(hintsKeys);
        requireNonNull(weights);
        throw new UnsupportedOperationException();
    }

    @Override
    public Bytes signBls(@NonNull final Bytes message, @NonNull final Bytes privateKey) {
        requireNonNull(message);
        requireNonNull(privateKey);
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean verifyBls(
            @NonNull final Bytes signature, @NonNull final Bytes message, @NonNull final Bytes publicKey) {
        requireNonNull(signature);
        requireNonNull(message);
        requireNonNull(publicKey);
        throw new UnsupportedOperationException();
    }

    @Override
    public Bytes aggregateSignatures(
            @NonNull final Bytes aggregationKey,
            @NonNull final Bytes verificationKey,
            @NonNull final Map<Integer, Bytes> partialSignatures) {
        requireNonNull(aggregationKey);
        requireNonNull(verificationKey);
        requireNonNull(partialSignatures);
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean verifyAggregate(
            @NonNull final Bytes signature,
            @NonNull final Bytes message,
            @NonNull final Bytes verificationKey,
            final long thresholdNumerator,
            long thresholdDenominator) {
        requireNonNull(signature);
        requireNonNull(message);
        requireNonNull(verificationKey);
        throw new UnsupportedOperationException();
    }
}
