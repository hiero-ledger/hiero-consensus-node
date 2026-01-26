// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.crypto;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.security.KeyPair;
import org.hiero.base.crypto.BytesSigner;
import org.hiero.base.crypto.Signature;
import org.hiero.base.crypto.SignatureType;
import org.hiero.base.crypto.Signer;
import org.hiero.consensus.model.node.KeysAndCerts;

/**
 * An instance capable of signing data with the platforms private signing key. This class is not thread safe.
 */
public class PlatformSigner implements Signer, BytesSigner {
    private final BytesSigner signer;
    private final SignatureType signatureType;

    /**
     * @param keysAndCerts the platform's keys and certificates
     */
    public PlatformSigner(@NonNull final KeysAndCerts keysAndCerts) {
        this(keysAndCerts.sigKeyPair());
    }

    /**
     * @param sigKeyPair the signing key pair
     */
    public PlatformSigner(@NonNull final KeyPair sigKeyPair) {
        this.signer = SigningFactory.createSigner(sigKeyPair);
        final SigningSchema schema = SigningSchema.fromKeyType(sigKeyPair.getPrivate());
        this.signatureType = switch (schema) {
            case RSA -> SignatureType.RSA;
            case ED25519 -> SignatureType.ED25519;
        };
    }

    @Override
    public @NonNull Signature sign(@NonNull final byte[] data) {
        return new Signature(signatureType, sign(Bytes.wrap(data)));
    }

    @Override
    public @NonNull Bytes sign(@NonNull final Bytes data) {
        return signer.sign(data);
    }
}
