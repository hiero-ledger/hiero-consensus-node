// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.crypto.engine;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.hiero.base.crypto.DigestType;
import org.hiero.base.crypto.Message;

/**
 * Implementation of a message digest provider. This implementation depends on the JCE {@link MessageDigest} providers
 * and supports all algorithms supported by the JVM.
 */
public class DigestProvider extends CachingOperationProvider<Message, Void, byte[], MessageDigest, DigestType> {

    /**
     * Default Constructor.
     */
    public DigestProvider() {
        super();
    }

    /**
     * Computes the result of the cryptographic transformation using the provided message. This
     * implementation defaults to an SHA-384 message digest and is provided for convenience.
     *
     * @param msg
     * 		the message for which to compute a message digest
     * @return true if the provided signature is valid; false otherwise
     * @throws NoSuchAlgorithmException
     * 		if an implementation of the required algorithm cannot be located or loaded
     */
    protected @NonNull byte[] compute(@NonNull final byte[] msg) throws NoSuchAlgorithmException {
        return compute(msg, DigestType.SHA_384);
    }

    /**
     * Computes the result of the cryptographic transformation using the provided message.
     *
     * @param msg
     * 		the message for which to compute a message digest
     * @param algorithmType
     * 		the required algorithm to be used when computing the message digest
     * @return the message digest as an array of the raw bytes
     * @throws NoSuchAlgorithmException
     * 		if an implementation of the required algorithm cannot be located or loaded
     */
    protected @NonNull byte[] compute(@NonNull final byte[] msg, @NonNull final DigestType algorithmType)
            throws NoSuchAlgorithmException {
        if (msg == null) {
            throw new IllegalArgumentException("msg");
        }

        return compute(msg, 0, msg.length, algorithmType);
    }

    /**
     * Computes the result of the cryptographic transformation using the given subset of bytes from the provided
     * message.  This implementation defaults to an SHA-384 message digest and is provided for convenience.
     *
     * @param msg
     * 		the message for which to compute a message digest
     * @param offset
     * 		the starting offset to begin reading
     * @param length
     * 		the total number of bytes to read
     * @param algorithmType
     * 		the message digest algorithm to be used
     * @return the message digest as an array of the raw bytes
     * @throws NoSuchAlgorithmException
     * 		if an implementation of the required algorithm cannot be located or loaded
     */
    private @NonNull byte[] compute(
            @NonNull final byte[] msg, final int offset, final int length, @NonNull final DigestType algorithmType)
            throws NoSuchAlgorithmException {
        final MessageDigest algorithm = loadAlgorithm(algorithmType);
        return compute(algorithm, msg, offset, length);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected @NonNull MessageDigest handleAlgorithmRequired(@NonNull final DigestType algorithmType)
            throws NoSuchAlgorithmException {
        return MessageDigest.getInstance(algorithmType.algorithmName());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected @NonNull byte[] handleItem(
            @NonNull final MessageDigest algorithm,
            @NonNull final DigestType algorithmType,
            @NonNull final Message item,
            @NonNull final Void optionalData) {
        return compute(algorithm, item.getPayloadDirect(), item.getOffset(), item.getLength());
    }

    /**
     * Computes the result of the cryptographic transformation using the given subset of bytes from the provided
     * message.  This implementation defaults to an SHA-384 message digest and is provided for convenience.
     *
     * @param algorithm
     * 		the required algorithm implemented to be used
     * @param msg
     * 		the message for which to compute a message digest
     * @param offset
     * 		the starting offset to begin reading
     * @param length
     * 		the total number of bytes to read
     * @return the message digest as an array of the raw bytes
     */
    private @NonNull byte[] compute(
            @NonNull final MessageDigest algorithm, @NonNull final byte[] msg, final int offset, final int length) {
        algorithm.reset();
        algorithm.update(msg, offset, length);

        return algorithm.digest();
    }
}
