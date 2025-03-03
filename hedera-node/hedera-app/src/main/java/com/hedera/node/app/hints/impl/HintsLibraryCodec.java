// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints.impl;

import static java.util.Objects.requireNonNull;

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

    private static final int CRS_LENGTH = 1456;
    private static final int CONTRIBUTION_PROOF_LENGTH = 128;

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
        requireNonNull(output);
        final int expectedLength = CRS_LENGTH + CONTRIBUTION_PROOF_LENGTH;
        if (output.length() != expectedLength) {
            throw new IllegalArgumentException(
                    "Invalid output length: expected " + expectedLength + " but got " + output.length());
        }
        final Bytes crs = output.slice(0, CRS_LENGTH);
        final Bytes proof = output.slice(CRS_LENGTH, CONTRIBUTION_PROOF_LENGTH);
        return new CrsUpdateOutput(crs, proof);
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
}
