// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.impl;

import static com.hedera.node.app.blocks.impl.BlockImplUtils.hashInternalNode;
import static com.hedera.node.app.blocks.impl.BlockImplUtils.hashLeaf;
import static com.hedera.node.app.hints.impl.HintsLibraryImpl.SIGNATURE_LENGTH;
import static com.hedera.node.app.hints.impl.HintsLibraryImpl.VK_LENGTH;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.history.HintsKeyProvenance;
import com.hedera.hapi.node.state.history.HintsKeyWitness;
import com.hedera.node.app.hints.HintsLibrary;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.hiero.base.crypto.DigestType;

/**
 * Validates the fixed, depth-one timestamp proof carried by a hinTS key witness. The proof authenticates use of a
 * particular key, not construction identifiers or the key's current status. Adoption and timestamp eligibility must
 * additionally be checked against consensus state when admitting a witness to the native ledger.
 */
@Singleton
public final class HintsKeyWitnessVerifier {
    private static final long MAX_TIMESTAMP_SECONDS = 253402300799L;

    private final HintsLibrary library;

    @Inject
    public HintsKeyWitnessVerifier(@NonNull final HintsLibrary library) {
        this.library = requireNonNull(library);
    }

    /**
     * Verifies the witness under the supplied, independently authorized verification key at the fixed aBFT threshold.
     * The threshold is strictly greater than one third, independent of {@code tss.signingThresholdDivisor}. Producers
     * must collect that much signing weight even if their configured block-signing threshold is lower.
     * This check deliberately has no wall-clock freshness or construction-ID policy: historical witnesses must remain
     * usable for catch-up, and construction IDs are not authenticated by the signature.
     */
    public boolean verify(@Nullable final HintsKeyWitness witness, @Nullable final Bytes expectedVerificationKey) {
        if (witness == null
                || !hasValidStructure(witness)
                || expectedVerificationKey == null
                || expectedVerificationKey.length() != VK_LENGTH) {
            return false;
        }
        return verifyAggregate(witness.signature(), rootHash(witness), expectedVerificationKey);
    }

    /**
     * Verifies a predecessor certificate while following actual keys from an independently trusted key. Construction
     * identifiers are intentionally not used for authorization: they are not part of the signed message.
     */
    public boolean verifyCertificate(
            @Nullable final HintsKeyProvenance certificate, @Nullable final Bytes expectedSourceVerificationKey) {
        if (certificate == null
                || expectedSourceVerificationKey == null
                || expectedSourceVerificationKey.length() != VK_LENGTH
                || !expectedSourceVerificationKey.equals(certificate.sourceVerificationKey())
                || certificate.targetVerificationKey().length() != VK_LENGTH
                || certificate.signature().length() != SIGNATURE_LENGTH) {
            return false;
        }
        return verifyAggregate(
                certificate.signature(), certificate.targetVerificationKey(), expectedSourceVerificationKey);
    }

    /**
     * Whether the witness has the bounded cryptographic shape accepted before native verification. The unsigned
     * construction ID is ignored; native admission must separately validate it against an adopted state record.
     */
    public static boolean hasValidStructure(@Nullable final HintsKeyWitness witness) {
        return witness != null
                && validRootInputs(witness)
                && witness.signature().length() == SIGNATURE_LENGTH;
    }

    /** Whether the timestamp is a nonzero consensus timestamp in the protobuf Timestamp range. */
    public static boolean validTimestamp(@Nullable final Timestamp timestamp) {
        return timestamp != null
                && timestamp.seconds() > 0
                && timestamp.seconds() <= MAX_TIMESTAMP_SECONDS
                && timestamp.nanos() >= 0
                && timestamp.nanos() < 1_000_000_000;
    }

    /**
     * Reconstructs the directly signed block root. The leaf is always canonical Timestamp protobuf bytes on the left;
     * the caller cannot substitute a leaf hash, an alternate position, or another Merkle path. An empty signature is
     * allowed here so producers can compute a root before signing.
     *
     * @throws IllegalArgumentException if the timestamp, digest, or sibling length is invalid
     */
    public static @NonNull Bytes rootHash(@NonNull final HintsKeyWitness witness) {
        requireNonNull(witness);
        if (!validRootInputs(witness)) {
            throw new IllegalArgumentException("Invalid hinTS key witness root inputs");
        }
        final var digest = requireNonNull(digestType(witness)).buildDigest();
        final var timestampLeaf = hashLeaf(digest, Timestamp.PROTOBUF.toBytes(witness.timestampOrThrow()));
        return hashInternalNode(digest, timestampLeaf, witness.siblingHash());
    }

    private static boolean validRootInputs(@NonNull final HintsKeyWitness witness) {
        final var digest = digestType(witness);
        return validTimestamp(witness.timestamp())
                && digest != null
                && witness.siblingHash().length() == digest.digestLength();
    }

    private static @Nullable DigestType digestType(@NonNull final HintsKeyWitness witness) {
        if (witness.hashAlgorithm() == null) {
            return null;
        }
        return switch (witness.hashAlgorithm()) {
            case SHA2_256 -> DigestType.SHA_256;
            case SHA2_384 -> DigestType.SHA_384;
            default -> null;
        };
    }

    private boolean verifyAggregate(
            @NonNull final Bytes signature, @NonNull final Bytes message, @NonNull final Bytes verificationKey) {
        try {
            return library.verifyAggregate(signature, message, verificationKey, 1, 3);
        } catch (RuntimeException e) {
            // Length-valid but malformed group encodings must fail closed at the untrusted publication boundary.
            return false;
        }
    }
}
