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
}
