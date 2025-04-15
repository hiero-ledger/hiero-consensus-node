// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.crypto;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Objects;
import java.util.concurrent.Future;

/**
 * Encapsulates an array of bytes to be hashed using a cryptographic message digest. Overloaded constructors are provided
 * to allow a given range of the array to be hashed instead of the entire array.
 */
public class Message implements Comparable<Message> {

    /** the contents to be hashed (or a range thereof) */
    private byte[] payload;

    /** the offset from which to begin hashing */
    private int offset;

    /** the number of bytes from offset to hash */
    private int length;

    /** the final hash */
    private Hash hash;

    /** the digest algorithm requested */
    private DigestType digestType;

    /** the future object indicating completion of the hash operation */
    private Future<Void> future;

    /**
     * Creates a message instance where the entire array will be hashed.
     *
     * @param payload
     * 		the data to be hashed
     * @throws NullPointerException
     * 		if the {@code payload} parameter is null
     */
    public Message(final byte[] payload) {
        this(payload, 0, (payload != null) ? payload.length : 0, DigestType.SHA_384);
    }

    /**
     * Creates a message instance where the entire array will be hashed.
     *
     * @param payload
     * 		the data to be hashed
     * @param digestType
     * 		the digest algorithm to be used
     * @throws NullPointerException
     * 		if the {@code payload} parameter is null
     */
    public Message(final byte[] payload, final DigestType digestType) {
        this(payload, 0, (payload != null) ? payload.length : 0, digestType);
    }

    /**
     * Creates a message instance where the array starting from offset will be hashed.
     *
     * @param payload
     * 		the data to be hashed
     * @param offset
     * 		the offset in the array from which to begin hashing
     * @throws NullPointerException
     * 		if the {@code payload} parameter is null
     * @throws ArrayIndexOutOfBoundsException
     * 		if the {@code offset} parameter is outside the bounds of the array
     */
    public Message(final byte[] payload, final int offset) {
        this(payload, offset, (payload != null) ? payload.length - offset : 0, DigestType.SHA_384);
    }

    /**
     * Creates a message instance where the array starting from offset will be hashed.
     *
     * @param payload
     * 		the data to be hashed
     * @param offset
     * 		the offset in the array from which to begin hashing
     * @param digestType
     * 		the digest algorithm to be used
     * @throws NullPointerException
     * 		if the {@code payload} parameter is null
     * @throws ArrayIndexOutOfBoundsException
     * 		if the {@code offset} parameter is outside the bounds of the array
     */
    public Message(final byte[] payload, final int offset, final DigestType digestType) {
        this(payload, offset, (payload != null) ? payload.length - offset : 0, digestType);
    }

    /**
     * Creates a message instance where the array starting at offset to length will be hashed.
     *
     * @param payload
     * 		the data to be hashed
     * @param offset
     * 		the offset in the array from which to begin hashing
     * @param length
     * 		the length in bytes starting from offset
     * @throws NullPointerException
     * 		if the {@code payload} parameter is null
     * @throws ArrayIndexOutOfBoundsException
     * 		if the {@code offset} parameter is outside the bounds of the array
     * @throws IllegalArgumentException
     * 		if the {@code length} parameter is less than zero or greater than
     *        {@code payload.length}
     */
    public Message(final byte[] payload, final int offset, final int length) {
        this(payload, offset, length, DigestType.SHA_384);
    }

    /**
     * Creates a message instance where the array starting at offset to length will be hashed.
     *
     * @param payload
     * 		the data to be hashed
     * @param offset
     * 		the offset in the array from which to begin hashing
     * @param length
     * 		the length in bytes starting from offset
     * @param digestType
     * 		the digest algorithm to be used
     * @throws NullPointerException
     * 		if the {@code payload} parameter is null
     * @throws ArrayIndexOutOfBoundsException
     * 		if the {@code offset} parameter is outside the bounds of the array
     * @throws IllegalArgumentException
     * 		if the {@code length} parameter is less than zero or greater than
     *        {@code payload.length}
     */
    public Message(final byte[] payload, final int offset, final int length, final DigestType digestType) {
        if (payload == null) {
            throw new NullPointerException("payload");
        }

        if (length < 0 || length > payload.length) {
            throw new IllegalArgumentException("length");
        }

        if (offset < 0 || offset > payload.length || (offset + length) > payload.length) {
            throw new ArrayIndexOutOfBoundsException("offset");
        }

        if (digestType == null) {
            throw new NullPointerException("digestType");
        }

        this.payload = payload;
        this.offset = offset;
        this.length = length;
        this.digestType = digestType;
    }

