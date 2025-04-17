// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.stats.signing.algorithms;

import java.security.SignatureException;
import org.hiero.base.crypto.SignatureType;

/**
 * Defines the interface to which all signature producers must adhere.
 */
public interface SigningAlgorithm {

    /**
     * Gets a unique algorithm identifier.
     *
     * @return the unique identifier for this algorithm.
     */
    byte getId();

    /**
     * Gets the raw public key as a byte array.
     *
     * @return the raw public key bytes.
     */
    byte[] getPublicKey();

    /**
     * Gets the private key as a byte array.
     *
     * @return the raw or encoded private key bytes.
     */
    byte[] getPrivateKey();

    /**
     * Signs the data contained in the buffer using the private key obtained from the {@link #getPrivateKey()} method.
     * The {@code offset} and {@code len} arguments must be within the bounds of the provided {@code buffer} array.
     *
     * @param buffer
     * 		an array containing the data to be signed.
     * @param offset
     * 		starting position from which to begin reading in the {@code buffer} array.
     * @param len
     * 		the number of array elements to be read.
     * @return a byte array containing the raw or encoded signature.
     * @throws SignatureException
     * 		if this signature object is not initialized properly or if this signature algorithm is unable to process the
     * 		input data provided.
     * @throws ArrayIndexOutOfBoundsException
     * 		if the {@code offset} or {@code len} arguments refer to invalid locations or exceed the size of the provided
     * 		array.
     */
    byte[] sign(byte[] buffer, int offset, int len) throws SignatureException;

    /**
     * Signs the data contained in the buffer using the private key obtained from the {@link #getPrivateKey()} method.
     * The {@code offset} and {@code len} arguments must be within the bounds of the provided {@code buffer} array.
     *
     * This version returns an {@link ExtendedSignature} instance containing the same raw or encoded signature as
     * returned by the {@link #sign(byte[], int, int)} method but also includes the raw signature components for an
     * elliptical curve based signature.
     *
     * @param buffer
     * 		an array containing the data to be signed.
     * @param offset
     * 		starting position from which to begin reading in the {@code buffer} array.
     * @param len
     * 		the number of array elements to be read.
     * @return a byte array containing the raw or encoded signature.
     * @throws SignatureException
     * 		if this signature object is not initialized properly or if this signature algorithm is unable to process the
     * 		input data provided.
     * @throws ArrayIndexOutOfBoundsException
     * 		if the {@code offset} or {@code len} arguments refer to invalid locations or exceed the size of the provided
     * 		array.
     */
    ExtendedSignature signEx(byte[] buffer, int offset, int len) throws SignatureException;

    /**
     * Creates a cryptographic digest compatible with the signature algorithm.
     *
     * @param buffer
     * 		an array containing the data to be signed.
     * @param offset
     * 		starting position from which to begin reading in the {@code buffer} array.
     * @param len
     * 		the number of array elements to be read.
     * @return a byte array containing a one-way hash of the data.
     */
    byte[] hash(byte[] buffer, int offset, int len);

    /**
     * Called during initialization to ensure that all necessary cryptographic constructs have been created and
     * initialized. This method is only called once; however, implementations should ensure that is idempotent. This
     * method should (in most cases) also create the Public/Private key pair.
     */
    void tryAcquirePrimitives();

    /**
     * Indicates whether this instance is properly initialized and is able to sign data.
     *
     * @return true if this instance is initialized and ready to sign data, false otherwise.
     */
    boolean isAvailable();

    /**
     * Gets the fixed or maximum length (in bytes) of the signatures produced by this algorithm.
     *
     * @return the exact or maximum length (in bytes) of the signature.
     */
    int getSignatureLength();

    /**
     * Gets the length (in bytes) of the public key.
     *
     * @return the length (in bytes) of the public key.
     */
    int getPublicKeyLength();

    /**
     * Gets the length (in bytes) of a single elliptical curve coordinate. This method may return zero (0) for non-EC
     * algorithms.
     *
     * @return the length (in bytes) of a signle elliptical curve coordinate.
     */
    int getCoordinateSize();

    /**
     * Gets the {@link SignatureType} enumeration value describing the algorithm this instance implements.
     *
     * @return the enumeration value describing the algorithm this instance implements.
     */
    SignatureType getSignatureType();
}
