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

import com.hedera.hapi.node.state.hints.PreprocessedKeys;
import com.hedera.node.app.hints.HintsLibrary;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Utility to extract information from byte arrays returned by the {@link HintsLibrary}, encode protobuf
 * messages in the form the library expects, and so on.
 */
@Singleton
public class HintsLibraryCodec {
    @Inject
    public HintsLibraryCodec() {
        // Dagger2
    }

    /**
     * A structured representation of the output of {@link HintsLibrary#updateCrs(Bytes, Bytes)}.
     * @param crs the updated CRS
     * @param proof the proof of the update
     */
    public record CrsUpdateOutput(@NonNull Bytes crs, @NonNull Bytes proof) {
        public CrsUpdateOutput {
            requireNonNull(crs);
            requireNonNull(proof);
        }
    }

    /**
     * Decodes the output of {@link HintsLibrary#updateCrs(Bytes, Bytes)} into a
     * {@link CrsUpdateOutput}.
     *
     * @param output the output of the {@link HintsLibrary#updateCrs(Bytes, Bytes)}
     * @return the hinTS key
     */
    public CrsUpdateOutput decodeCrsUpdate(@NonNull final Bytes output) {
        requireNonNull(output);
        throw new UnsupportedOperationException("Not implemented");
    }

    /**
     * Encodes the given public key and hints into a hinTS key for use with the {@link HintsLibrary}.
     *
     * @param blsPublicKey the BLS public key
     * @param hints the hints for the corresponding BLS private key
     * @return the hinTS key
     */
    public Bytes encodeHintsKey(@NonNull final Bytes blsPublicKey, @NonNull final Bytes hints) {
        requireNonNull(blsPublicKey);
        requireNonNull(hints);
        throw new UnsupportedOperationException("Not implemented");
    }

    /**
     * Extracts the aggregation key from the given preprocessed keys.
     *
     * @param preprocessedKeys the preprocessed keys
     * @return the aggregation key
     */
    public Bytes extractAggregationKey(@NonNull final Bytes preprocessedKeys) {
        requireNonNull(preprocessedKeys);
        throw new UnsupportedOperationException("Not implemented");
    }

    /**
     * Extracts the verification key from the given preprocessed keys.
     *
     * @param preprocessedKeys the preprocessed keys
     * @return the verification key
     */
    public Bytes extractVerificationKey(@NonNull final Bytes preprocessedKeys) {
        requireNonNull(preprocessedKeys);
        throw new UnsupportedOperationException("Not implemented");
    }

    /**
     * Extracts the public key for the given party id from the given aggregation key.
     *
     * @param aggregationKey the aggregation key
     * @param partyId the party id
     * @return the public key, or null if the party id is not present
     */
    @Nullable
    public Bytes extractPublicKey(@NonNull final Bytes aggregationKey, final int partyId) {
        requireNonNull(aggregationKey);
        throw new UnsupportedOperationException("Not implemented");
    }

    /**
     * Extracts the weight of the given party id from the given aggregation key.
     *
     * @param aggregationKey the aggregation key
     * @param partyId the party id
     * @return the weight
     */
    public long extractWeight(@NonNull final Bytes aggregationKey, final int partyId) {
        requireNonNull(aggregationKey);
        throw new UnsupportedOperationException("Not implemented");
    }

    /**
     * Extracts the total weight of all parties from the given verification key.
     *
     * @param verificationKey the verification key
     * @return the total weight
     */
    public long extractTotalWeight(@NonNull final Bytes verificationKey) {
        requireNonNull(verificationKey);
        throw new UnsupportedOperationException("Not implemented");
    }

    /**
     * Decodes the given preprocessed keys into a {@link PreprocessedKeys} object.
     *
     * @param preprocessedKeys the preprocessed keys, encoded by the library
     * @return the decoded preprocessed keys
     */
    public PreprocessedKeys decodePreprocessedKeys(@NonNull final Bytes preprocessedKeys) {
        requireNonNull(preprocessedKeys);
        throw new UnsupportedOperationException("Not implemented");
    }
}
