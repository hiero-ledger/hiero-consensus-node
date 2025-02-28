// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.crypto;

import com.swirlds.base.utility.ToStringBuilder;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Objects;

/**
 * Encapsulates a cryptographic signature along with the public key to use during verification. In order to maintain the
 * overall throughput and latency profiles of the hashgraph implementation, this class is an immutable representation of
 * a cryptographic signature. Multiple overloaded constructors have been provided to facilitate ease of use when copying
 * an existing signature.
 */
public class TransactionSignature implements Comparable<TransactionSignature> {

    /** Pointer to the transaction contents. */
    private final byte[] contents;

    /** The offset of the message contained in the contents array. */
    private final int messageOffset;

    /** The length of the message contained in the contents array. */
    private final int messageLength;

    /** The offset of the public key contained in the contents array. */
    private final int publicKeyOffset;

    /** The length of the public key contained in the contents array. */
    private final int publicKeyLength;

    /** The offset of the signature contained in the contents array. */
    private final int signatureOffset;

    /** The length of the signature contained in the contents array. */
    private final int signatureLength;

    /** The type of cryptographic algorithm used to create the signature. */
    private final SignatureType signatureType;

    /** Indicates whether the signature is valid/invalid or has yet to be verified. */
    private VerificationStatus signatureStatus;

    /**
     * Constructs an immutable signature of the given cryptographic algorithm using the provided signature pointer,
     * public key pointer, and original message pointer.
     *
     * @param contents          a pointer to a byte buffer containing the message, signature, and public key
     * @param signatureOffset   the index where the signature begins in the contents array
     * @param signatureLength   the length of the signature (in bytes)
     * @param publicKeyOffset   the index where the public key begins in the contents array
     * @param publicKeyLength   the length of the public key (in bytes)
     * @param messageOffset     the index where the message begins in the contents array
     * @param messageLength     the length of the message (in bytes)
     * @param signatureType     the cryptographic algorithm used to create the signature
     * @throws NullPointerException     if the {@code contents} array is null or zero length
     * @throws IllegalArgumentException if any of the offsets or lengths fall outside the bounds of the {@code contents}
     *                                  array
     */
    public TransactionSignature(
            final byte[] contents,
            final int signatureOffset,
            final int signatureLength,
            final int publicKeyOffset,
            final int publicKeyLength,
            final int messageOffset,
            final int messageLength,
            final SignatureType signatureType) {
        if (contents == null || contents.length == 0) {
            throw new NullPointerException("contents");
        }

        if (signatureOffset < 0 || signatureOffset > contents.length) {
            throw new IllegalArgumentException("signatureOffset");
        }

        if (signatureLength < 0
                || signatureLength > contents.length
                || signatureLength + signatureOffset > contents.length) {
            throw new IllegalArgumentException("signatureLength");
        }

        if (publicKeyOffset < 0 || publicKeyOffset > contents.length) {
            throw new IllegalArgumentException("publicKeyOffset");
        }

        if (publicKeyLength < 0
                || publicKeyLength > contents.length
                || publicKeyLength + publicKeyOffset > contents.length) {
            throw new IllegalArgumentException("publicKeyLength");
        }

        if (messageOffset < 0 || messageOffset > contents.length) {
            throw new IllegalArgumentException("messageOffset");
        }

        if (messageLength < 0 || messageLength > contents.length || messageLength + messageOffset > contents.length) {
            throw new IllegalArgumentException("messageLength");
        }

        this.contents = contents;

        this.signatureOffset = signatureOffset;
        this.signatureLength = signatureLength;

        this.publicKeyOffset = publicKeyOffset;
        this.publicKeyLength = publicKeyLength;

        this.messageOffset = messageOffset;
        this.messageLength = messageLength;

        this.signatureType = signatureType;
        this.signatureStatus = VerificationStatus.UNKNOWN;
    }

    /**
     * Returns the transaction payload. This method returns a copy of the original payload.
     * <p>
     * This method is thread-safe and guaranteed to be atomic in nature.
     *
     * @return the transaction payload
     */
    public byte[] getContents() {
        return (contents != null) ? contents.clone() : null;
    }

    /**
     * Internal use accessor that returns a direct (mutable) reference to the transaction contents/payload. Care must be
     * taken to never modify the array returned by this accessor. Modifying the array will result in undefined behaviors
     * and will result in a violation of the immutability contract provided by the {@link TransactionSignature} object.
     * <p>
     * This method exists solely to allow direct access by the platform for performance reasons.
     *
     * @return a direct reference to the transaction content/payload
     */
    public byte[] getContentsDirect() {
        return contents;
    }

