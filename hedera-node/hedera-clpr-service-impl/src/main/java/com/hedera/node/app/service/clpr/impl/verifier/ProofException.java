// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;

/**
 * Verification failure raised for malformed or unauthenticated proof data.
 *
 * <p>Native verifier implementations throw this internally. CLPR handlers and system-contract
 * wrappers must translate it to their public failure surface ({@code HandleException} or an EVM
 * revert), so malformed external proofs are rejected rather than escaping as generic runtime
 * failures.
 */
public final class ProofException extends RuntimeException {
    private static final String BESU_QBFT_VERIFIER = "BesuQbftVerifier";
    private static final String SEI_VERIFIER = "SeiCometBftProofVerifier";

    public ProofException(@NonNull final String verifierName, @NonNull final String message) {
        super(format(verifierName, message));
    }

    public ProofException(
            @NonNull final String verifierName, @NonNull final String message, @NonNull final Throwable cause) {
        super(format(verifierName, message), Objects.requireNonNull(cause, "cause"));
    }

    @NonNull
    public static ProofException besuQbft(@NonNull final String message) {
        return new ProofException(BESU_QBFT_VERIFIER, message);
    }

    @NonNull
    public static ProofException besuQbft(@NonNull final String message, @NonNull final Throwable cause) {
        return new ProofException(BESU_QBFT_VERIFIER, message, cause);
    }

    @NonNull
    public static ProofException sei(@NonNull final String message) {
        return new ProofException(SEI_VERIFIER, message);
    }

    @NonNull
    public static ProofException sei(@NonNull final String message, @NonNull final Throwable cause) {
        return new ProofException(SEI_VERIFIER, message, cause);
    }

    @NonNull
    private static String format(@NonNull final String verifierName, @NonNull final String message) {
        return Objects.requireNonNull(verifierName, "verifierName") + ": " + Objects.requireNonNull(message, "message");
    }
}
