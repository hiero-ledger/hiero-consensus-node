// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.crypto;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.logging.legacy.LogMarker;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Objects;

public class HashBuilder {

    private MessageDigest digest;
    private DigestType digestType;

    public HashBuilder(final MessageDigest digest) {
        if (digest == null) {
            throw new IllegalArgumentException("digest");
        }

        this.digest = digest;
        detectDigestType();
    }

    public HashBuilder(final DigestType digestType) {
        if (digestType == null) {
            throw new IllegalArgumentException("digestType");
        }

        this.digestType = digestType;
        initializeDigest();
    }

    /**
     * hash the given long
     *
     * @param n
     * 		the long value to be hashed
     * @return the HashBuilder object after digesting this long number
     */
    public HashBuilder update(long n) {
        for (int i = 0; i < Long.BYTES; i++) {
            digest.update((byte) (n & 0xFF));
            n >>= Byte.SIZE;
        }

        return this;
    }

    /**
     * hash the given int
     *
     * @param n
     * 		the int value to be hashed
     * @return the HashBuilder object after digesting this int number
     */
    public HashBuilder update(int n) {
        for (int i = 0; i < Integer.BYTES; i++) {
            digest.update((byte) (n & 0xFF));
            n >>= Byte.SIZE;
        }

        return this;
    }

    /**
     * hash the given array of bytes
     *
     * @param t
     * 		the array of bytes
     * @param offset
     * 		the offset to start from in the array of bytes
     * @param length
     * 		the number of bytes to use, starting at {@code offset}
     * @return the HashBuilder object after digesting this int number
     */
    public HashBuilder update(byte[] t, int offset, int length) {
        if (t == null) {
            update(0);
        } else {
            update(length);
            digest.update(t, offset, length);
        }

        return this;
    }

    /**
     * hash the given Instant
     *
     * @param i
     * 		the Instant object to be hashed
     * @return the HashBuilder object after digesting this Instant object
     */
    public HashBuilder update(Instant i) {
        // the instant class consists of only 2 parts, the seconds and the nanoseconds
        update(i.getEpochSecond());
        update(i.getNano());

        return this;
    }

    /**
     * hash the given array of byte arrays
     *
     * @param t
     * 		the array of byte arrays to be hashed
     * @return the HashBuilder object after digesting this array
     */
    public HashBuilder update(byte[][] t) {
        if (t == null) {
            update(0);
        } else {
            update(t.length);
            for (byte[] a : t) {
                if (a == null) {
                    update(0);
                } else {
                    update(a.length);
                    digest.update(a);
                }
            }
        }

        return this;
    }

    /**
     * hash the given array of bytes, including its length
     *
     * @param t
     * 		the byte arrays to be hashed
     * @return the HashBuilder object after digesting this array
     */
    public HashBuilder update(byte[] t) {
        if (t == null) {
            update(0);
        } else {
            update(t.length);
            digest.update(t);
        }

        return this;
    }

    /**
     * hash the given Bytes object, including its length
     *
     * @param bytes
     *      the Bytes object to be hashed
     * @return the HashBuilder object after digesting this Bytes object
     */
    public HashBuilder update(final Bytes bytes) {
        if (bytes == null) {
            update(0);
        } else {
            update(bytes.length());
            bytes.writeTo(digest);
        }
        return this;
    }

    /**
     * Temporary method until we change how we hash all objects
     *
     * @param hash
     * 		the Hash object to be hashed
     * @return the HashBuilder object after digesting this Hash object
     */
    public HashBuilder update(final Hash hash) {
        if (hash == null) {
            throw new IllegalArgumentException("hash");
        }

        hash.getBytes().writeTo(digest);

        return this;
    }

    /**
     * hash the given array of bytes, not including its length
     *
     * @param data
     * 		the byte arrays to be hashed
     * @return the HashBuilder object after digesting this array
     */
    public HashBuilder updateRaw(final byte[] data) {
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("data");
        }

        digest.update(data);
        return this;
    }

    /**
     * resets the digest
     *
     * @return the HashBuilder object after the digest is reset
     */
    public HashBuilder reset() {
        digest.reset();

        return this;
    }

    /**
     * @return a immutable Hash
     */
    public Hash build() {
        return new Hash(digest.digest(), digestType);
    }

    /**
     * initializes the digest object
     */
    private void initializeDigest() {
        try {
            digest = MessageDigest.getInstance(digestType.algorithmName());
        } catch (NoSuchAlgorithmException ex) {
            throw new CryptographyException(ex, LogMarker.EXCEPTION);
        }
    }

    /**
     * checks whether the digestType is valid
     */
    private void detectDigestType() {
        for (DigestType t : DigestType.values()) {
            if (Objects.equals(t.algorithmName(), digest.getAlgorithm())) {
                digestType = t;
                break;
            }
        }

        if (digestType == null) {
            throw new InvalidDigestTypeException(digest.getAlgorithm());
        }
    }
}
