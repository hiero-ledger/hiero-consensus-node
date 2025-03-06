// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.keys;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.security.KeyPair;
import java.security.Provider;
import net.i2p.crypto.eddsa.EdDSAPrivateKey;
import net.i2p.crypto.eddsa.EdDSAPublicKey;
import net.i2p.crypto.eddsa.EdDSASecurityProvider;
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveSpec;
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable;
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec;
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.openssl.jcajce.JceOpenSSLPKCS8DecryptorProviderBuilder;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo;
import org.bouncycastle.pkcs.PKCSException;

/**
 * Minimal utility to read/write a single Ed25519 key from/to an encrypted PEM file.
 */
public final class Ed25519Utils {
    private static final Provider BC_PROVIDER = new BouncyCastleProvider();
    private static final Provider ED_PROVIDER = new EdDSASecurityProvider();

    public static final EdDSANamedCurveSpec ED25519_PARAMS =
            EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519);

    public static KeyPair readKeyPairFrom(final File pem, final String passphrase) {
        return keyPairFrom(readKeyFrom(pem, passphrase));
    }

    public static EdDSAPrivateKey readKeyFrom(final String pemLoc, final String passphrase) {
        return readKeyFrom(new File(pemLoc), passphrase);
    }

    public static EdDSAPrivateKey readKeyFrom(final File pem, final String passphrase) {
        final var relocatedPem = KeyUtils.relocatedIfNotPresentInWorkingDir(pem);
        try (final var in = new FileInputStream(relocatedPem)) {
            final var decryptProvider = new JceOpenSSLPKCS8DecryptorProviderBuilder()
                    .setProvider(BC_PROVIDER)
                    .build(passphrase.toCharArray());
            final var converter = new JcaPEMKeyConverter().setProvider(ED_PROVIDER);
            try (final var parser = new PEMParser(new InputStreamReader(in))) {
                final var encryptedPrivateKeyInfo = (PKCS8EncryptedPrivateKeyInfo) parser.readObject();
                final var info = encryptedPrivateKeyInfo.decryptPrivateKeyInfo(decryptProvider);
                return (EdDSAPrivateKey) converter.getPrivateKey(info);
            }
        } catch (final IOException | OperatorCreationException | PKCSException e) {
            throw new IllegalArgumentException(e);
        }
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
