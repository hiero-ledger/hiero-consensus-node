// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.utility;

import static com.hedera.pbj.runtime.Codec.DEFAULT_MAX_DEPTH;
import static org.hiero.base.io.streams.SerializableStreamConstants.NULL_CLASS_ID;
import static org.hiero.base.io.streams.SerializableStreamConstants.NULL_INSTANT_EPOCH_SECOND;
import static org.hiero.base.io.streams.SerializableStreamConstants.NULL_LIST_ARRAY_LENGTH;
import static org.hiero.base.io.streams.SerializableStreamConstants.NULL_VERSION;

import com.hedera.pbj.runtime.Codec;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.PbjReader;
import com.hedera.pbj.runtime.io.PbjWriter;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.BufferOverflowException;
import java.nio.BufferUnderflowException;
import java.time.Instant;
import java.util.function.Supplier;
import org.hiero.base.io.FunctionalDeserializePbj;
import org.hiero.base.io.FunctionalSerializePbj;
import org.hiero.base.io.SelfSerializable;
import org.hiero.base.io.SerializableDet;
import org.hiero.base.io.exceptions.InvalidVersionException;
import org.hiero.base.io.streams.SerializableStreamConstants;

public final class PbjUtils {
    static class ReadCacheBytes {
        final PbjReader reader = new PbjReader(Bytes.EMPTY);
        boolean inUse = false;
    }

    static class ReadCacheStream {
        final PbjReader reader = new PbjReader((InputStream) null);
        boolean inUse = false;
    }

    static class WriteCache {
        final PbjWriter writer = new PbjWriter();
        boolean inUse = false;
    }

    static ThreadLocal<ReadCacheBytes> tlsReaderBytes = ThreadLocal.withInitial(ReadCacheBytes::new);
    static ThreadLocal<ReadCacheStream> tlsReaderStream = ThreadLocal.withInitial(ReadCacheStream::new);
    static ThreadLocal<WriteCache> tlsWriter = ThreadLocal.withInitial(WriteCache::new);

    public static PbjReader takeTlsReaderBytes() {
        ReadCacheBytes cache = tlsReaderBytes.get();
        if (cache.inUse) {
            throw new RuntimeException("Trying to get tls byte reader more than once");
        }
        cache.inUse = true;
        return cache.reader;
    }

    public static void returnTlsReaderBytes() {
        ReadCacheBytes cache = tlsReaderBytes.get();
        if (!cache.inUse) {
            throw new RuntimeException("Trying to return tls byte reader more than once");
        }
        cache.inUse = false;
    }

    public static PbjReader takeTlsReaderStream() {
        ReadCacheStream cache = tlsReaderStream.get();
        if (cache.inUse) {
            throw new RuntimeException("Trying to get tls stream reader more than once");
        }
        cache.inUse = true;
        return cache.reader;
    }

    public static void returnTlsReaderStream() {
        ReadCacheStream cache = tlsReaderStream.get();
        if (!cache.inUse) {
            throw new RuntimeException("Trying to return tls stream reader more than once");
        }
        cache.inUse = false;
    }

    public static PbjWriter takeTlsWriter() {
        WriteCache cache = tlsWriter.get();
        if (cache.inUse) {
            throw new RuntimeException("Trying to get tls writer more than once");
        }
        cache.inUse = true;
        return cache.writer;
    }

    public static void returnTlsWriter() {
        WriteCache cache = tlsWriter.get();
        if (!cache.inUse) {
            throw new RuntimeException("Trying to return tls writer more than once");
        }
        cache.inUse = false;
        cache.writer.resetWithNull(); // clear stream
        cache.writer.throwOnError();
    }

    public static void writeNormalisedString(@NonNull final PbjWriter out, @Nullable final String s)
            throws IOException {
        writeByteArray(out, CommonUtils.getNormalisedStringBytes(s));
    }

    public static String readNormalisedString(PbjReader in, int maxLength) throws IOException {
        byte[] data = readByteArray(in, maxLength, false);
        if (data == null) {
            return null;
        }
        return CommonUtils.getNormalisedStringFromBytes(data);
    }

    public static void writeByteArray(@NonNull final PbjWriter out, @Nullable final byte[] data) {
        writeByteArray(out, data, false);
    }

    public static void writeByteArray(
            @NonNull final PbjWriter out, @Nullable final byte[] data, final boolean writeChecksum) {
        if (data == null) {
            out.writeInt(NULL_LIST_ARRAY_LENGTH);
            return;
        }
        out.writeInt(data.length);
        if (writeChecksum) {
            // write a simple checksum to detect if at wrong place in the stream
            out.writeInt(101 - data.length);
        }
        out.writeBytes(data);
    }

    private static final int MAX_PBJ_RECORD_SIZE = 33554432;

    public static <T> T readPbjRecord(PbjReader in, @NonNull final Codec<T> codec) throws IOException {
        int size = in.readInt();
        final long origLimit = in.limit();
        in.limit(in.position() + size);
        try {
            // parse strictly with a default depth and max record size to prevent very large messages.
            // We can't use `parseStrict` as it doesn't support record size validation.
            final T parsed = codec.parse(in, true, false, DEFAULT_MAX_DEPTH, MAX_PBJ_RECORD_SIZE);
            if (in.position() != in.limit()) {
                throw new EOFException("PBJ record was not fully read");
            }
            return parsed;
        } catch (final ParseException e) {
            if (e.getCause() instanceof BufferOverflowException || e.getCause() instanceof BufferUnderflowException) {
                // PBJ Codec can throw these exceptions if it does not read enough bytes
                final EOFException eofException = new EOFException("Buffer underflow while reading PBJ record");
                eofException.addSuppressed(e);
                throw eofException;
            }
            throw new IOException(e);
        }
    }

