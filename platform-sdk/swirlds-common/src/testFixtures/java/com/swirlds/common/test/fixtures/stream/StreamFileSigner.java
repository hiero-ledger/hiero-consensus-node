// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.test.fixtures.stream;

import static com.swirlds.common.crypto.internal.CryptoUtils.getDetRandom;
import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static org.hiero.base.utility.CommonUtils.hex;

import com.swirlds.common.crypto.SignatureType;
import com.swirlds.common.stream.Signer;
import com.swirlds.common.utility.CommonUtils;
import java.security.InvalidKeyException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.SignatureException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;

public class StreamFileSigner implements Signer {
    public static final String SIG_ALGORITHM = SignatureType.RSA.signingAlgorithm();
    public static final String SIG_PROVIDER = SignatureType.RSA.provider();
    static final String SIG_TYPE = SignatureType.RSA.keyAlgorithm();
    // size (in bits) of a public or private key
    static final int SIG_KEY_SIZE_BITS = 3072;
    /** use this for all logging, as controlled by the optional data/log4j2.xml file */
    private static final Logger logger = LogManager.getLogger(StreamFileSigner.class);

    private static final Marker LOGM_OBJECT_STREAM = MarkerManager.getMarker("OBJECT_STREAM");
    private static final Marker LOGM_EXCEPTION = MarkerManager.getMarker("EXCEPTION");
    private static final int SEED = 2;
    private KeyPair sigKeyPair;

    public StreamFileSigner() {
        try {
            KeyPairGenerator sigKeyGen = KeyPairGenerator.getInstance(SIG_TYPE, SIG_PROVIDER);
            SecureRandom sigDetRandom = getDetRandom(); // deterministic CSPRNG, used briefly then discarded
            sigKeyGen.initialize(SIG_KEY_SIZE_BITS, sigDetRandom);
            sigDetRandom.setSeed(SEED);
            sigKeyPair = sigKeyGen.generateKeyPair();
        } catch (NoSuchAlgorithmException | NoSuchProviderException e) {
            logger.error(EXCEPTION.getMarker(), "Failed to generate KeyPair", e);
            CommonUtils.tellUserConsolePopup(
                    "ERROR", "ERROR: This Java installation does not have the needed cryptography providers installed");
        }
    }

    /**
     * Digitally sign the data with the private key. Return null if anything goes wrong (e.g., bad private
     * key).
     * <p>
     * The returned signature will be at most SIG_SIZE_BYTES bytes, which is 104 for the CNSA suite
     * parameters.
     *
     * @param data
     * 		the data to sign
     * @return the signature (or null if any errors)
     */
    @Override
    public com.swirlds.common.crypto.Signature sign(byte[] data) {
        Signature signature;
        try {
            signature = Signature.getInstance(SIG_ALGORITHM, SIG_PROVIDER);
            signature.initSign(sigKeyPair.getPrivate());
            signature.update(data);
            final byte[] result = signature.sign();
            if (result == null) {
                logger.error(EXCEPTION.getMarker(), "Failed to sign data: signature is null");
            }
            logger.debug(LOGM_OBJECT_STREAM, "Generated signature: {}", () -> hex(result));
            return new com.swirlds.common.crypto.Signature(SignatureType.RSA, result);
        } catch (NoSuchAlgorithmException | NoSuchProviderException | InvalidKeyException | SignatureException e) {
            logger.error(EXCEPTION.getMarker(), "Failed to sign data", e);
        }
        return new com.swirlds.common.crypto.Signature(
                SignatureType.RSA, new byte[SignatureType.RSA.signatureLength()]);
    }

    public PublicKey getPublicKey() {
        return sigKeyPair.getPublic();
    }
}
