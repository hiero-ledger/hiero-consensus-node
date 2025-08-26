package org.hiero.telemetryconverter.util;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneId;

/**
 * Utility methods for telemetry conversion.
 */
public final class Utils {
    public static final String OPEN_TELEMETRY_SCHEMA_URL = "https://opentelemetry.io/schemas/1.0.0";
    private static final ZoneId UTC = ZoneId.of("UTC");

    /**
     * Create a 16 byte hash from a long value using the provided MessageDigest which should be a MD5 or other 128bit
     * hash.
     *
     * @param digest the MessageDigest to use, should be MD5 or other 128bit hash
     * @param value the long value to hash
     * @return the 16 byte hash
     */
    public static Bytes longToHash16Bytes(MessageDigest digest, long value) {
        // Convert the long value to a byte array and update the digest
        return Bytes.wrap(digest.digest(longToByteArray(value)));
    }

    public static byte[] longToByteArray(long value) {
        byte[] bytes = new byte[8];
        for (int i = 0; i < 8; i++) {
            bytes[i] = (byte) (value >>> (56 - i * 8));
        }
        return bytes;
    }

    /**
     * XXH3 64 bit hash to 8 bytes.
     *
     * @param val the value to hash
     * @return the hash as 8 bytes
     */
    public static Bytes longToHash8Bytes(long val) {
        val ^= Long.rotateLeft(val, 49) ^ Long.rotateLeft(val, 24);
        val *= 0x9FB21C651E98DF25L;
        val ^= (val >>> 35) + 8;
        val *= 0x9FB21C651E98DF25L;
        return Bytes.wrap(longToByteArray(val ^ (val >>> 28)));
    }

    /**
     * Converts an Instant to a UNIX Epoch time in nanoseconds.
     * TODO do we need to convert to UTC?
     *
     * @param instant the Instant to convert
     * @return the UNIX Epoch time in nanoseconds
     */
    public static long instantToUnixEpocNanos(Instant instant) {
        // Value is UNIX Epoch time in nanoseconds since 00:00:00 UTC on 1 January 1970.
        return instant.atZone(UTC).toEpochSecond() * 1_000_000_000L + instant.getNano();
    }

    public static Instant unixEpocNanosToInstant(long epochNanos) {
        long seconds = epochNanos / 1_000_000_000L;
        int nanos = (int) (epochNanos % 1_000_000_000L);
        return Instant.ofEpochSecond(seconds, nanos);
    }

    public static long fileCreationTimeEpocNanos(Path filePath) throws IOException {
        try {
            BasicFileAttributes attributes = Files.readAttributes(filePath, BasicFileAttributes.class);
            final FileTime fileTime = attributes.creationTime();
            return instantToUnixEpocNanos(fileTime.toInstant());
        } catch (IOException e) {
            System.err.println("Error reading file attributes: " + e.getMessage());
            throw new UncheckedIOException(e);
        }
    }
}
