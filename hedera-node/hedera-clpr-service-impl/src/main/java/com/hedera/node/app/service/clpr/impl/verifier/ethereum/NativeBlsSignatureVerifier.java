// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.ethereum;

import com.hedera.cryptography.hints.EthereumBlsBridge;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;

/**
 * The {@link BlsSignatureVerifier} of the node: hiero-cryptography's {@link EthereumBlsBridge}, which verifies with the
 * blst library over JNI.
 *
 * <p>The bridge loads its native library on first use, so referencing {@link #INSTANCE} loads nothing.
 */
public final class NativeBlsSignatureVerifier implements BlsSignatureVerifier {

    public static final NativeBlsSignatureVerifier INSTANCE = new NativeBlsSignatureVerifier();

    private NativeBlsSignatureVerifier() {}

    @Override
    public boolean verifySyncAggregate(
            @NonNull final byte[] aggregatePubkey,
            @NonNull final List<byte[]> nonSignerPubkeys,
            @NonNull final byte[] message,
            @NonNull final byte[] signature) {
        return EthereumBlsBridge.getInstance()
                .verifySyncAggregate(aggregatePubkey, nonSignerPubkeys.toArray(byte[][]::new), message, signature);
    }

    @Override
    public boolean validatePubkeys(@NonNull final List<byte[]> pubkeys) {
        return EthereumBlsBridge.getInstance().validateG1Eip2537PublicKeys(pubkeys.toArray(byte[][]::new));
    }
}
