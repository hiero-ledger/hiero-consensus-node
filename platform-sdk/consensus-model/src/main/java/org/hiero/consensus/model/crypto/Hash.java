// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.crypto;

import static java.util.Objects.requireNonNull;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.Serializable;
import java.util.Objects;
import org.hiero.base.io.SerializableWithKnownLength;
import org.hiero.base.io.exceptions.BadIOException;
import org.hiero.base.io.streams.SerializableDataInputStream;
import org.hiero.base.io.streams.SerializableDataOutputStream;
import org.hiero.base.utility.CommonUtils;

/**
 * A cryptographic hash of some data.
 */
public class Hash implements Comparable<Hash>, SerializableWithKnownLength, Serializable {
    public static final long CLASS_ID = 0xf422da83a251741eL;
    public static final int CLASS_VERSION = 1;

    private Bytes bytes;
    private DigestType digestType;

    /**
     * Zero arg constructor. Creates a hash without any data using the default digest type ({@link DigestType#SHA_384}).
     */
    public Hash() {
        this(DigestType.SHA_384);
    }

    /**
     * Same as {@link #Hash(Bytes, DigestType)} but with an empty byte array.
     */
    public Hash(@NonNull final DigestType digestType) {
        this(Bytes.wrap(new byte[digestType.digestLength()]), digestType);
    }

    /**
     * Same as {@link #Hash(Bytes, DigestType)} but with a digest type ({@link DigestType#SHA_384}) and wrapping the byte array.
     */
    public Hash(@NonNull final byte[] value) {
        this(Bytes.wrap(value), DigestType.SHA_384);
    }

    /**
     * Same as {@link #Hash(Bytes, DigestType)} but with a digest type ({@link DigestType#SHA_384})
     */
    public Hash(@NonNull final Bytes value) {
        this(value, DigestType.SHA_384);
    }

    /**
     * Same as {@link #Hash(Bytes, DigestType)} but with wrapping the byte array.
     */
    public Hash(@NonNull final byte[] value, @NonNull final DigestType digestType) {
        this(Bytes.wrap(value), digestType);
    }

    /**
     * Instantiate a hash with data from a byte array with a specific digest type. This constructor assumes that the
     * array provided will not be modified after this call.
     *
     * @param value
     * 		the hash bytes
     * @param digestType
     * 		the digest type
     */
    public Hash(@NonNull final Bytes value, @NonNull final DigestType digestType) {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(digestType, "digestType");

        if ((int) value.length() != digestType.digestLength()) {
            throw new IllegalArgumentException("value: " + value.length());
        }

        this.digestType = digestType;
        this.bytes = value;
    }

    /**
     * Create a hash by copying data from another hash.
     *
     * @param other
     * 		the hash to copy
     */
    public Hash(@NonNull final Hash other) {
        if (other == null) {
            throw new IllegalArgumentException("other");
        }

        this.digestType = other.digestType;
        this.bytes = other.bytes;
    }

    /**
     * @return a copy of the hash value as a byte array
     */
    public @NonNull byte[] copyToByteArray() {
        return bytes.toByteArray();
    }

    /**
     * @return the hash value as an immutable {@link Bytes} object
     */
    public @NonNull Bytes getBytes() {
        return bytes;
    }

    /**
     * Get a deep copy of this hash.
     *
     * @return a new hash
     */
    public @NonNull Hash copy() {
        return new Hash(this);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getClassId() {
        return CLASS_ID;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getVersion() {
        return CLASS_VERSION;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void serialize(@NonNull final SerializableDataOutputStream out) throws IOException {
        requireNonNull(digestType, "digestType");
        requireNonNull(bytes, "bytes");
        out.writeInt(digestType.id());
        out.writeInt((int) bytes.length());
        bytes.writeTo(out);
    }

    @Override
    public int getSerializedLength() {
        return Integer.BYTES // digest type
                + Integer.BYTES // length of the hash
                + (int) bytes.length();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void deserialize(@NonNull final SerializableDataInputStream in, final int version) throws IOException {
        final DigestType digestType = DigestType.valueOf(in.readInt());

        if (digestType == null) {
            throw new BadIOException("Invalid DigestType identifier read from the stream");
        }

        this.digestType = digestType;
        final byte[] value = in.readByteArray(digestType.digestLength());

        if (value == null) {
            throw new BadIOException("Invalid hash value read from the stream");
        }
        this.bytes = Bytes.wrap(value);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        }

        if (!(obj instanceof final Hash that)) {
            return false;
        }

        return digestType.id() == that.digestType.id() && Objects.equals(bytes, that.bytes);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        // A few notes about this implementation:
        // - it is very important that this method has very low overhead, some usages are very performance sensitive
        // - since this is a cryptographic hash, it is safe to use the first 4 bytes of the hash as the hash code.
        //   it fulfills the requirements of hashCode, it is consistent with equals, and it is very unlikely to conflict
        //   with other hash codes. by using the first 4 bytes, we can avoid unnecessary overhead
        // - it does not make a difference if the bytes are big endian or little endian, as long as they are consistent.
        //   this is why we don't specify the order when calling getInt(), we use whatever order is the default to avoid
        //   the overhead of reordering the bytes
        return bytes.getInt(0);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int compareTo(@NonNull final Hash that) {
        if (this == that) {
            return 0;
        }

        if (that == null) {
            throw new NullPointerException("that");
        }

        final int ret = Integer.compare(digestType.id(), that.digestType.id());

        if (ret != 0) {
            return ret;
        }

        return Bytes.SORT_BY_SIGNED_VALUE.compare(bytes, that.bytes);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NonNull String toString() {
        return (bytes == null) ? "null" : bytes.toHex();
    }

    /**
     * Create a hexadecimal string representation of this hash.
     */
    public @NonNull String toHex() {
        return toString();
    }

    /**
     * Create a short string representation of this hash.
     *
     * @param length
     * 		the number of characters to include in the short string
     */
    public @NonNull String toHex(final int length) {
        return (bytes == null) ? "null" : CommonUtils.hex(bytes, length);
    }

    /**
     * Get the digest type of this hash.
     *
     * @return the digest type
     */
    public @NonNull DigestType getDigestType() {
        return this.digestType;
    }
}
