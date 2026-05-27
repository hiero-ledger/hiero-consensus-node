// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints.impl;

import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toMap;

import com.hedera.hapi.node.state.roster.NodeSignature;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterSignatures;
import com.hedera.hapi.services.auxiliary.hints.HintsPartialSignatureTransactionBody;
import com.hedera.node.config.data.TssConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.security.PublicKey;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongUnaryOperator;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.BytesSignatureVerifier;
import org.hiero.base.crypto.CryptographyException;
import org.hiero.base.crypto.SigningFactory;
import org.hiero.consensus.roster.RosterUtils;

/**
 * The RSA context that can be used to request signatures using the roster's gossip signing keys.
 */
@Singleton
public class RsaContext {
    private static final Logger log = LogManager.getLogger(RsaContext.class);

    public static final long CONSTRUCTION_ID = 0L;

    // For a quiesced network, a signature list could in principle take an entire day to assemble
    private static final Duration SIGNING_ATTEMPT_TIMEOUT = Duration.ofDays(1);

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private final Supplier<Configuration> configProvider;

    private volatile Bytes rosterHash = Bytes.EMPTY;
    private volatile Map<Long, PublicKey> publicKeys = Map.of();
    private volatile Map<Long, Long> weights = Map.of();
    private final ThreadLocal<Map<Long, BytesSignatureVerifier>> verifiers = ThreadLocal.withInitial(HashMap::new);

    @Inject
    public RsaContext(@NonNull final Supplier<Configuration> configProvider) {
        this.configProvider = requireNonNull(configProvider);
    }

    /**
     * Initializes the RSA verification context from the given roster and node weight function.
     *
     * @param roster the roster whose gossip signing keys should be used
     * @param weightFn the function assigning signing weights by node id
     */
    public void initialize(@NonNull final Roster roster, @NonNull final LongUnaryOperator weightFn) {
        requireNonNull(roster);
        requireNonNull(weightFn);
        final Map<Long, PublicKey> keys = new HashMap<>();
        for (final var entry : roster.rosterEntries()) {
            final var certificate = RosterUtils.fetchGossipCaCertificate(entry);
            final var publicKey = certificate == null ? null : certificate.getPublicKey();
            if (publicKey != null && "RSA".equals(publicKey.getAlgorithm())) {
                keys.put(entry.nodeId(), publicKey);
            }
        }
        rosterHash = RosterUtils.hash(roster).getBytes();
        publicKeys = Map.copyOf(keys);
        weights = publicKeys.keySet().stream().collect(toMap(identity(), nodeId -> weightFor(weightFn, nodeId)));
        verifiers.remove();
    }

    /**
     * Whether this context has initialized RSA public keys.
     *
     * @return whether this context is ready
     */
    public boolean isReady() {
        return !publicKeys.isEmpty();
    }

    /**
     * Validates an RSA signature transaction body under the current roster.
     *
     * @param nodeId the signing node id
     * @param body the transaction body
     * @return whether the signature is valid
     */
    public boolean validate(final long nodeId, @NonNull final HintsPartialSignatureTransactionBody body) {
        requireNonNull(body);
        if (body.constructionId() != CONSTRUCTION_ID) {
            return false;
        }
        return validate(nodeId, body.message(), body.partialSignature());
    }

    /**
     * Validates an RSA signature under the current roster.
     *
     * @param nodeId the signing node id
     * @param message the signed message
     * @param signature the RSA signature
     * @return whether the signature is valid
     */
    public boolean validate(final long nodeId, @NonNull final Bytes message, @NonNull final Bytes signature) {
        requireNonNull(message);
        requireNonNull(signature);
        final var publicKey = publicKeys.get(nodeId);
        if (publicKey == null) {
            return false;
        }
        try {
            final var verifier =
                    verifiers.get().computeIfAbsent(nodeId, ignore -> SigningFactory.createVerifier(publicKey));
            return verifier.verify(message, signature);
        } catch (final CryptographyException e) {
            log.debug("Failed to validate RSA signature from node {}", nodeId, e);
            return false;
        }
    }

