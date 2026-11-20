// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.clpr;

import static com.hedera.hapi.node.base.BlockHashAlgorithm.SHA2_256;
import static com.hedera.node.app.hapi.utils.MiscCryptoUtils.keccak256DigestOf;
import static com.hedera.node.app.hints.impl.HintsLibraryImpl.SIGNATURE_LENGTH;
import static com.hedera.node.app.hints.impl.HintsLibraryImpl.VK_LENGTH;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.HintsKeyProvenanceChain;
import com.hedera.hapi.block.stream.StateProof;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.history.HintsKeyProvenance;
import com.hedera.hapi.node.state.history.HintsKeyWitness;
import com.hedera.node.app.hapi.utils.blocks.StateProofVerifier;
import com.hedera.node.app.hints.HintsLibrary;
import com.hedera.node.app.history.impl.HintsKeyProvenanceVerifier;
import com.hedera.node.app.history.impl.HintsKeyWitnessVerifier;
import com.hedera.node.app.service.clpr.impl.ClprHieroProofSupport;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.base.time.Time;
import com.swirlds.metrics.api.Counter;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.MarkerManager;
import org.hiero.base.concurrent.throttle.RateLimitedLogger;
import org.hiero.base.crypto.DigestType;

/** Checks the outgoing V1 envelope at the same hinTS threshold as the EVM verifier. */
final class ClprHieroProofSelfCheck implements ClprHieroProofSupport {
    private static final int MAX_SKIP_COUNTERS = 4096;

    private record SkipKey(Bytes channelId, SkipReason reason) {}

    private final HintsLibrary library;
    private final HintsKeyWitnessVerifier witnessVerifier;
    private final HintsKeyProvenanceVerifier provenanceVerifier;
    private final Metrics metrics;
    private final Map<SkipReason, RateLimitedLogger> skipLogs = new EnumMap<>(SkipReason.class);
    private final LinkedHashMap<SkipKey, Counter> skipCounters = new LinkedHashMap<>(16, 0.75f, true);

    ClprHieroProofSelfCheck(
            @NonNull final HintsLibrary library,
            @NonNull final HintsKeyWitnessVerifier witnessVerifier,
            @NonNull final Metrics metrics) {
        this.library = requireNonNull(library);
        this.witnessVerifier = requireNonNull(witnessVerifier);
        this.provenanceVerifier = new HintsKeyProvenanceVerifier(witnessVerifier);
        this.metrics = requireNonNull(metrics);
        for (final var reason : SkipReason.values()) {
            skipLogs.put(
                    reason,
                    new RateLimitedLogger(
                            LogManager.getLogger(ClprHieroProofSelfCheck.class),
                            Time.getCurrent(),
                            Duration.ofMinutes(1)));
        }
    }

    @Override
    public boolean verify(@NonNull final Bytes envelope, @NonNull final Bytes anchorId) {
        try {
            final var proof = ClprHieroProofCodec.decodeEnvelope(envelope);
            final var chain = proof.keyProvenance();
            if (chain != null && !anchorId.equals(keccak256DigestOf(chain.initialVerificationKey()))) {
                return false;
            }
            final var certificates = chain == null ? List.<HintsKeyProvenance>of() : certificates(chain);
            final var blockWitness = proof.blockWitness();
            if (blockWitness != null) {
                final var timestamp =
                        Timestamp.PROTOBUF.parseStrict(blockWitness.timestamp().toReadableSequentialData());
                if (!Timestamp.PROTOBUF.toBytes(timestamp).equals(blockWitness.timestamp())) {
                    return false;
                }
                final var witness = HintsKeyWitness.newBuilder()
                        .timestamp(timestamp)
                        .siblingHash(blockWitness.siblingHash())
                        .signature(blockWitness.signature())
                        .hashAlgorithm(SHA2_256)
                        .build();
                return provenanceVerifier
                        .verifyBatch(
                                HintsKeyProvenanceVerifier.Checkpoint.fromTrustedKey(
                                        proof.keyProvenanceOrThrow().initialVerificationKey()),
                                certificates,
                                witness,
                                ClprHieroProofCodec.MAX_LINKS)
                        .isPresent();
            }

            final var stateProof = proof.stateProofOrThrow();
            final var signer =
                    stateProof.signedBlockProofOrThrow().blockSignature().slice(0, VK_LENGTH);
            if (chain == null) {
                return anchorId.equals(keccak256DigestOf(signer)) && verifyStateProof(stateProof, signer);
            }
            var expectedKey = chain.initialVerificationKey();
            for (final var certificate : certificates) {
                if (!witnessVerifier.verifyCertificate(certificate, expectedKey)) {
                    return false;
                }
                expectedKey = certificate.targetVerificationKey();
            }
            return expectedKey.equals(signer) && verifyStateProof(stateProof, signer);
        } catch (final ParseException | RuntimeException e) {
            // A parse error or an invalid group encoding must never reach the destination.
            return false;
        }
    }

    private static List<HintsKeyProvenance> certificates(final HintsKeyProvenanceChain chain) {
        final var certificates = new ArrayList<HintsKeyProvenance>(chain.links().size());
        var source = chain.initialVerificationKey();
        for (final var link : chain.links()) {
            certificates.add(HintsKeyProvenance.newBuilder()
                    .sourceVerificationKey(source)
                    .targetVerificationKey(link.nextVerificationKey())
                    .signature(link.signature())
                    .build());
            source = link.nextVerificationKey();
        }
        return certificates;
    }

    private boolean verifyStateProof(final StateProof stateProof, final Bytes signer) {
        final var paths = stateProof.paths();
        if (paths.isEmpty()
                || paths.stream().anyMatch(path -> !path.hasStateItemLeaf() || path.nextPathIndex() != -1)) {
            return false;
        }
        final var digest = DigestType.SHA_256.buildDigest();
        final var root = StateProofVerifier.computeBlockRootHashFromPath(paths.getFirst(), digest);
        for (int i = 1; i < paths.size(); i++) {
            if (!StateProofVerifier.verifyPath(paths.get(i), root, digest)) {
                return false;
            }
        }
        final var signature =
                stateProof.signedBlockProofOrThrow().blockSignature().slice(VK_LENGTH, SIGNATURE_LENGTH);
        return library.verifyAggregate(signature, Bytes.wrap(root), signer, 1, 3);
    }

    @Override
    public void reportSkip(@NonNull final Bytes channelId, @NonNull final SkipReason reason) {
        final var key = new SkipKey(channelId, reason);
        synchronized (skipCounters) {
            if (!skipCounters.containsKey(key) && skipCounters.size() >= MAX_SKIP_COUNTERS) {
                // Channel churn must not grow the node's metrics registry indefinitely.
                metrics.remove(skipCounters.pollFirstEntry().getValue());
            }
            skipCounters
                    .computeIfAbsent(
                            key,
                            ignored -> metrics.getOrCreate(new Counter.Config(
                                            "clpr",
                                            "hieroProofSkipped_" + reason.name().toLowerCase(Locale.ROOT) + "_"
                                                    + channelId.toHex())
                                    .withDescription("HieroProof V1 bundles skipped for this channel and reason")))
                    .increment();
        }
        skipLogs.get(reason)
                .warn(
                        MarkerManager.getMarker("CLPR"),
                        "Skipped HieroProof V1 bundle for channel {}: {}",
                        channelId,
                        reason);
    }
}
