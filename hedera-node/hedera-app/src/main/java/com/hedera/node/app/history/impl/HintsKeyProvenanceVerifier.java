// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.impl;

import static com.hedera.hapi.util.HapiUtils.TIMESTAMP_COMPARATOR;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.history.HintsKeyProvenance;
import com.hedera.hapi.node.state.history.HintsKeyWitness;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import java.util.Optional;

/**
 * Reference acceptance rule for gas-bounded provenance catch-up. This class does not mutate an anchor; callers may
 * commit the returned checkpoint only after a whole batch succeeds. A consumer's CLPR message freshness policy is a
 * separate check and must not be applied to historical key witnesses.
 */
public final class HintsKeyProvenanceVerifier {
    /** An independently trusted key and the last authenticated timestamp committed for that key chain. */
    public record Checkpoint(
            @NonNull Bytes verificationKey, @NonNull Timestamp witnessedAt) {
        public Checkpoint {
            requireNonNull(verificationKey);
            requireNonNull(witnessedAt);
        }
    }

    private final HintsKeyWitnessVerifier witnessVerifier;

    public HintsKeyProvenanceVerifier(@NonNull final HintsKeyWitnessVerifier witnessVerifier) {
        this.witnessVerifier = requireNonNull(witnessVerifier);
    }

    /**
     * Verifies at most {@code maxLinks} adjacent certificates and a witness for the final key. Intermediate keys need
     * not have witnesses. Every source key must match the preceding actual key; unsigned construction identifiers are
     * never used to establish order or trust. Historical witnesses are allowed, but the authenticated timestamp must
     * advance beyond the committed checkpoint to prevent replay of a previously accepted batch.
     */
    public @NonNull Optional<Checkpoint> verifyBatch(
            @NonNull final Checkpoint trusted,
            @NonNull final List<HintsKeyProvenance> chain,
            @Nullable final HintsKeyWitness endpointWitness,
            final int maxLinks) {
        requireNonNull(trusted);
        requireNonNull(chain);
        if (maxLinks <= 0
                || chain.isEmpty()
                || chain.size() > maxLinks
                || endpointWitness == null
                || !HintsKeyWitnessVerifier.hasValidStructure(endpointWitness)
                || TIMESTAMP_COMPARATOR.compare(endpointWitness.timestampOrThrow(), trusted.witnessedAt()) <= 0) {
            return Optional.empty();
        }
        var expectedSource = trusted.verificationKey();
        for (final var certificate : chain) {
            if (!witnessVerifier.verifyCertificate(certificate, expectedSource)) {
                return Optional.empty();
            }
            expectedSource = certificate.targetVerificationKey();
        }
        if (!witnessVerifier.verify(endpointWitness, expectedSource)) {
            return Optional.empty();
        }
        return Optional.of(new Checkpoint(expectedSource, endpointWitness.timestampOrThrow()));
    }
}
