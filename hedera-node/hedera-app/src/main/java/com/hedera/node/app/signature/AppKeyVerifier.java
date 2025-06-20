// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.signature;

import com.hedera.node.app.spi.key.KeyVerifier;
import com.hedera.node.app.spi.signatures.SignatureVerification;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Helper class that contains all functionality for verifying signatures during handle.
 */
public interface AppKeyVerifier extends KeyVerifier {

    /**
     * Look for a {@link SignatureVerification} that applies to the given hollow account.
     * @param evmAlias The evm alias to lookup verification for.
     * @return The {@link SignatureVerification} for the given hollow account.
     */
    @NonNull
    SignatureVerification verificationFor(@NonNull Bytes evmAlias);

    /**
     * Gets the number of signatures verified for this transaction.
     *
     * @return the number of signatures verified for this transaction. Non-negative.
     */
    int numSignaturesVerified();
}
