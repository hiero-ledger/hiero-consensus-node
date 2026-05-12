// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.tss;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.blocks.BlockHashSigner;
import com.hedera.node.app.hints.impl.BlockHashSigning;
import com.hedera.node.app.hints.impl.RsaContext;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.ConcurrentMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A {@link BlockHashSigner} that delegates succinct signature requests and directly orchestrates RSA
 * list-of-partial-signature requests.
 */
public class DualBlockHashSigner implements BlockHashSigner {
    private static final Logger log = LogManager.getLogger(DualBlockHashSigner.class);

    private final RsaContext rsaContext;
    private final ConcurrentMap<Bytes, BlockHashSigning> rsaSignings;
    private final TssSubmissions submissions;
    private final BlockHashSigner succinctSignatureDelegate;

    public DualBlockHashSigner(
            @NonNull final RsaContext rsaContext,
            @NonNull final ConcurrentMap<Bytes, BlockHashSigning> rsaSignings,
            @NonNull final TssSubmissions submissions,
            @NonNull final BlockHashSigner succinctSignatureDelegate) {
        this.rsaContext = requireNonNull(rsaContext);
        this.rsaSignings = requireNonNull(rsaSignings);
        this.submissions = requireNonNull(submissions);
        this.succinctSignatureDelegate = requireNonNull(succinctSignatureDelegate);
    }

    @Override
    public boolean isReady() {
        return succinctSignatureDelegate.isReady() && rsaContext.isReady();
    }

    @Override
    public Attempt sign(@NonNull final Bytes blockHash, @NonNull final Request request) {
        requireNonNull(blockHash);
        requireNonNull(request);
        return switch (request) {
            case SUCCINCT_SIGNATURE -> succinctSignatureDelegate.sign(blockHash, request);
            case LIST_OF_PARTIAL_SIGNATURES -> {
                if (!rsaContext.isReady()) {
                    throw new IllegalStateException("RSA signing context not ready to sign block hash " + blockHash);
                }
                final var signing = rsaSignings.computeIfAbsent(
                        blockHash, b -> rsaContext.newSigning(b, () -> rsaSignings.remove(blockHash)));
                if (!(signing instanceof RsaContext.Signing rsaSigning)) {
                    throw new IllegalStateException("RSA signing required for block hash " + blockHash);
                }
                final var submissionFuture = submissions.submitRsaSignature(blockHash);
                submissionFuture.exceptionally(t -> {
                    log.warn("Failed to submit RSA signature for block hash {}", blockHash, t);
                    return null;
                });
                yield new Attempt(null, null, rsaSigning.future(), submissionFuture);
            }
        };
    }
}
