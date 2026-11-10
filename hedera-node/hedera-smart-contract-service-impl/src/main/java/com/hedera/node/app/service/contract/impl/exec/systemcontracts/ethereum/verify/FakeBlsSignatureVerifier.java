// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.ethereum.verify;

import com.hedera.node.app.service.clpr.impl.verifier.ethereum.BlsSignatureVerifier;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;

/**
 * Temporary, <b>insecure</b> stand-in {@link BlsSignatureVerifier} that accepts every aggregate
 * signature without performing any BLS12-381 pairing check.
 * It should be replaced by a real implementation before production.
 */
public final class FakeBlsSignatureVerifier implements BlsSignatureVerifier {

    public static final FakeBlsSignatureVerifier INSTANCE = new FakeBlsSignatureVerifier();

    private FakeBlsSignatureVerifier() {}

    @Override
    public boolean verifySyncAggregate(
            @NonNull final byte[] aggregatePubkey,
            @NonNull final List<byte[]> nonSignerPubkeys,
            @NonNull final byte[] message,
            @NonNull final byte[] signature) {
        return true;
    }
}
