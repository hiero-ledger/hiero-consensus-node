// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.ethereum.verify;

import com.hedera.node.app.service.clpr.impl.verifier.BlsSignatureVerifier;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;

/**
 * {@link BlsSignatureVerifier} used when no real BLS12-381 backend is available. It rejects every
 * aggregate signature, so Ethereum sync-committee proofs can never be accepted without a genuine
 * pairing check.
 */
public final class FailClosedBlsSignatureVerifier implements BlsSignatureVerifier {

    public static final FailClosedBlsSignatureVerifier INSTANCE = new FailClosedBlsSignatureVerifier();

    private FailClosedBlsSignatureVerifier() {}

    @Override
    public boolean fastAggregateVerify(
            @NonNull final List<byte[]> publicKeys, @NonNull final byte[] message, @NonNull final byte[] signature) {
        return false;
    }
}