    /**
     * Creates a new asynchronous RSA signing process for the given block hash.
     *
     * @param blockHash the block hash
     * @param onCompletion a callback to run when the signing process completes
     * @return the signing process
     */
    public @NonNull BlockHashSigning newSigning(@NonNull final Bytes blockHash, @NonNull final Runnable onCompletion) {
        requireNonNull(blockHash);
        requireNonNull(onCompletion);
        if (!isReady()) {
            throw new IllegalStateException("RSA signing context not ready");
        }
        final var weightSnapshot = Map.copyOf(weights);
        final long totalWeight =
                weightSnapshot.values().stream().mapToLong(Long::longValue).sum();
        final var tssConfig = configProvider.get().getConfigData(TssConfig.class);
        final int divisor = Math.max(1, tssConfig.signingThresholdDivisor());
        final long threshold = totalWeight / divisor;
        return new Signing(blockHash, requireNonNull(rosterHash), threshold, weightSnapshot, onCompletion);
    }

    private static long weightFor(@NonNull final LongUnaryOperator weightFn, final long nodeId) {
        final long weight = weightFn.applyAsLong(nodeId);
        if (weight < 0) {
            throw new IllegalArgumentException("Node " + nodeId + " has negative RSA signing weight " + weight);
        }
        return weight;
    }

    /**
     * A signing process spawned from this context.
     */
    public non-sealed class Signing implements BlockHashSigning {
        private final Bytes blockHash;
        private final Bytes rosterHash;
        private final long thresholdWeight;
        private final Map<Long, Long> nodeWeights;
        private final CompletableFuture<Bytes> future = new CompletableFuture<>();
        private final ConcurrentMap<Long, Bytes> signatures = new ConcurrentHashMap<>();
        private final AtomicLong weightOfSignatures = new AtomicLong();
        private final AtomicBoolean completed = new AtomicBoolean();
        private final ScheduledFuture<?> timeoutFuture;

        public Signing(
                @NonNull final Bytes blockHash,
                @NonNull final Bytes rosterHash,
                final long thresholdWeight,
                @NonNull final Map<Long, Long> nodeWeights,
                @NonNull final Runnable onCompletion) {
            this.blockHash = requireNonNull(blockHash);
            this.rosterHash = requireNonNull(rosterHash);
            this.thresholdWeight = thresholdWeight;
            this.nodeWeights = requireNonNull(nodeWeights);
            requireNonNull(onCompletion);
            timeoutFuture = executor.schedule(
                    () -> {
                        if (!future.isDone()) {
                            log.warn(
                                    "Completing RSA signing attempt on '{}' without obtaining a signature list (had {} from nodes {} for total weight {}/{} required)",
                                    blockHash,
                                    signatures.size(),
                                    signatures.keySet(),
                                    weightOfSignatures.get(),
                                    thresholdWeight);
                        }
                        onCompletion.run();
                    },
                    SIGNING_ATTEMPT_TIMEOUT.getSeconds(),
                    SECONDS);
        }

        /**
         * The future that will complete when sufficient RSA signatures have been collected.
         *
         * @return the future
         */
        public CompletableFuture<Bytes> future() {
            return future;
        }

        /**
         * Cancels this RSA signing process.
         */
        @Override
        public void cancel() {
            timeoutFuture.cancel(false);
            if (completed.compareAndSet(false, true)) {
                future.cancel(false);
            }
        }

        /**
         * Incorporates a node's pre-validated RSA signature into the list. If including this node's weight passes the
         * required threshold, completes the future returned from {@link #future()} with the serialized
         * {@link RosterSignatures}.
         *
         * @param ignored ignored for RSA signing
         * @param nodeId the node ID
         * @param signature the pre-validated RSA signature
         */
        @Override
        public void incorporateValid(@NonNull final Bytes ignored, final long nodeId, @NonNull final Bytes signature) {
            requireNonNull(ignored);
            requireNonNull(signature);
            if (completed.get()) {
                return;
            }
            final var weight = nodeWeights.get(nodeId);
            if (weight == null || weight == 0) {
                return;
            }
            if (signatures.put(nodeId, signature) != null) {
                return;
            }
            final var totalWeight = weightOfSignatures.addAndGet(weight);
            if (totalWeight > thresholdWeight && completed.compareAndSet(false, true)) {
                future.complete(RosterSignatures.PROTOBUF.toBytes(rosterSignatures()));
            }
        }

        private RosterSignatures rosterSignatures() {
            final var nodeSignatures = signatures.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(entry -> new NodeSignature(entry.getKey(), entry.getValue()))
                    .toList();
            return new RosterSignatures(rosterHash, nodeSignatures);
        }
    }
}