    public static void writeSerializable(
            PbjWriter out, @NonNull final SelfSerializable serializable, final boolean writeClassId)
            throws IOException {
        writeSerializable(out, serializable, writeClassId, serializable);
    }

    public static int readVarInt(Bytes bytes, boolean zigZag) {
        return (int) readVarLong(bytes, zigZag);
    }

    public static long readVarLong(Bytes bytes, boolean zigZag) {
        byte[] buf = bytes.array();
        int arrayOffset = bytes.arrayOffset();
        long value = 0;
        for (int i = 0; i < 10; i++) {
            byte b = buf[arrayOffset + i];
            value |= (long) (b & 0x7F) << (i * 7);
            if (b >= 0) {
                return zigZag ? (value >>> 1) ^ -(value & 1) : value;
            }
        }
        return -1;
    }

    public static long readLong(Bytes bytes) {
        byte[] buf = bytes.array();
        int pos = bytes.arrayOffset();
        if (bytes.length() < 8) return -1;

        long v = 0;
        for (int i = 0; i < 8; i++) {
            v |= (long) (buf[pos + 7 - i] & 255) << (i * 8);
        }
        pos += 8;
        return v;
    }

    private static void writeSerializable(
            PbjWriter out,
            @Nullable final SelfSerializable serializable,
            final boolean writeClassId,
            @NonNull final FunctionalSerializePbj serializeMethod)
            throws IOException {
        if (serializable == null) {
            if (writeClassId) {
                out.writeLong(NULL_CLASS_ID);
            } else {
                out.writeInt(NULL_VERSION);
            }
            return;
        }
        writeClassIdVersion(out, serializable, writeClassId);
        serializeMethod.serialize(out);
    }

    static void writeClassIdVersion(
            PbjWriter out, @NonNull final SerializableDet serializable, final boolean writeClassId) throws IOException {
        if (writeClassId) {
            out.writeLong(serializable.getClassId());
        }
        out.writeInt(serializable.getVersion());
    }

    @Nullable
    public static <T extends SelfSerializable & FunctionalDeserializePbj> T readSerializable(
            @NonNull final PbjReader in, final boolean readClassId, @NonNull final Supplier<T> serializableConstructor)
            throws IOException {
        if (readClassId) {
            if (in.readLong() == NULL_CLASS_ID) return null;
        }
        final int version = in.readInt();
        if (version == NULL_VERSION) return null;
        final T obj = serializableConstructor.get();
        if (version < obj.getMinimumSupportedVersion() || version > obj.getVersion()) {
            throw new InvalidVersionException(version, obj);
        }
        obj.deserialize(in, version);
        return obj;
    }

    /**
     * Writes a length-prefixed PBJ record to the given {@link PbjWriter}.
     *
     * @param out the destination writer
     * @param record the record to write
     * @param codec the codec to use to write the record
     * @param <T> the type of the record
     * @return the total number of bytes written (record size + 4-byte length prefix)
     * @throws IOException if an IO error occurs
     */
    public static <T> long writePbjRecord(
            @NonNull final PbjWriter out, @NonNull final T record, @NonNull final Codec<T> codec) throws IOException {
        final int recordSize = codec.measureRecord(record);
        out.writeInt(recordSize);
        codec.write(record, out);
        out.flush();
        return recordSize + Integer.BYTES;
    }

    public static void writeInstant(@NonNull PbjWriter out, @Nullable final Instant instant) throws IOException {
        if (instant == null) {
            out.writeLong(NULL_INSTANT_EPOCH_SECOND);
            return;
        }
        out.writeLong(instant.getEpochSecond());
        out.writeLong(instant.getNano());
    }

    @Nullable
    public static Instant readInstant(@NonNull PbjReader in) throws IOException {
        long epochSecond = in.readLong(); // from getEpochSecond()
        if (epochSecond == NULL_INSTANT_EPOCH_SECOND) {
            return null;
        }
        long nanos = in.readLong();
        if (nanos < 0 || nanos > 999_999_999) {
            throw new IOException("Instant.nanosecond is not within the allowed range!");
        }
        return Instant.ofEpochSecond(epochSecond, nanos);
    }

    public static byte[] readByteArray(@NonNull final PbjReader in, final int maxLength) throws IOException {
        return readByteArray(in, maxLength, SerializableStreamConstants.DEFAULT_CHECKSUM);
    }

    public static byte[] readByteArray(@NonNull final PbjReader in, final int maxLength, final boolean readChecksum)
            throws IOException {
        int len = in.readInt();
        if (len < 0) {
            return null;
        }
        if (readChecksum) {
            int checksum = in.readInt();
            if (checksum != (101 - len)) {
                throw new IOException("readByteArray tried to create array of length " + len + " with wrong checksum.");
            }
        }
        if (len > maxLength) {
            throw new IOException(
                    "readByteArray tried to create array of length " + len + " but max allowed is " + maxLength + ".");
        }
        byte[] dst = new byte[len];
        in.readBytes(dst);
        if (in.error() > 0) {
            throw new IOException("readByteArray: unexpected end of stream reading " + len + " bytes.");
        }
        return dst;
    }
}
