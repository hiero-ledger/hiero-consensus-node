// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.crypto;

import com.swirlds.base.utility.ToStringBuilder;
import com.swirlds.common.io.exceptions.BadIOException;
import com.swirlds.common.io.streams.SerializableDataInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Objects;
import java.util.concurrent.Future;

/**
 * Encapsulates a cryptographic signature along with the public key to use during verification. In order to maintain the
 * overall throughput and latency profiles of the hashgraph implementation, this class is an immutable representation of
 * a cryptographic signature. Multiple overloaded constructors have been provided to facilitate ease of use when copying
 * an existing signature.
 */
public class TransactionSignature implements Comparable<TransactionSignature> {

    /** Pointer to the transaction contents. */
    private final byte[] contents;

    /** (Optional) Pointer to actual public key. */
    private final byte[] expandedPublicKey;

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

    /** An internal future used to provide synchronization after the event has reached consensus. */
    private transient volatile Future<Void> future;

    /**
     * Constructs an immutable Ed25519 signature using the provided signature pointer, public key pointer, and original
     * message pointer.
     *
     * @param contents        a pointer to a byte buffer containing the message, signature, and public key
     * @param signatureOffset the index where the signature begins in the contents array
     * @param signatureLength the length of the signature (in bytes)
     * @param publicKeyOffset the index where the public key begins in the contents array
     * @param publicKeyLength the length of the public key (in bytes)
     * @param messageOffset   the index where the message begins in the contents array
     * @param messageLength   the length of the message (in bytes)
     * @throws NullPointerException     if the {@code contents} array is null or zero length
     * @throws IllegalArgumentException if any of the offsets or lengths fall outside the bounds of the {@code contents}
     *                                  array.
     */
    public TransactionSignature(
            final byte[] contents,
            final int signatureOffset,
            final int signatureLength,
            final int publicKeyOffset,
            final int publicKeyLength,
            final int messageOffset,
            final int messageLength) {
        this(
                contents,
                signatureOffset,
                signatureLength,
                publicKeyOffset,
                publicKeyLength,
                messageOffset,
                messageLength,
                SignatureType.ED25519);
    }

    /**
     * Constructs an immutable Ed25519 signature using the provided signature pointer, public key pointer, and original
     * message pointer.
     *
     * @param contents          a pointer to a byte buffer containing the message, signature, and public key
     * @param signatureOffset   the index where the signature begins in the contents array
     * @param signatureLength   the length of the signature (in bytes)
     * @param expandedPublicKey an optional byte array from which retrieve the public key
     * @param publicKeyOffset   the index where the public key begins in the contents array
     * @param publicKeyLength   the length of the public key (in bytes)
     * @param messageOffset     the index where the message begins in the contents array
     * @param messageLength     the length of the message (in bytes)
     * @throws NullPointerException     if the {@code contents} array is null or zero length
     * @throws IllegalArgumentException if any of the offsets or lengths fall outside the bounds of the {@code contents}
     *                                  array
     */
    public TransactionSignature(
            final byte[] contents,
            final int signatureOffset,
            final int signatureLength,
            final byte[] expandedPublicKey,
            final int publicKeyOffset,
            final int publicKeyLength,
            final int messageOffset,
            final int messageLength) {
        this(
                contents,
                signatureOffset,
                signatureLength,
                expandedPublicKey,
                publicKeyOffset,
                publicKeyLength,
                messageOffset,
                messageLength,
                SignatureType.ED25519);
    }

