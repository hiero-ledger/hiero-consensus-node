// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.stats.signing.algorithms;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.STARTUP;
import static org.hiero.base.utility.CommonUtils.hex;

import java.io.IOException;
import java.security.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bouncycastle.asn1.ASN1InputStream;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.jce.interfaces.ECPublicKey;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jce.spec.ECNamedCurveGenParameterSpec;
import org.bouncycastle.math.ec.ECPoint;
import org.hiero.base.crypto.SignatureType;

/**
 * The base class for all ECDSA {@link  SigningAlgorithm} implementations.
 */
public abstract class ECSigningAlgorithm implements SigningAlgorithm {

    /**
     * use this for all logging, as controlled by the optional data/log4j2.xml file
     */
    private static final Logger logger = LogManager.getLogger(ECSigningAlgorithm.class);

    /**
     * the constant value used by the JCE framework for the ECDSA key algorithm.
     */
    private static final String EC_ALGORITHM_NAME = "EC";

    static {
        // Inject the bouncy castle JCE provider.
        Security.addProvider(new BouncyCastleProvider());
    }

    /**
     * the algorithm for which this instance provides an implementation.
     */
    private final SignatureType signatureType;

    /**
     * the generated public/private key pair.
     */
    private KeyPair keyPair;

    /**
     * the raw public key represented as a packed byte array.
     */
    private byte[] publicKeyBytes;

    /**
     * the encoded or raw private key represented as a byte array.
     */
    private byte[] privateKeyBytes;

    /**
     * the JCE signature instance for this algorithm.
     */
    private Signature signature;

    /**
     * the PRNG instance used by all cryptographic operations.
     */
    private SecureRandom random;

    /**
     * indicates if the algorithm is available and initialized
     */
    private boolean algorithmAvailable;

    /**
     * The internal keccak-256 digest instance used to hash the data to be signed.
     */
    private MessageDigest keccakDigest;

