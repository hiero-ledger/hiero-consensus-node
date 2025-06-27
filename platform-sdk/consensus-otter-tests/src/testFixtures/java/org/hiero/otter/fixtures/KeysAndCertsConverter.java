// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures;

import static java.util.Objects.requireNonNull;

import com.google.protobuf.ByteString;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.ByteArrayInputStream;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.otter.fixtures.container.proto.ProtoKeysAndCerts;

/**
 * Utility class for converting between {@link KeysAndCerts} and {@link ProtoKeysAndCerts}.
 * Provides methods to serialize and deserialize cryptographic keys and certificates
 * for use in protocol buffers.
 */
public class KeysAndCertsConverter {
    private KeysAndCertsConverter() {}

    /**
     * Converts a {@link KeysAndCerts} instance to a {@link ProtoKeysAndCerts} protocol buffer.
     *
     * @param keysAndCerts the {@link KeysAndCerts} instance to convert; must not be null
     * @return the corresponding {@link ProtoKeysAndCerts} protocol buffer
     * @throws RuntimeException if a certificate encoding error occurs
     */
    public static ProtoKeysAndCerts toProto(@NonNull final KeysAndCerts keysAndCerts) {
        requireNonNull(keysAndCerts, "keysAndCerts must not be null");
        try {
            return ProtoKeysAndCerts.newBuilder()
                    .setSigKeyType(keysAndCerts.sigKeyPair().getPrivate().getAlgorithm())
                    .setSigPrivateKey(ByteString.copyFrom(
                            keysAndCerts.sigKeyPair().getPrivate().getEncoded()))
                    .setSigPublicKey(ByteString.copyFrom(
                            keysAndCerts.sigKeyPair().getPublic().getEncoded()))
                    .setAgrKeyType(keysAndCerts.agrKeyPair().getPrivate().getAlgorithm())
                    .setAgrPrivateKey(ByteString.copyFrom(
                            keysAndCerts.agrKeyPair().getPrivate().getEncoded()))
                    .setAgrPublicKey(ByteString.copyFrom(
                            keysAndCerts.agrKeyPair().getPublic().getEncoded()))
                    .setSigCertType(keysAndCerts.sigCert().getType())
                    .setSigCertificate(
                            ByteString.copyFrom(keysAndCerts.sigCert().getEncoded()))
                    .setAgrCertType(keysAndCerts.agrCert().getType())
                    .setAgrCertificate(
                            ByteString.copyFrom(keysAndCerts.agrCert().getEncoded()))
                    .build();
        } catch (CertificateEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Converts a {@link ProtoKeysAndCerts} protocol buffer to a {@link KeysAndCerts} instance.
     *
     * @param proto the {@link ProtoKeysAndCerts} protocol buffer to convert; must not be null
     * @return the corresponding {@link KeysAndCerts} instance
     * @throws RuntimeException if a certificate or key decoding error occurs
     */
    public static KeysAndCerts fromProto(@NonNull final ProtoKeysAndCerts proto) {
        requireNonNull(proto, "proto cannot be null");
        try {
            final KeyFactory sigKeyFactory = KeyFactory.getInstance(proto.getSigKeyType());
            final PrivateKey sigPriv = sigKeyFactory.generatePrivate(
                    new PKCS8EncodedKeySpec(proto.getSigPrivateKey().toByteArray()));
            final PublicKey sigPub = sigKeyFactory.generatePublic(
                    new X509EncodedKeySpec(proto.getSigPublicKey().toByteArray()));
            final KeyPair sigPair = new KeyPair(sigPub, sigPriv);

            final KeyFactory agrKeyFactory = KeyFactory.getInstance(proto.getAgrKeyType());
            final PrivateKey agrPriv = agrKeyFactory.generatePrivate(
                    new PKCS8EncodedKeySpec(proto.getAgrPrivateKey().toByteArray()));
            final PublicKey agrPub = agrKeyFactory.generatePublic(
                    new X509EncodedKeySpec(proto.getAgrPublicKey().toByteArray()));
            final KeyPair agrPair = new KeyPair(agrPub, agrPriv);

            final CertificateFactory certFactory = CertificateFactory.getInstance(proto.getSigCertType());
            final X509Certificate sigCert = (X509Certificate) certFactory.generateCertificate(
                    new ByteArrayInputStream(proto.getSigCertificate().toByteArray()));

            final X509Certificate agrCert = (X509Certificate) certFactory.generateCertificate(
                    new ByteArrayInputStream(proto.getAgrCertificate().toByteArray()));

            return new KeysAndCerts(sigPair, agrPair, sigCert, agrCert);
        } catch (CertificateException | NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new RuntimeException(e);
        }
    }
}