    /**
     * Returns the message payload. This method returns a copy of the original content.
     *
     * This method is thread-safe and guaranteed to be atomic in nature.
     *
     * @return the message payload
     */
    public byte[] getPayload() {
        return payload.clone();
    }

    /**
     * Internal use accessor that returns a direct (mutable) reference to the message payload. Care must be taken to
     * never modify the array returned by this accessor. Modifying the array will result in undefined behaviors and will
     * result in a violation of the immutability contract provided by the {@link Message} object.
     *
     * This method exists solely to allow direct access by the platform for performance reasons.
     *
     * @return a direct reference to the message payload
     */
    public byte[] getPayloadDirect() {
        return payload;
    }

    /**
     * Returns the offset at which the message begins.
     *
     * This method is thread-safe and guaranteed to be atomic in nature.
     *
     * @return the offset of the message
     */
    public int getOffset() {
        return offset;
    }

    /**
     * Returns the size of the message payload.
     *
     * This method is thread-safe and guaranteed to be atomic in nature.
     *
     * @return the length of the message content
     */
    public int getLength() {
        return length;
    }

    /**
     * Returns the computed hash of the message payload.
     *
     * This method is thread-safe and guaranteed to be atomic in nature.
     *
     * @return the hash of the message payload
     */
    public Hash getHash() {
        return hash;
    }

    /**
     * Internal use only setter for assigning or updating the hash attached to this message.
     *
     * @param hash
     * 		the computed hash
     */
    public void setHash(final Hash hash) {
        this.hash = hash;
    }

    /**
     * Returns a {@link Future} which will be completed once this message has been hashed.
     *
     * @return a future linked to the message hashing state
     */
    public synchronized Future<Void> getFuture() {
        return future;
    }

    /**
     * Internal use only setter for assigning or updating the {@link Future} attached to this signature.
     *
     * @param future
     * 		the future to be linked to this signature
     * @throws NullPointerException
     * 		if the {@code future} parameter is null
     */
    public synchronized void setFuture(final Future<Void> future) {
        if (future == null) {
            throw new NullPointerException("future");
        }

        this.future = future;
        this.notifyAll();
    }

    /**
     * Returns a {@link Future} which will be completed once this message has been hashed.
     * <p>
     * NOTE: This method will wait indefinitely for the future to become available
     * </p>
     *
     * @return a future linked to the message hashing state
     * @throws InterruptedException
     * 		if the thread is interrupted while waiting
     */
    public synchronized Future<Void> waitForFuture() throws InterruptedException {
        while (future == null) {
            this.wait();
        }

        return future;
    }

    /**
     * Returns the {@link DigestType} used to compute the digest for this message.
     *
     * @return the digest algorithm used
     */
    public DigestType getDigestType() {
        return digestType;
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
     * @param obj
     * 		the reference object with which to compare.
     * @return {@code true} if this object is the same as the obj argument; {@code false} otherwise.
     * @see #hashCode()
     * @see HashMap
     */
    @Override
    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof Message)) {
            return false;
        }

        Message that = (Message) obj;
        return offset == that.offset && length == that.length && Arrays.equals(payload, that.payload);
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
        int result = Objects.hash(offset, length);
        result = 31 * result + Arrays.hashCode(payload);
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
     * @param that
     * 		the object to be compared.
     * @return a negative integer, zero, or a positive integer as this object is less than, equal to, or greater than
     * 		the specified object.
     * @throws NullPointerException
     * 		if the specified object is null
     * @throws ClassCastException
     * 		if the specified object's type prevents it from being compared to this object.
     */
    @Override
    public int compareTo(final Message that) {
        if (this == that) {
            return 0;
        }

        if (that == null) {
            return 1;
        }

        int result = Integer.compare(offset, that.offset);

        if (result != 0) {
            return result;
        }

        result = Integer.compare(length, that.length);

        if (result != 0) {
            return result;
        }

        return Arrays.compare(payload, that.payload);
    }
}
