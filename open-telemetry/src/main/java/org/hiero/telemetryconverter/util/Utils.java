package org.hiero.telemetryconverter.util;

import com.hedera.hapi.node.base.Timestamp;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import io.opentelemetry.pbj.common.v1.AnyValue;
import io.opentelemetry.pbj.common.v1.KeyValue;
import io.opentelemetry.pbj.trace.v1.Span;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.hiero.telemetryconverter.model.VirtualResource;
import org.hiero.telemetryconverter.model.combined.EventInfo;

/**
 * Utility methods for telemetry conversion.
 */
public final class Utils {
    private static final System.Logger LOGGER = System.getLogger(Utils.class.getName());
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
     * XXH3 64 bit hash of a long to 8 bytes.
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
     * XXH3 64 bit hash of two longs to 8 bytes.
     *
     * @param val the value to hash
     * @return the hash as 8 bytes
     */
    public static Bytes longToHash8Bytes(long val, long val2) {
        long lo = val ^ (0x1f67b3b7a4a44072L ^ 0x78e5c0cc4ee679cbL);
        long hi = val2 ^ (0x2172ffcc7dd05a82L ^ 0x8e2443f7744608b8L);
        long x = lo * hi;
        long y = Math.unsignedMultiplyHigh(lo, hi);
        long acc = 16 + Long.reverseBytes(lo) + hi + (x ^ y);
        acc ^= acc >>> 37;
        acc *= 0x165667919E3779F9L;
        long hash = acc ^ (acc >>> 32);
        return Bytes.wrap(longToByteArray(hash));
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

    /**
     * Converts a Timestamp to a UNIX Epoch time in nanoseconds.
     *
     * @param instant the Timestamp to convert
     * @return the UNIX Epoch time in nanoseconds
     */
    public static long timestampToUnixEpocNanos(Timestamp instant) {
        // Value is UNIX Epoch time in nanoseconds since 00:00:00 UTC on 1 January 1970.
        return instant.seconds() * 1_000_000_000L + instant.nanos();
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

    public static void putSpan(Map<VirtualResource, List<Span>> spanMap, VirtualResource resource, Span span) {
        // validate the spans time range
        final Span editedSpan;
        if (span.startTimeUnixNano() >= span.endTimeUnixNano()) {
            LOGGER.log(Level.WARNING, () ->
                    "Invalid span time range, start time must be before end time: " + span);
            // TODO hack for now
            editedSpan = span.copyBuilder()
                    .endTimeUnixNano(span.startTimeUnixNano() + 1)
                    .build();
        } else {
            editedSpan = span;
        }
        spanMap.computeIfAbsent(resource, k -> new ArrayList<>()).add(editedSpan);
    }

    public static KeyValue kv(String key, String value) {
        return KeyValue.newBuilder().key(key).value(AnyValue.newBuilder().stringValue(value).build()).build();
    }

    public static KeyValue kv(String key, long value) {
        return KeyValue.newBuilder().key(key).value(AnyValue.newBuilder().intValue(value).build()).build();
    }

    /**
     * Format a duration in a human readable format with days, hours, minutes, seconds and milliseconds.
     *
     * @param duration the duration to format
     * @return the formatted duration
     */
    public static String formatDurationHumanDecimalSeconds(java.time.Duration duration) {
        long totalNanos = duration.toNanos();
        long days = totalNanos / 86_400_000_000_000L;
        totalNanos %= 86_400_000_000_000L;
        long hours = totalNanos / 3_600_000_000_000L;
        totalNanos %= 3_600_000_000_000L;
        long minutes = totalNanos / 60_000_000_000L;
        totalNanos %= 60_000_000_000L;
        long seconds = totalNanos / 1_000_000_000L;
        long nanos = totalNanos % 1_000_000_000L;

        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append("d ");
        if (hours > 0) sb.append(hours).append("h ");
        if (minutes > 0) sb.append(minutes).append("m ");
        if (seconds > 0 || nanos > 0 || sb.length() == 0) {
            double secWithFraction = seconds + nanos / 1_000_000_000.0;
            sb.append(String.format("%.5fs", secWithFraction));
        }
        return sb.toString().trim();
    }
}