    /**
     * Returns the offset in the {@link #getContents()} array where the message begins.
     *
     * @return the offset to the beginning of the message
     */
    public int getMessageOffset() {
        return messageOffset;
    }

    /**
     * Returns the length in bytes of the message.
     *
     * @return the length in bytes
     */
    public int getMessageLength() {
        return messageLength;
    }

    /**
     * Returns the offset where the public key begins.
     *
     * @return the offset to the beginning of the public key
     */
    public int getPublicKeyOffset() {
        return publicKeyOffset;
    }

    /**
     * Returns the length in bytes of the public key.
     *
     * @return the length in bytes
     */
    public int getPublicKeyLength() {
        return publicKeyLength;
    }

    /**
     * Returns the offset in the {@link #getContents()} array where the signature begins.
     *
     * @return the offset to the beginning of the signature
     */
    public int getSignatureOffset() {
        return signatureOffset;
    }

    /**
     * Returns the length in bytes of the signature.
     *
     * @return the length in bytes
     */
    public int getSignatureLength() {
        return signatureLength;
    }

    /**
     * Returns the type of cryptographic algorithm used to create &amp; verify this signature.
     *
     * @return the type of cryptographic algorithm
     */
    public SignatureType getSignatureType() {
        return signatureType;
    }

    /**
     * Returns the status of the signature verification. If the transaction does not yet have consensus then the value
     * may be {@link VerificationStatus#UNKNOWN}; however, once the transaction reaches consensus then the value must
     * not be {@link VerificationStatus#UNKNOWN}.
     *
     * @return the state of the signature (not verified, valid, invalid)
     */
    public VerificationStatus getSignatureStatus() {
        return signatureStatus;
    }

    /**
     * Internal use only setter for assigning or updating the validity of this signature .
     *
     * @param signatureStatus the new state of the signature verification
     */
    public void setSignatureStatus(final VerificationStatus signatureStatus) {
        this.signatureStatus = signatureStatus;
    }

