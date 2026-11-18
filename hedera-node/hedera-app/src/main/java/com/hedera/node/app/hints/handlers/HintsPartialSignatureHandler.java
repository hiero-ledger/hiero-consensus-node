// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints.handlers;

import static java.util.Objects.requireNonNull;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.hedera.hapi.services.auxiliary.hints.HintsPartialSignatureTransactionBody;
import com.hedera.node.app.hints.impl.BlockHashSigning;
import com.hedera.node.app.hints.impl.HintsContext;
import com.hedera.node.app.hints.impl.HintsModule;
import com.hedera.node.app.hints.impl.RsaContext;
import com.hedera.node.app.hints.impl.SigningAttemptId;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.app.spi.workflows.TransactionHandler;
import com.hedera.node.config.data.TssConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Singleton
public class HintsPartialSignatureHandler implements TransactionHandler {
    private static final Logger log = LogManager.getLogger(HintsPartialSignatureHandler.class);

    @NonNull
    private final ConcurrentMap<SigningAttemptId, HintsContext.Signing> signings;

    @NonNull
    private final ConcurrentMap<Bytes, BlockHashSigning> rsaSignings;

    private final HintsContext hintsContext;

    private final RsaContext rsaContext;

    private final LoadingCache<PartialSignature, Boolean> cache;

    /**
     * A node's partial signature verified relative to a particular hinTS construction id and CRS.
     *
     * @param constructionId the construction id
     * @param crs            the CRS
     * @param nodeId         the node id
     * @param body           the partial signature
     */
    private record PartialSignature(
            long constructionId,
            @NonNull Bytes crs,
            long nodeId,
            @NonNull HintsPartialSignatureTransactionBody body) {
        private PartialSignature {
            requireNonNull(crs);
            requireNonNull(body);
        }
    }

    @Inject
    public HintsPartialSignatureHandler(
            @NonNull final Duration blockPeriod,
            @Named(HintsModule.HINTS_SIGNINGS) @NonNull
                    final ConcurrentMap<SigningAttemptId, HintsContext.Signing> signings,
            @Named(HintsModule.RSA_SIGNINGS) @NonNull final ConcurrentMap<Bytes, BlockHashSigning> rsaSignings,
            @NonNull final HintsContext context,
            @NonNull final RsaContext rsaContext) {
        this.signings = requireNonNull(signings);
        this.rsaSignings = requireNonNull(rsaSignings);
        this.hintsContext = requireNonNull(context);
        this.rsaContext = requireNonNull(rsaContext);
        // Validation results from pre-handle, reused at handle so no partial signature is verified twice
        cache = Caffeine.newBuilder()
                .expireAfterAccess(Math.max(1, 2 * blockPeriod.getSeconds()), TimeUnit.SECONDS)
                .softValues()
                .build(this::validate);
    }

    @Override
    public void pureChecks(@NonNull final PureChecksContext context) throws PreCheckException {
        requireNonNull(context);
    }

    @Override
    public void preHandle(@NonNull final PreHandleContext context) throws PreCheckException {
        requireNonNull(context);
        final var creatorId = context.creatorInfo().nodeId();
        final HintsPartialSignatureTransactionBody op;
        final TssConfig tssConfig;
        try {
            op = context.body().hintsPartialSignatureOrThrow();
            tssConfig = context.configuration().getConfigData(TssConfig.class);
            if (op.constructionId() == RsaContext.CONSTRUCTION_ID) {
                if (!tssConfig.useDeterministicHintsSignatures()) {
                    incorporateRsaIfValid(op, creatorId);
                }
                return;
            }
        } catch (Exception e) {
            log.debug("Ignoring partial signature in pre-handle for node {}", creatorId, e);
            return;
        }
        try {
            final var crs = hintsContext.crsForConstruction(op.constructionId());
            if (crs == null) {
                return;
            }
            final var partialSignature = new PartialSignature(op.constructionId(), crs, creatorId, op);
            final boolean isValid = Boolean.TRUE.equals(cache.get(partialSignature));
            if (isValid && !tssConfig.useDeterministicHintsSignatures()) {
                incorporateIfConstructionMatches(op, crs, creatorId);
            }
        } catch (Exception e) {
            log.debug("Ignoring partial signature in pre-handle for node {}", creatorId, e);
        }
    }