    /**
     * Constructs a new instance that supports the supplied {@link SignatureType} algorithm.
     *
     * @param signatureType
     * 		the algorithm which this instance should support.
     */
    public ECSigningAlgorithm(final SignatureType signatureType) {
        if (!EC_ALGORITHM_NAME.equals(signatureType.keyAlgorithm())) {
            throw new IllegalStateException("Illegal SignatureType Value: Not a DSA Implementation");
        }

        this.signatureType = signatureType;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public byte[] getPublicKey() {
        return publicKeyBytes;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public byte[] getPrivateKey() {
        return privateKeyBytes;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public byte[] sign(final byte[] buffer, final int offset, final int len) throws SignatureException {
        try {
            final byte[] dataHash = keccak256(buffer, offset, len);
            signature.initSign(keyPair.getPrivate(), random);
            signature.update(dataHash, 0, dataHash.length);
            return processSignature(signature.sign());
        } catch (InvalidKeyException | IOException e) {
            throw new SignatureException(e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ExtendedSignature signEx(final byte[] buffer, final int offset, final int len) throws SignatureException {
        try {
            final byte[] dataHash = keccak256(buffer, offset, len);
            signature.initSign(keyPair.getPrivate(), random);
            signature.update(dataHash, 0, dataHash.length);
            final byte[] sig = signature.sign();

            return new ExtendedSignature(processSignature(sig), rawSignatureRCoord(sig), rawSignatureSCoord(sig));
        } catch (InvalidKeyException | IOException e) {
            throw new SignatureException(e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized byte[] hash(final byte[] buffer, final int offset, final int len) {
        return keccak256(buffer, offset, len);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void tryAcquirePrimitives() {
        try {
            final KeyPairGenerator keyPairGenerator =
                    KeyPairGenerator.getInstance(signatureType.keyAlgorithm(), signatureType.provider());
            random = new SecureRandom();

            keyPairGenerator.initialize(new ECNamedCurveGenParameterSpec(signatureType.ellipticalCurve()), random);
            keyPair = keyPairGenerator.generateKeyPair();

            publicKeyBytes = compressPublicKey(keyPair.getPublic());
            privateKeyBytes = keyPair.getPrivate().getEncoded();
            logger.trace(
                    STARTUP.getMarker(),
                    "Public Key Tracing [ signatureType = {}, key = {}, x = {}, y = {} ]",
                    getSignatureType(),
                    hex(publicKeyBytes),
                    hex(rawPublicKeyXCoord(keyPair.getPublic())),
                    hex(rawPublicKeyYCoord(keyPair.getPublic())));

            signature = Signature.getInstance(signatureType.signingAlgorithm(), signatureType.provider());
            keccakDigest = MessageDigest.getInstance("KECCAK-256");
            algorithmAvailable = true;
        } catch (NoSuchAlgorithmException | InvalidAlgorithmParameterException | NoSuchProviderException e) {
            logger.error(
                    EXCEPTION.getMarker(),
                    "Algorithm Disabled: Exception During Initialization [ id = {}, class = {} ]",
                    getId(),
                    this.getClass().getName(),
                    e);
            algorithmAvailable = false;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isAvailable() {
        return algorithmAvailable;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public SignatureType getSignatureType() {
        return signatureType;
    }

    /**
     * Converts an ASN.1 variable length signature to a compacted, fixed length signature.
     *
     * @param sig
     * 		the ASN.1 encoded signature.
     * @return a compacted, fixed length representation of the ASN.1 encoded signature.
     * @throws IOException
     * 		if an error occurs while parsing the ASN.1 encoded signature.
     */
    protected byte[] processSignature(final byte[] sig) throws IOException {
        try (final ASN1InputStream stream = new ASN1InputStream(sig)) {
            final ASN1Sequence seq = (ASN1Sequence) stream.readObject();
            final ASN1Integer rInt = (ASN1Integer) seq.getObjectAt(0);
            final ASN1Integer sInt = (ASN1Integer) seq.getObjectAt(1);

            final byte[] r = rInt.getValue().toByteArray();
            final byte[] s = sInt.getValue().toByteArray();

            return pointCompression(r, s);
        }
    }

    /**
     * Extracts the raw variable length R coordinate from an ASN.1 encoded signature.
     *
     * @param sig
     * 		the ASN.1 encoded signature.
     * @return the raw variable length R coordinate.
     * @throws IOException
     * 		if an error occurs while parsing the ASN.1 encoded signature.
     */
    protected byte[] rawSignatureRCoord(final byte[] sig) throws IOException {
        try (final ASN1InputStream stream = new ASN1InputStream(sig)) {
            final ASN1Sequence seq = (ASN1Sequence) stream.readObject();
            final ASN1Integer rInt = (ASN1Integer) seq.getObjectAt(0);

            return rInt.getValue().toByteArray();
        }
    }

    /**
     * Extracts the raw variable length S coordinate from an ASN.1 encoded signature.
     *
     * @param sig
     * 		the ASN.1 encoded signature.
     * @return the raw variable length S coordinate.
     * @throws IOException
     * 		if an error occurs while parsing the ASN.1 encoded signature.
     */
    protected byte[] rawSignatureSCoord(final byte[] sig) throws IOException {
        try (final ASN1InputStream stream = new ASN1InputStream(sig)) {
            final ASN1Sequence seq = (ASN1Sequence) stream.readObject();
            final ASN1Integer sInt = (ASN1Integer) seq.getObjectAt(1);

            return sInt.getValue().toByteArray();
        }
    }

    /**
     * Converts an ASN.1 encoded variable length public key to a compacted, fixed length raw public key.
     *
     * @param publicKey
     * 		the JCE public key instance.
     * @return a compacted, fixed length raw public key.
     */
    protected byte[] compressPublicKey(final PublicKey publicKey) {
        final ECPublicKey ecPublicKey = (ECPublicKey) publicKey;
        final ECPoint point = ecPublicKey.getQ();

        final byte[] rawX = point.getRawXCoord().toBigInteger().toByteArray();
        final byte[] rawY = point.getRawYCoord().toBigInteger().toByteArray();

        return pointCompression(rawX, rawY);
    }

    /**
     * Extracts the raw variable length X coordinate from an ASN.1 encoded public key.
     *
     * @param publicKey
     * 		the JCE public key instance.
     * @return the raw variable length X coordinate.
     */
    protected byte[] rawPublicKeyXCoord(final PublicKey publicKey) {
        final ECPublicKey ecPublicKey = (ECPublicKey) publicKey;
        final ECPoint point = ecPublicKey.getQ();

        return point.getRawXCoord().toBigInteger().toByteArray();
    }

    /**
     * Extracts the raw variable length Y coordinate from an ASN.1 encoded public key.
     *
     * @param publicKey
     * 		the JCE public key instance.
     * @return the raw variable length Y coordinate.
     */
    protected byte[] rawPublicKeyYCoord(final PublicKey publicKey) {
        final ECPublicKey ecPublicKey = (ECPublicKey) publicKey;
        final ECPoint point = ecPublicKey.getQ();

        return point.getRawYCoord().toBigInteger().toByteArray();
    }

    /**
     * Compresses two raw variable-length big integer coordinates into a single fixed length (x, y) pair represented
     * as a byte array.
     *
     * @param rawX
     * 		the raw variable length X or R coordinate.
     * @param rawY
     * 		the raw variable length Y or S coordinate.
     * @return a fixed length byte array containing the (X, Y) or (R, S) coordinate pair.
     */
    protected byte[] pointCompression(final byte[] rawX, final byte[] rawY) {
        final byte[] rawComposite = new byte[getPublicKeyLength()];
        final int deltaX = rawX.length - getCoordinateSize();
        final int deltaY = rawY.length - getCoordinateSize();

        // If delta values are positive then we strip from the srcStart value; if they are negative then pad in the
        // destStart
        final int srcStartX = Math.max(deltaX, 0);
        final int srcStartY = Math.max(deltaY, 0);
        final int lenX = rawX.length - srcStartX;
        final int lenY = rawY.length - srcStartY;
        final int dstStartX = getCoordinateSize() - lenX;
        final int dstStartY = getCoordinateSize() + (getCoordinateSize() - lenY);

        System.arraycopy(rawX, srcStartX, rawComposite, dstStartX, lenX);
        System.arraycopy(rawY, srcStartY, rawComposite, dstStartY, lenY);

        return rawComposite;
    }

    protected byte[] keccak256(final byte[] buffer, final int offset, final int len) {
        keccakDigest.reset();
        keccakDigest.update(buffer, offset, len);
        return keccakDigest.digest();
    }
}
