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
            final var partialSignature = new PartialSignature(hintsContext.constructionIdOrThrow(), crs, creatorId, op);
            if (tssConfig.useDeterministicHintsSignatures()) {
                //noinspection ResultOfMethodCallIgnored
                cache.get(partialSignature);
            } else {
                final boolean isValid = hintsContext.validate(
                        partialSignature.nodeId(), partialSignature.crs(), partialSignature.body());
                if (isValid) {
                    signings.computeIfAbsent(
                                    op.message(), b -> hintsContext.newSigning(b, () -> signings.remove(op.message())))
                            .incorporateValid(crs, creatorId, op.partialSignature());
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
                    hintsContext.constructionIdOrThrow(),
                    crs,
                    context.creatorInfo().nodeId(),
                    op)));
            if (isValid) {
                signings.computeIfAbsent(
                                op.message(), b -> hintsContext.newSigning(b, () -> signings.remove(op.message())))
                        .incorporateValid(crs, creatorId, op.partialSignature());
            }
        }
    }

    private void incorporateRsaIfValid(@NonNull final HintsPartialSignatureTransactionBody op, final long creatorId) {
        if (rsaContext.validate(creatorId, op)) {
            rsaSignings
                    .computeIfAbsent(
                            op.message(), b -> rsaContext.newSigning(b, () -> rsaSignings.remove(op.message())))
                    .incorporateValid(Bytes.EMPTY, creatorId, op.partialSignature());
        }
    }

    /**
     * Validates the given partial signature.
     * <p>
     * @param partialSignature the partial signature to validate
     * @return whether the partial signature is valid
     */
    private @Nullable Boolean validate(@NonNull final PartialSignature partialSignature) {
        try {
            // Should never throw, but if it does, we don't want to cache the result, so we catch and return null
            return hintsContext.validate(partialSignature.nodeId(), partialSignature.crs(), partialSignature.body());
        } catch (Exception e) {
            return null;
        }
    }
}