    @Override
    public void handle(@NonNull final HandleContext context) throws HandleException {
        requireNonNull(context);
        final var op = context.body().hintsPartialSignatureOrThrow();
        final var creatorId = context.creatorInfo().nodeId();
        final var tssConfig = context.configuration().getConfigData(TssConfig.class);
        if (op.constructionId() == RsaContext.CONSTRUCTION_ID) {
            if (tssConfig.useDeterministicHintsSignatures()) {
                incorporateRsaIfValid(op, creatorId);
            }
            return;
        }
        final var crs = hintsContext.crsForConstruction(op.constructionId());
        if (crs == null) {
            return;
        }
        // With deterministic hinTS signatures, partial signatures are only incorporated at handle; otherwise they
        // were normally already incorporated at pre-handle, but that may have been impossible for a node behind its
        // peers (e.g., one that had not yet handled the construction handoff), so retry any that are still needed
        if (tssConfig.useDeterministicHintsSignatures() || isStillNeeded(op, creatorId)) {
            final boolean isValid =
                    Boolean.TRUE.equals(cache.get(new PartialSignature(op.constructionId(), crs, creatorId, op)));
            if (isValid) {
                incorporateIfConstructionMatches(op, crs, creatorId);
            }
        }
    }

    /**
     * Whether a partial signature that reached consensus might not have been incorporated at pre-handle, but would be
     * if incorporated now. This is the case when there is either no signing attempt for its message and
     * construction, or one that still lacks a signature from its creator.
     * <p>
     * Pre-handle only incorporates partial signatures for constructions this node already knows; so when this node
     * is behind its peers, it can pre-handle their partial signatures for a block under a new construction before it
     * has handled the handoff to that construction. Without retrying at handle, those signatures would be lost and
     * the block could only be proven indirectly.
     *
     * @param op the partial signature
     * @param creatorId the node that created the partial signature
     * @return true if the partial signature should be validated and incorporated at handle
     */
    private boolean isStillNeeded(@NonNull final HintsPartialSignatureTransactionBody op, final long creatorId) {
        final var existing = signings.get(new SigningAttemptId(op.message(), op.constructionId()));
        return existing == null || existing.needsSignatureFrom(creatorId);
    }

    /**
     * Incorporates only into a signing attempt created for the construction id carried by the transaction body.
     * This guards the handoff window where a partial signature for the previous construction can reach consensus
     * after the active construction has already advanced.
     */
    private void incorporateIfConstructionMatches(
            @NonNull final HintsPartialSignatureTransactionBody op, @NonNull final Bytes crs, final long creatorId) {
        if (!hintsContext.acceptsConstruction(op.constructionId())) {
            return;
        }
        final var signing = getOrCreateSigningFor(op);
        if (signing != null) {
            signing.incorporateValid(crs, creatorId, op.partialSignature());
        }
    }

    /**
     * Finds or creates a signing attempt for the transaction body's construction id, never merely for the
     * currently-active construction. The signing map is keyed by block hash and construction id, so a handoff
     * cannot mix partial signatures between adjacent constructions, and an attempt under one construction cannot
     * hide partial signatures for the other.
     */
    private @Nullable HintsContext.Signing getOrCreateSigningFor(
            @NonNull final HintsPartialSignatureTransactionBody op) {
        final var attemptId = new SigningAttemptId(op.message(), op.constructionId());
        final var existing = signings.get(attemptId);
        if (existing != null) {
            return existing;
        }
        final var created = hintsContext.newSigningForConstruction(
                op.message(), op.constructionId(), () -> signings.remove(attemptId));
        if (created == null) {
            return null;
        }
        final var raced = signings.putIfAbsent(attemptId, created);
        if (raced == null) {
            return created;
        }
        created.cancel();
        return raced;
    }

    private void incorporateRsaIfValid(@NonNull final HintsPartialSignatureTransactionBody op, final long creatorId) {
        final boolean isValid = rsaContext.validate(creatorId, op);
        if (isValid) {
            rsaSignings
                    .computeIfAbsent(
                            op.message(), b -> rsaContext.newSigning(b, () -> rsaSignings.remove(op.message())))
                    .incorporateValid(Bytes.EMPTY, creatorId, op.partialSignature());
        }
    }

    /**
     * Validates the given partial signature against its own construction id. If there is already a matching signing
     * attempt for the block hash, use that signing's captured scheme; otherwise the context can still validate against
     * its active or immediately previous construction snapshot.
     * <p>
     * @param partialSignature the partial signature to validate
     * @return whether the partial signature is valid
     */
    private @Nullable Boolean validate(@NonNull final PartialSignature partialSignature) {
        try {
            // Should never throw, but if it does, we don't want to cache the result, so we catch and return null
            final var signing = signings.get(
                    new SigningAttemptId(partialSignature.body().message(), partialSignature.constructionId()));
            return signing == null
                    ? hintsContext.validate(partialSignature.nodeId(), partialSignature.crs(), partialSignature.body())
                    : signing.validatePartial(
                            partialSignature.nodeId(), partialSignature.crs(), partialSignature.body());
        } catch (Exception e) {
            return null;
        }
    }
}
