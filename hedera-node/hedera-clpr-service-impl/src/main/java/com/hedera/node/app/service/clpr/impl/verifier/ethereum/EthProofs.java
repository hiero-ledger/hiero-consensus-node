// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.ethereum;

import com.hedera.node.app.service.clpr.impl.verifier.ProofException;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Utility class for Ethereum proof-related operations, specifically for handling exceptions
 * tagged with a consistent verifier name.
 *
 */
final class EthProofs {

    static final String VERIFIER_NAME = "EthereumSyncCommitteeProofVerifier";

    private EthProofs() {}

    /**
     * Builds {@link ProofException}s tagged with the Ethereum sync-committee verifier's name. Keeps
     * that name in one place for every type in this package without coupling the generic
     * {@link ProofException} to this verifier.
     */
    @NonNull
    static ProofException fail(@NonNull final String message) {
        return new ProofException(VERIFIER_NAME, message);
    }

    /**
     * Builds {@link ProofException}s tagged with the Ethereum sync-committee verifier's name. Keeps
     * that name in one place for every type in this package without coupling the generic
     * {@link ProofException} to this verifier.
     */
    @NonNull
    static ProofException fail(@NonNull final String message, @NonNull final Throwable cause) {
        return new ProofException(VERIFIER_NAME, message, cause);
    }

    /**
     * Rebuilds {@code cause} with {@code context} in front of its reason, so whoever logs the rejection also sees what
     * was being verified. The verifier-name prefix of a {@code cause} raised in this package is dropped from the
     * reason, so the name appears once.
     */
    @NonNull
    static ProofException withContext(@NonNull final String context, @NonNull final ProofException cause) {
        final String ownPrefix = VERIFIER_NAME + ": ";
        final String message = cause.getMessage();
        final String reason = message.startsWith(ownPrefix) ? message.substring(ownPrefix.length()) : message;
        return new ProofException(VERIFIER_NAME, context + ": " + reason, cause);
    }
}
