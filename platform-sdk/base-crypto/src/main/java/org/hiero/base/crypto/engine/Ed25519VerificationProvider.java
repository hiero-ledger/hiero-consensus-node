// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.crypto.engine;

import static com.swirlds.logging.legacy.LogMarker.TESTING_EXCEPTIONS;
import static org.hiero.base.utility.CommonUtils.hex;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.SignatureType;
import org.hiero.base.crypto.TransactionSignature;

/**
 * Implementation of an Ed25519 signature verification provider. This implementation only supports
 * Ed25519 signatures and depends on libSodium (via Panama FFM) for all operations.
 */
public class Ed25519VerificationProvider
        extends OperationProvider<TransactionSignature, Void, Boolean, LibSodiumEd25519, SignatureType> {

    private static final Logger logger = LogManager.getLogger(Ed25519VerificationProvider.class);

    private static final LibSodiumEd25519 algorithm = LibSodiumEd25519.INSTANCE;

    /**
     * Default Constructor.
     */
    public Ed25519VerificationProvider() {
        super();
    }

    /**
     * Computes the result of the cryptographic transformation using the provided item and algorithm. This
     * implementation defaults to an Ed25519 signature and is provided for convenience.
     *
     * @param message
     * 		the original message that was signed
     * @param signature
     * 		the signature to be verified
     * @param publicKey
     * 		the public key used to verify the signature
     * @return true if the provided signature is valid; false otherwise
     */
    protected boolean compute(final byte[] message, final byte[] signature, final byte[] publicKey) {
        return compute(message, signature, publicKey, SignatureType.ED25519);
    }

    /**
     * Computes the result of the cryptographic transformation using the provided item and algorithm.
     *
     * @param algorithmType
     * 		the type of algorithm to be used when performing the transformation
     * @param message
     * 		the original message that was signed
     * @param signature
     * 		the signature to be verified
     * @param publicKey
     * 		the public key used to verify the signature
     * @return true if the provided signature is valid; false otherwise
     */
    protected boolean compute(
            final byte[] message, final byte[] signature, final byte[] publicKey, final SignatureType algorithmType) {
        final LibSodiumEd25519 loadedAlgorithm = loadAlgorithm(algorithmType);
        return compute(loadedAlgorithm, algorithmType, message, signature, publicKey);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected LibSodiumEd25519 loadAlgorithm(final SignatureType algorithmType) {
        return algorithm;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected Boolean handleItem(
            final LibSodiumEd25519 algorithm,
            final SignatureType algorithmType,
            final TransactionSignature sig,
            final Void optionalData) {
        return compute(
                algorithm,
                algorithmType,
                sig.getMessage().toByteArray(),
                sig.getSignature().toByteArray(),
                sig.getPublicKey().toByteArray());
    }

    /**
     * Computes the result of the cryptographic transformation using the provided item and algorithm.
     *
     * @param algorithm
     * 		the concrete instance of the required algorithm
     * @param algorithmType
     * 		the type of algorithm to be used when performing the transformation
     * @param message
     * 		the original message that was signed
     * @param signature
     * 		the signature to be verified
     * @param publicKey
     * 		the public key used to verify the signature
     * @return true if the provided signature is valid; false otherwise
     */
    private boolean compute(
            final LibSodiumEd25519 algorithm,
            final SignatureType algorithmType,
            final byte[] message,
            final byte[] signature,
            final byte[] publicKey) {
        final boolean isValid = algorithm.cryptoSignVerifyDetached(signature, message, message.length, publicKey);

        if (!isValid && logger.isDebugEnabled()) {
            logger.debug(
                    TESTING_EXCEPTIONS.getMarker(),
                    "Adv Crypto Subsystem: Signature Verification Failure for signature type {} [ publicKey = {}, "
                            + "signature = {} ]",
                    algorithmType,
                    hex(publicKey),
                    hex(signature));
        }

        return isValid;
    }
}