    /**
     * Indicates whether some other object is "equal to" this one.
     * <p>
     * The {@code equals} method implements an equivalence relation on non-null object references:
     * <ul>
     * <li>It is <i>reflexive</i>: for any non-null reference value {@code x}, {@code x.equals(x)} should return
     * {@code true}.
     * <li>It is <i>symmetric</i>: for any non-null reference values {@code x} and {@code y}, {@code x.equals(y)} should
     * return {@code true} if and only if {@code y.equals(x)} returns {@code true}.
     * <li>It is <i>transitive</i>: for any non-null reference values {@code x}, {@code y}, and {@code z}, if
     * {@code x.equals(y)} returns {@code true} and {@code y.equals(z)} returns {@code true}, then {@code x.equals(z)}
     * should return {@code true}.
     * <li>It is <i>consistent</i>: for any non-null reference values {@code x} and {@code y}, multiple invocations of
     * {@code x.equals(y)} consistently return {@code true} or consistently return {@code false}, provided no
     * information used in {@code equals} comparisons on the objects is modified.
     * <li>For any non-null reference value {@code x}, {@code x.equals(null)} should return {@code false}.
     * </ul>
     * <p>
     * The {@code equals} method for class {@code Object} implements the most discriminating possible equivalence
     * relation on objects; that is, for any non-null reference values {@code x} and {@code y}, this method returns
     * {@code true} if and only if {@code x} and {@code y} refer to the same object ({@code x == y} has the value
     * {@code true}).
     * <p>
     * Note that it is generally necessary to override the {@code hashCode} method whenever this method is overridden,
     * so as to maintain the general contract for the {@code hashCode} method, which states that equal objects must have
     * equal hash codes.
     *
     * @param obj the reference object with which to compare.
     * @return {@code true} if this object is the same as the obj argument; {@code false} otherwise.
     * @see #hashCode()
     * @see HashMap
     */
    @Override
    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        }

        if (!(obj instanceof TransactionSignature)) {
            return false;
        }

        TransactionSignature signature = (TransactionSignature) obj;
        return messageOffset == signature.messageOffset
                && messageLength == signature.messageLength
                && publicKeyOffset == signature.publicKeyOffset
                && publicKeyLength == signature.publicKeyLength
                && signatureOffset == signature.signatureOffset
                && signatureLength == signature.signatureLength
                && Arrays.equals(contents, signature.contents)
                && signatureType == signature.signatureType;
    }

    /**
     * Returns a hash code value for the object. This method is supported for the benefit of hash tables such as those
     * provided by {@link HashMap}.
     * <p>
     * The general contract of {@code hashCode} is:
     * <ul>
     * <li>Whenever it is invoked on the same object more than once during an execution of a Java application, the
     * {@code hashCode} method must consistently return the same integer, provided no information used in {@code equals}
     * comparisons on the object is modified. This integer need not remain consistent from one execution of an
     * application to another execution of the same application.
     * <li>If two objects are equal according to the {@code equals(Object)} method, then calling the {@code hashCode}
     * method on each of the two objects must produce the same integer result.
     * <li>It is <em>not</em> required that if two objects are unequal according to the {@link Object#equals(Object)}
     * method, then calling the {@code hashCode} method on each of the two objects must produce distinct integer
     * results. However, the programmer should be aware that producing distinct integer results for unequal objects may
     * improve the performance of hash tables.
     * </ul>
     * <p>
     * As much as is reasonably practical, the hashCode method defined by class {@code Object} does return distinct
     * integers for distinct objects. (The hashCode may or may not be implemented as some function of an object's memory
     * address at some point in time.)
     *
     * @return a hash code value for this object.
     * @see Object#equals(Object)
     * @see System#identityHashCode
     */
    @Override
    public int hashCode() {
        int result = Objects.hash(
                messageOffset,
                messageLength,
                publicKeyOffset,
                publicKeyLength,
                signatureOffset,
                signatureLength,
                signatureType);
        result = 31 * result + Arrays.hashCode(contents);
        return result;
    }

    /**
     * Compares this object with the specified object for order. Returns a negative integer, zero, or a positive integer
     * as this object is less than, equal to, or greater than the specified object.
     *
     * <p>
     * The implementor must ensure {@code sgn(x.compareTo(y)) == -sgn(y.compareTo(x))} for all {@code x} and {@code y}.
     * (This implies that {@code x.compareTo(y)} must throw an exception iff {@code y.compareTo(x)} throws an
     * exception.)
     *
     * <p>
     * The implementor must also ensure that the relation is transitive:
     * {@code (x.compareTo(y) > 0 && y.compareTo(z) > 0)} implies {@code x.compareTo(z) > 0}.
     *
     * <p>
     * Finally, the implementor must ensure that {@code x.compareTo(y)==0} implies that
     * {@code sgn(x.compareTo(z)) == sgn(y.compareTo(z))}, for all {@code z}.
     *
     * <p>
     * It is strongly recommended, but <i>not</i> strictly required that {@code (x.compareTo(y)==0) == (x.equals(y))}.
     * Generally speaking, any class that implements the {@code Comparable} interface and violates this condition should
     * clearly indicate this fact. The recommended language is "Note: this class has a natural ordering that is
     * inconsistent with equals."
     *
     * <p>
     * In the foregoing description, the notation {@code sgn(}<i>expression</i>{@code )} designates the mathematical
     * <i>signum</i> function, which is defined to return one of {@code -1}, {@code 0}, or {@code 1} according to
     * whether the value of <i>expression</i> is negative, zero, or positive, respectively.
     *
     * @param that the object to be compared.
     * @return a negative integer, zero, or a positive integer as this object is less than, equal to, or greater than
     * the specified object.
     * @throws NullPointerException if the specified object is null
     * @throws ClassCastException   if the specified object's type prevents it from being compared to this object.
     */
    @Override
    public int compareTo(final TransactionSignature that) {
        if (this == that) {
            return 0;
        }

        if (that == null) {
            throw new NullPointerException();
        }

        int result = Arrays.compare(contents, that.contents);

        if (result != 0) {
            return result;
        }

        result = Integer.compare(messageOffset, that.messageOffset);

        if (result != 0) {
            return result;
        }

        result = Integer.compare(messageLength, that.messageLength);

        if (result != 0) {
            return result;
        }

        result = Integer.compare(publicKeyOffset, that.publicKeyOffset);

        if (result != 0) {
            return result;
        }

        result = Integer.compare(publicKeyLength, that.publicKeyLength);

        if (result != 0) {
            return result;
        }

        result = Integer.compare(signatureOffset, that.signatureOffset);

        if (result != 0) {
            return result;
        }

        result = Integer.compare(signatureLength, that.signatureLength);

        if (result != 0) {
            return result;
        }

        return signatureType.compareTo(that.signatureType);
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this)
                .append("contents", Arrays.toString(contents))
                .append("messageOffset", messageOffset)
                .append("messageLength", messageLength)
                .append("publicKeyOffset", publicKeyOffset)
                .append("publicKeyLength", publicKeyLength)
                .append("signatureOffset", signatureOffset)
                .append("signatureLength", signatureLength)
                .append("signatureType", signatureType)
                .append("signatureStatus", signatureStatus)
                .toString();
    }
}
