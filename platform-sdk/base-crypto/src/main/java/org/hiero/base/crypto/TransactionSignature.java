// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.crypto;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.base.utility.ToStringBuilder;
import java.util.HashMap;
import java.util.Objects;

/**
 * Encapsulates a cryptographic signature along with the public key to use during verification. In order to maintain the
 * overall throughput and latency profiles of the hashgraph implementation, this class is an immutable representation of
 * a cryptographic signature. Multiple overloaded constructors have been provided to facilitate ease of use when copying
 * an existing signature.
 */
public class TransactionSignature {

    /** Signed message. */
    private final Bytes message;

    /** The public key. */
    private final Bytes publicKey;

    /** The signature. */
    private final Bytes signature;

    /** The type of cryptographic algorithm used to create the signature. */
    private final SignatureType signatureType;

    /** Indicates whether the signature is valid/invalid or has yet to be verified. */
    private VerificationStatus signatureStatus;

    /**
     * Constructs an immutable signature of the given cryptographic algorithm using the provided signature,
     * public key, and original message.
     *
     * @param message          a pointer to a byte buffer containing the message, signature, and public key
     * @param publicKey   the index where the public key begins in the contents array
     * @param signature        the index where the signature begins in the contents array
     * @param signatureType     the cryptographic algorithm used to create the signature
     */
    public TransactionSignature(
            final Bytes message, final Bytes publicKey, final Bytes signature, final SignatureType signatureType) {
        this.message = message;
        this.publicKey = publicKey;
        this.signature = signature;
        this.signatureType = signatureType;
        this.signatureStatus = VerificationStatus.UNKNOWN;
    }

    /**
     * Returns the message.
     *
     * @return the message
     */
    public Bytes getMessage() {
        return message;
    }

    /**
     * Returns the public key.
     *
     * @return the public key
     */
    public Bytes getPublicKey() {
        return publicKey;
    }

    /**
     * Returns the signature.
     *
     * @return the signature
     */
    public Bytes getSignature() {
        return signature;
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

        TransactionSignature other = (TransactionSignature) obj;
        return signature.equals(other.signature)
                && message.equals(other.message)
                && publicKey.equals(other.publicKey)
                && signatureType == other.signatureType;
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
        return Objects.hash(message.hashCode(), publicKey.hashCode(), signature.hashCode(), signatureType);
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this)
                .append("message", message.toString())
                .append("publicKey", publicKey.toString())
                .append("signature", signature.toString())
                .append("signatureType", signatureType)
                .append("signatureStatus", signatureStatus)
                .toString();
    }
}
