// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.crypto;

import static org.hiero.base.utility.test.fixtures.RandomUtils.getRandomPrintSeed;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.common.test.fixtures.io.InputOutputStream;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.SignatureException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.Random;
import org.hiero.base.crypto.internal.DetRandomProvider;
import org.hiero.consensus.model.roster.SerializableX509Certificate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SerializableX509CertificateTests {

    @Test
    @DisplayName("SerializableX509Certificate serialize and deserialize")
    void serializeDeserialize()
            throws NoSuchAlgorithmException, NoSuchProviderException, IOException, KeyGeneratingException,
                    InvalidKeyException, SignatureException, CertificateEncodingException {

        final String rsaKeyType = "RSA";
        final int rsaKeySize = 3072;
        final String ecKeyType = "EC";
        final int ecKeySize = 384;

        final Random nonSecureRandom = getRandomPrintSeed();
        final SecureRandom secureRandom = DetRandomProvider.getDetRandom();
        secureRandom.setSeed(nonSecureRandom.nextLong());

        // Render key pairs.
        KeyPairGenerator rsaKeyGen = KeyPairGenerator.getInstance(rsaKeyType);
        rsaKeyGen.initialize(rsaKeySize, secureRandom);
        final KeyPair rsaKeyPair = rsaKeyGen.generateKeyPair();

        KeyPairGenerator ecKeyGen = KeyPairGenerator.getInstance(ecKeyType);
        ecKeyGen.initialize(ecKeySize, secureRandom);
        final KeyPair ecKeyPair = ecKeyGen.generateKeyPair();

        // create self-signed certificate using X500 name
        final String name = "CN=Carol";
        // RSA cert is self-signed
        final X509Certificate rsaCert =
                CryptoStatic.generateCertificate(name, rsaKeyPair, name, rsaKeyPair, secureRandom);
        // EC cert is signed by RSA key
        final X509Certificate ecCert =
                CryptoStatic.generateCertificate(name, ecKeyPair, name, rsaKeyPair, secureRandom);

        final SerializableX509Certificate rsaOriginal = new SerializableX509Certificate(rsaCert);
        SerializableX509Certificate rsaCopy = roundTripSerializeDeserialize(rsaOriginal);
        final SerializableX509Certificate ecOriginal = new SerializableX509Certificate(ecCert);
        SerializableX509Certificate ecCopy = roundTripSerializeDeserialize(ecOriginal);

        // RSA verify signing with the public key that was deserialized.
        verifySigning(rsaCopy, rsaKeyType, name.getBytes(), rsaKeyPair);
        verifySigning(ecCopy, ecKeyType, name.getBytes(), ecKeyPair);
    }

    /**
     * serializes and deserialized the given certificate and validates that the encodings are the same after the round
     * trip.  The deserialized certificate is returned.
     *
     * @param original the serializable certificate to serialize and deserialize
     * @return the deserialized copy of the certificate
     * @throws IOException
     * @throws CertificateEncodingException
     */
    @NonNull
    private SerializableX509Certificate roundTripSerializeDeserialize(
            @NonNull final SerializableX509Certificate original) throws IOException, CertificateEncodingException {
        InputOutputStream io = new InputOutputStream();
        io.getOutput().writeSerializable(original, false);
        io.startReading();
        SerializableX509Certificate copy = io.getInput().readSerializable(false, SerializableX509Certificate::new);
        io.close();

        // validate encodings are the same after round trip
        assertArrayEquals(
                original.getCertificate().getEncoded(), copy.getCertificate().getEncoded());
        return copy;
    }

    /**
     * Validates that the data signed by the private key of the key pair can be verified by the public key of the
     * certificate.
     *
     * @param copy    the copy of the certificate after round trip serialize and deserialize
     * @param keyType the type of key
     * @param data    the data payload to sign
     * @param keyPair the original key pair with the private key for signing
     * @throws NoSuchAlgorithmException
     * @throws InvalidKeyException
     * @throws SignatureException
     * @throws CertificateEncodingException
     * @throws NoSuchProviderException
     */
    private void verifySigning(
            @NonNull final SerializableX509Certificate copy,
            @NonNull final String keyType,
            @NonNull final byte[] data,
            @NonNull final KeyPair keyPair)
            throws NoSuchAlgorithmException, InvalidKeyException, SignatureException, CertificateEncodingException,
                    NoSuchProviderException {
        Signature signer = keyType.compareTo("EC") == 0
                ? Signature.getInstance("SHA256withECDSA", "SunEC")
                : Signature.getInstance(keyType);
        signer.initSign(keyPair.getPrivate());
        signer.update(data);

        byte[] signature = signer.sign();

        Signature verifier = keyType.compareTo("EC") == 0
                ? Signature.getInstance("SHA256withECDSA", "SunEC")
                : Signature.getInstance(keyType);
        verifier.initVerify(copy.getCertificate().getPublicKey());
        verifier.update(data);
        assertTrue(verifier.verify(signature));
    }
}
