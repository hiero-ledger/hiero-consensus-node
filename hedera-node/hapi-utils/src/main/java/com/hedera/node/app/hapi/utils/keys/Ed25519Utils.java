// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.keys;

import static java.util.Objects.requireNonNull;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.File;
import java.io.InputStream;
import java.security.KeyPair;
import java.security.Provider;
import net.i2p.crypto.eddsa.EdDSAPrivateKey;
import net.i2p.crypto.eddsa.EdDSAPublicKey;
import net.i2p.crypto.eddsa.EdDSASecurityProvider;
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveSpec;
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable;
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec;
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec;

/**
 * Minimal utility to read/write a single Ed25519 key from/to an encrypted PEM file.
 */
public final class Ed25519Utils {
    private static final EdDSANamedCurveSpec ED25519_PARAMS =
            EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519);
    private static final int ED25519_BYTE_LENGTH = 32;

    public static final Provider ED_PROVIDER = new EdDSASecurityProvider();

    static boolean isValidEd25519Key(@NonNull final Bytes key) {
        return key.length() == ED25519_BYTE_LENGTH;
    }

    public static EdDSAPrivateKey readKeyFrom(final File pem, final String passphrase) {
        return KeyUtils.readKeyFrom(pem, passphrase, ED_PROVIDER);
    }

    public static KeyPair readKeyPairFrom(final File pem, final String passphrase) {
        return keyPairFrom(readKeyFrom(pem, passphrase));
    }

    public static EdDSAPrivateKey readKeyFrom(final String pemLoc, final String passphrase) {
        return KeyUtils.readKeyFrom(new File(pemLoc), passphrase, ED_PROVIDER);
    }

    /**
     * Reads an Ed25519 private key from the given input stream, using the provided passphrase to decrypt it.
     * @param in the input stream to read the key from
     * @param passphrase the passphrase to decrypt the key
     * @return the Ed25519 key
     */
    public static EdDSAPrivateKey readKeyFrom(@NonNull final InputStream in, @NonNull final String passphrase) {
        requireNonNull(in);
        requireNonNull(passphrase);
        return KeyUtils.readKeyFrom(in, passphrase, ED_PROVIDER);
    }

    public static byte[] extractEd25519PublicKey(@NonNull final EdDSAPrivateKey key) {
        return key.getAbyte();
    }

    public static void writeKeyTo(final byte[] seed, final String pemLoc, final String passphrase) {
        KeyUtils.writeKeyTo(keyFrom(seed), pemLoc, passphrase);
    }

    public static EdDSAPrivateKey keyFrom(final byte[] seed) {
        return new EdDSAPrivateKey(new EdDSAPrivateKeySpec(seed, ED25519_PARAMS));
    }

    public static KeyPair keyPairFrom(final EdDSAPrivateKey privateKey) {
        final var publicKey = new EdDSAPublicKey(new EdDSAPublicKeySpec(privateKey.getAbyte(), ED25519_PARAMS));
        return new KeyPair(publicKey, privateKey);
    }

    private Ed25519Utils() {
        throw new UnsupportedOperationException("Utility Class");
    }
}