    /**
     * Constructs an immutable signature of the given cryptographic algorithm using the provided signature pointer,
     * public key pointer, and original message pointer.
     *
     * @param contents        a pointer to a byte buffer containing the message, signature, and public key
     * @param signatureOffset the index where the signature begins in the contents array
     * @param signatureLength the length of the signature (in bytes)
     * @param publicKeyOffset the index where the public key begins in the contents array
     * @param publicKeyLength the length of the public key (in bytes)
     * @param messageOffset   the index where the message begins in the contents array
     * @param messageLength   the length of the message (in bytes)
     * @param signatureType   the cryptographic algorithm used to create the signature
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
        this(
                contents,
                signatureOffset,
                signatureLength,
                null,
                publicKeyOffset,
                publicKeyLength,
                messageOffset,
                messageLength,
                signatureType);
    }

    /**
     * Constructs an immutable signature of the given cryptographic algorithm using the provided signature pointer,
     * public key pointer, and original message pointer.
     *
     * @param contents          a pointer to a byte buffer containing the message, signature, and public key
     * @param signatureOffset   the index where the signature begins in the contents array
     * @param signatureLength   the length of the signature (in bytes)
     * @param expandedPublicKey an optional byte array from which retrieve the public key
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
            final byte[] expandedPublicKey,
            final int publicKeyOffset,
            final int publicKeyLength,
            final int messageOffset,
            final int messageLength,
            final SignatureType signatureType) {
        if (contents == null || contents.length == 0) {
            throw new NullPointerException("contents");
        }

        final byte[] publicKeySource = (expandedPublicKey != null) ? expandedPublicKey : contents;

        if (signatureOffset < 0 || signatureOffset > contents.length) {
            throw new IllegalArgumentException("signatureOffset");
        }

        if (signatureLength < 0
                || signatureLength > contents.length
                || signatureLength + signatureOffset > contents.length) {
            throw new IllegalArgumentException("signatureLength");
        }

        if (publicKeyOffset < 0 || publicKeyOffset > publicKeySource.length) {
            throw new IllegalArgumentException("publicKeyOffset");
        }

        if (publicKeyLength < 0
                || publicKeyLength > publicKeySource.length
                || publicKeyLength + publicKeyOffset > publicKeySource.length) {
            throw new IllegalArgumentException("publicKeyLength");
        }

        if (messageOffset < 0 || messageOffset > contents.length) {
            throw new IllegalArgumentException("messageOffset");
        }

        if (messageLength < 0 || messageLength > contents.length || messageLength + messageOffset > contents.length) {
            throw new IllegalArgumentException("messageLength");
        }

        this.contents = contents;
        this.expandedPublicKey = expandedPublicKey;

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
     * Constructs a shallow copy of an existing signature replacing the public key indices and original message indices
     * with the provided values.
     *
     * @param other           the Signature to be copied
     * @param publicKeyOffset an updated public key offset
     * @param publicKeyLength an updated public key length
     * @param messageOffset   an updated message offset
     * @param messageLength   an updated message length
     * @throws NullPointerException     if the {@code other} parameter is null
     * @throws IllegalArgumentException if any of the offsets or lengths fall outside the bounds of the {@code contents}
     *                                  array
     */
    public TransactionSignature(
            final TransactionSignature other,
            final int publicKeyOffset,
            final int publicKeyLength,
            final int messageOffset,
            final int messageLength) {
        this(other, null, publicKeyOffset, publicKeyLength, messageOffset, messageLength);
    }

    /**
     * Constructs a shallow copy of an existing signature replacing the public key source, public key indices and
     * original message indices with the provided values.
     *
     * @param other             the Signature to be copied
     * @param expandedPublicKey an optional byte array from which retrieve the public key
     * @param publicKeyOffset   an updated public key offset
     * @param publicKeyLength   an updated public key length
     * @param messageOffset     an updated message offset
     * @param messageLength     an updated message length
     * @throws NullPointerException     if the {@code other} parameter is null
     * @throws IllegalArgumentException if any of the offsets or lengths fall outside the bounds of the {@code contents}
     *                                  array
     */
    public TransactionSignature(
            final TransactionSignature other,
            final byte[] expandedPublicKey,
            final int publicKeyOffset,
            final int publicKeyLength,
            final int messageOffset,
            final int messageLength) {
        if (other == null) {
            throw new NullPointerException("other");
        }

        final byte[] publicKeySource = (expandedPublicKey != null) ? expandedPublicKey : other.contents;

        if (publicKeyOffset < 0 || publicKeyOffset > publicKeySource.length) {
            throw new IllegalArgumentException("publicKeyOffset");
        }

        if (publicKeyLength < 0
                || publicKeyLength > publicKeySource.length
                || publicKeyLength + publicKeyOffset > publicKeySource.length) {
            throw new IllegalArgumentException("publicKeyLength");
        }

        if (messageOffset < 0 || messageOffset > other.contents.length) {
            throw new IllegalArgumentException("messageOffset");
        }

        if (messageLength < 0
                || messageLength > other.contents.length
                || messageLength + messageOffset > other.contents.length) {
            throw new IllegalArgumentException("messageLength");
        }

        this.contents = other.contents;
        this.signatureOffset = other.signatureOffset;
        this.signatureLength = other.signatureLength;
        this.expandedPublicKey = expandedPublicKey;
        this.publicKeyOffset = publicKeyOffset;
        this.publicKeyLength = publicKeyLength;
        this.messageOffset = messageOffset;
        this.messageLength = messageLength;
        this.signatureType = other.signatureType;
        this.signatureStatus = other.signatureStatus;
    }

