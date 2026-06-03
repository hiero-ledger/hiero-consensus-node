// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints.handlers;

import static java.util.Objects.requireNonNull;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.hedera.hapi.services.auxiliary.hints.HintsPartialSignatureTransactionBody;
import com.hedera.node.app.hints.ReadableHintsStore;
import com.hedera.node.app.hints.impl.BlockHashSigning;
import com.hedera.node.app.hints.impl.HintsContext;
import com.hedera.node.app.hints.impl.HintsModule;
import com.hedera.node.app.hints.impl.RsaContext;
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
    private final ConcurrentMap<Bytes, BlockHashSigning> signings;

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
            @Named(HintsModule.HINTS_SIGNINGS) @NonNull final ConcurrentMap<Bytes, BlockHashSigning> signings,
            @Named(HintsModule.RSA_SIGNINGS) @NonNull final ConcurrentMap<Bytes, BlockHashSigning> rsaSignings,
            @NonNull final HintsContext context,
            @NonNull final RsaContext rsaContext) {
        this.signings = requireNonNull(signings);
        this.rsaSignings = requireNonNull(rsaSignings);
        this.hintsContext = requireNonNull(context);
        this.rsaContext = requireNonNull(rsaContext);
        // Only used when waiting for consensus to construct deterministic signatures
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
        final var hintsStore = context.createStore(ReadableHintsStore.class);
        final var crs = requireNonNull(hintsStore.crsIfKnown());
        try {
            final var partialSignature = new PartialSignature(op.constructionId(), crs, creatorId, op);
            if (tssConfig.useDeterministicHintsSignatures()) {
                //noinspection ResultOfMethodCallIgnored
                cache.get(partialSignature);
            } else {
                final boolean isValid = Boolean.TRUE.equals(validate(partialSignature));
                if (isValid) {
                    incorporateIfConstructionMatches(op, crs, creatorId);
                }
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
        final var hintsStore = context.storeFactory().readableStore(ReadableHintsStore.class);
        final var crs = requireNonNull(hintsStore.crsIfKnown());
        // Only something to do at handle if using deterministic hinTS signatures
        if (tssConfig.useDeterministicHintsSignatures()) {
            final boolean isValid = Boolean.TRUE.equals(cache.get(new PartialSignature(
                    op.constructionId(), crs, context.creatorInfo().nodeId(), op)));
            if (isValid) {
                incorporateIfConstructionMatches(op, crs, creatorId);
            }
        }
    }

    /**
     * Incorporates only into a signing attempt created for the construction id carried by the transaction body.
     * This guards the handoff window where a partial signature for the previous construction can reach consensus
     * after the active construction has already advanced.
     */
    private void incorporateIfConstructionMatches(
            @NonNull final HintsPartialSignatureTransactionBody op, @NonNull final Bytes crs, final long creatorId) {
        final var signing = getOrCreateSigningFor(op);
        if (signing != null) {
            signing.incorporateValid(crs, creatorId, op.partialSignature());
        }
    }

    /**
     * Finds or creates a signing attempt for the transaction body's construction id, never merely for the
     * currently-active construction. The signing map is keyed by block hash, so the explicit construction check is
     * what prevents a handoff from mixing partial signatures between adjacent constructions.
     */
    private @Nullable HintsContext.Signing getOrCreateSigningFor(
            @NonNull final HintsPartialSignatureTransactionBody op) {
        final var existing = signings.get(op.message());
        if (existing != null) {
            return asMatchingSigning(existing, op.constructionId());
        }
        final var created = hintsContext.newSigningForConstruction(
                op.message(), op.constructionId(), () -> signings.remove(op.message()));
        if (created == null) {
            return null;
        }
        final var raced = signings.putIfAbsent(op.message(), created);
        if (raced == null) {
            return created;
        }
        created.cancel();
        return asMatchingSigning(raced, op.constructionId());
    }

    /**
     * Returns the signing only if it was opened under the requested construction id.
     */
    private @Nullable HintsContext.Signing asMatchingSigning(
            @Nullable final BlockHashSigning signing, final long constructionId) {
        return signing instanceof HintsContext.Signing hintsSigning && hintsSigning.constructionId() == constructionId
                ? hintsSigning
                : null;
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
            final var signing = asMatchingSigning(
                    signings.get(partialSignature.body().message()), partialSignature.constructionId());
            return signing == null
                    ? hintsContext.validate(partialSignature.nodeId(), partialSignature.crs(), partialSignature.body())
                    : signing.validatePartial(
                            partialSignature.nodeId(), partialSignature.crs(), partialSignature.body());
        } catch (Exception e) {
            return null;
        }
    }
}