    /**
     * Constructs a shallow copy of an existing signature.
     *
     * @param other the Signature to be copied
     * @throws NullPointerException if the {@code other} parameter is null
     */
    public TransactionSignature(final TransactionSignature other) {
        if (other == null) {
            throw new NullPointerException("other");
        }

        this.contents = other.contents;
        this.expandedPublicKey = other.expandedPublicKey;
        this.signatureOffset = other.signatureOffset;
        this.signatureLength = other.signatureLength;
        this.publicKeyOffset = other.publicKeyOffset;
        this.publicKeyLength = other.publicKeyLength;
        this.messageOffset = other.messageOffset;
        this.messageLength = other.messageLength;
        this.signatureType = other.signatureType;
        this.signatureStatus = other.signatureStatus;
    }

    /**
     * Writes a binary representation of this signature, public key, and message to a {@link DataOutputStream}.
     * <p>
     * Maximum number of bytes written is represented by the formula: signature.length + publicKey.length +
     * message.length + (7 * Integer.BYTES)
     * <p>
     * The minimum number of bytes written is represented by the formula: signature.length + (7 * Integer.BYTES)
     *
     * @param signature the {@link TransactionSignature} object to be serialized
     * @param dos       the {@link DataOutputStream} to which the binary representation should be written
     * @param byteCount returns the number of bytes written as the first element in the array or increments the existing
     *                  value by the number of bytes written
     * @throws IOException          if any error occurs while writing to the {@link DataOutputStream}
     * @throws NullPointerException if the {@code signature} or {@code dos} parameters are null
     */
    private static void serialize(
            final TransactionSignature signature, final DataOutputStream dos, final int[] byteCount)
            throws IOException {
        if (signature == null) {
            throw new NullPointerException("signature");
        }

        if (dos == null) {
            throw new NullPointerException("dos");
        }

        final int sigType = signature.signatureType.ordinal();

        // Write Signature Length w/ Simple Checksum
        dos.writeInt(signature.signatureLength);
        dos.writeInt(439 - signature.signatureLength);

        // Write Signature
        dos.writeInt(sigType);
        dos.write(signature.contents, signature.signatureOffset, signature.signatureLength);

        // Write Public Key Length w/ Simple Checksum
        dos.writeInt(signature.publicKeyLength);
        dos.writeInt(541 - signature.publicKeyLength);

        // Write Public Key
        dos.write(
                (signature.expandedPublicKey != null) ? signature.expandedPublicKey : signature.contents,
                signature.publicKeyOffset,
                signature.publicKeyLength);

        // Write Hash Length w/ Simple Checksum
        dos.writeInt(signature.messageLength);
        dos.writeInt(647 - signature.messageLength);

        // Write Message
        dos.write(signature.contents, signature.messageOffset, signature.messageLength);

        if (byteCount != null && byteCount.length > 0) {
            byteCount[0] += (7 * Integer.BYTES)
                    + (signature.signatureLength * Byte.BYTES)
                    + (signature.publicKeyLength * Byte.BYTES)
                    + (signature.messageLength * Byte.BYTES);
        }
    }

    /**
     * Reconstructs a {@link TransactionSignature} object from a binary representation read from a
     * {@link DataInputStream}.
     *
     * @param dis       the {@link DataInputStream} from which to read
     * @param byteCount returns the number of bytes written as the first element in the array or increments the existing
     *                  value by the number of bytes written
     * @return the {@link TransactionSignature} that was read from the input stream
     * @throws IOException          if any error occurs while reading from the {@link DataInputStream}
     * @throws NullPointerException if the {@code dis} parameter is null
     * @throws BadIOException       if the internal checksum cannot be validated
     */
    public static TransactionSignature deserialize(final SerializableDataInputStream dis, final int[] byteCount)
            throws IOException {

        if (dis == null) {
            throw new NullPointerException("dis");
        }

        final int[] totalBytes = new int[] {7 * Integer.BYTES};

        // Read Signature Length w/ Simple Prime Number Checksum
        final int sigLen = dis.readInt();
        final int sigChecksum = dis.readInt();

        if (sigLen < 0 || sigChecksum != (439 - sigLen)) {
            throw new BadIOException("Signature.deserialize tried to create signature array of length " + sigLen
                    + " with wrong checksum.");
        }

        // Read Signature
        final SignatureType sigType = SignatureType.from(dis.readInt(), SignatureType.ED25519);
        final byte[] sig = new byte[sigLen];
        dis.readFully(sig);
        totalBytes[0] += sig.length;

        // Read Public Key Length w/ Simple Prime Number Checksum
        final int pkLen = dis.readInt();
        final int pkChecksum = dis.readInt();

        if (pkLen < 0 || pkChecksum != (541 - pkLen)) {
            throw new BadIOException("Signature.deserialize tried to create public key array of length " + pkLen
                    + " with wrong checksum.");
        }

        // Read Public Key
        final byte[] pk = new byte[pkLen];
        if (pkLen > 0) {
            dis.readFully(pk);
            totalBytes[0] += pk.length;
        }

        // Read Message Length w/ Simple Prime Number Checksum
        final int msgLen = dis.readInt();
        final int msgChecksum = dis.readInt();

        if (msgLen < 0 || msgChecksum != (647 - msgLen)) {
            throw new BadIOException(
                    "Signature.deserialize tried to create message array of length " + pkLen + " with wrong checksum.");
        }

        // Read Message
        final byte[] msg = new byte[msgLen];
        if (msgLen > 0) {
            dis.readFully(msg);
            totalBytes[0] += msg.length;
        }

        if (byteCount != null && byteCount.length > 0) {
            byteCount[0] += totalBytes[0];
        }

        final ByteBuffer buffer = ByteBuffer.allocate(msgLen + pkLen + sigLen);

        buffer.put(msg);
        buffer.put(pk);
        buffer.put(sig);

        return new TransactionSignature(
                buffer.array(), msg.length + pk.length, sig.length, msg.length, pk.length, 0, msg.length, sigType);
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
     * Returns a copy of the optional expanded public key or {@code null} if not provided.
     *
     * @return the optional expanded public key if provided, otherwise {@code null}
     */
    public byte[] getExpandedPublicKey() {
        return (expandedPublicKey != null) ? expandedPublicKey.clone() : null;
    }

    /**
     * Internal use accessor that returns a direct (mutable) reference to the expanded public key. Care must be taken to
     * never modify the array returned by this accessor. Modifying the array will result in undefined behaviors and will
     * result in a violation of the immutability contract provided by the {@link TransactionSignature} object.
     * <p>
     * This method exists solely to allow direct access by the platform for performance reasons.
     *
     * @return a direct reference to the transaction content/payload
     */
    public byte[] getExpandedPublicKeyDirect() {
        return expandedPublicKey;
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
     * Returns the offset where the public key begins. By default this is an index into the {@link #getContents()}
     * array. If the {@link #getExpandedPublicKey()} is provided, then this is an index in the
     * {@link #getExpandedPublicKey()} array.
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
     * Returns a {@link Future} which will be completed once this signature has been verified.
     *
     * @return a future linked to the signature verification state
     */
    public synchronized Future<Void> getFuture() {
        return future;
    }

    /**
     * Internal use only setter for assigning or updating the {@link Future} attached to this signature.
     *
     * @param future the future to be linked to this signature
     */
    public synchronized void setFuture(final Future<Void> future) {
        this.future = future;
        notifyAll();
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
     * Returns a {@link Future} which will be completed once this signature has been verified.
     * <p>
     * NOTE: This method will wait indefinitely for the future to become available
     * </p>
     *
     * @return a future linked to the signature verification state
     * @throws InterruptedException if any thread interrupted the current thread before or while the current thread was
     *                              waiting
     */
    public synchronized Future<Void> waitForFuture() throws InterruptedException {
        // Block until future becomes available
        while (future == null) {
            wait();
        }

        return future;
    }

    /**
     * Convenience method for serializing this signature to a {@link DataOutputStream}. Utilizes the
     * {@link #serialize(TransactionSignature, DataOutputStream, int[])} method directly.
     *
     * @param dos       the {@link DataOutputStream} to which the binary representation should be written
     * @param byteCount returns the number of bytes written as the first element in the array or increments the existing
     *                  value by the number of bytes written
     * @return the cumulative number of bytes written to the output stream
     * @throws IOException          if any error occurs while writing to the {@link DataOutputStream}
     * @throws NullPointerException if the {@code dos} parameter is null
     */
    public int writeTo(final DataOutputStream dos, int[] byteCount) throws IOException {
        if (byteCount == null || byteCount.length == 0) {
            byteCount = new int[1];
        }

        serialize(this, dos, byteCount);

        return byteCount[0];
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
                .append("expandedPublicKey", Arrays.toString(expandedPublicKey))
                .append("messageOffset", messageOffset)
                .append("messageLength", messageLength)
                .append("publicKeyOffset", publicKeyOffset)
                .append("publicKeyLength", publicKeyLength)
                .append("signatureOffset", signatureOffset)
                .append("signatureLength", signatureLength)
                .append("signatureType", signatureType)
                .append("signatureStatus", signatureStatus)
                .append("future", future)
                .toString();
    }
}
