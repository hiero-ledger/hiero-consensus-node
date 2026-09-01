// SPDX-License-Identifier: Apache-2.0
package com.hedera.pbj.runtime;

import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.WritableSequentialData;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.pbj.runtime.io.stream.WritableStreamingData;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Encapsulates Serialization, Deserialization and other IO operations.
 *
 * @param <T> The type of object to serialize and deserialize
 */
public interface Codec<T> {

    /**
     * The default maximum size of a repeated or length-encoded field (Bytes, String, Message, etc.).
     * The size should not be increased beyond the current limit because of the safety concerns.
     * An application can override this limit when calling the `Codec.parse()` method for a specific
     * protobuf model type if that model is allowed to contain larger fields.
     */
    int DEFAULT_MAX_SIZE = 2 * 1024 * 1024;

    /**
     * The default maximum depth of nested messages before the `parse()` method would error out.
     * Applications can always override the maxDepth by supplying an argument to the main `Codec.parse()` method.
     * The default depth should not be increased beyond the current limit because of the safety concerns.
     */
    int DEFAULT_MAX_DEPTH = 128;

    /**
     * Parses an object from the {@link ReadableSequentialData} and returns it.
     * <p>
     * If {@code strictMode} is {@code true}, then throws an exception if fields
     * have been defined on the encoded object that are not supported by the parser. This
     * breaks forwards compatibility (an older parser cannot parse a newer encoded object),
     * which is sometimes requires to avoid parsing an object that is newer than the code
     * parsing it is prepared to handle.
     * <p>
     * The {@code maxDepth} specifies the maximum allowed depth of nested messages. The parsing
     * will fail with a ParseException if the maximum depth is reached.
     * <p>
     * The {@code maxSize} specifies a custom value for the default `Codec.DEFAULT_MAX_SIZE` limit. IMPORTANT:
     * specifying a value larger than the default one can put the application at risk because a maliciously-crafted
     * payload can cause the parser to allocate too much memory which can result in OutOfMemory and/or crashes.
     * It's important to carefully estimate the maximum size limit that a particular protobuf model type should support,
     * and then pass that value as a parameter. Note that the estimated limit should apply to the **type** as a whole,
     * rather than to individual instances of the model. In other words, this value should be a constant, or a config
     * value that is controlled by the application, rather than come from the input that the application reads.
     * When in doubt, use the other overloaded versions of this method that use the default `Codec.DEFAULT_MAX_SIZE`.
     *
     * @param input The {@link ReadableSequentialData} from which to read the data to construct an object
     * @param strictMode when {@code true}, the parser errors out on unknown fields; otherwise they'll be simply skipped.
     * @param parseUnknownFields when {@code true} and strictMode is {@code false}, the parser will collect unknown
     *                           fields in the unknownFields list in the model; otherwise they'll be simply skipped.
     * @param maxDepth a ParseException will be thrown if the depth of nested messages exceeds the maxDepth value.
     * @param maxSize a ParseException will be thrown if the size of a delimited field exceeds the limit
     * @return The parsed object. It must not return null.
     * @throws ParseException If parsing fails
     */
    @NonNull
    T parse(
            @NonNull ReadableSequentialData input,
            boolean strictMode,
            boolean parseUnknownFields,
            int maxDepth,
            int maxSize)
            throws ParseException;

    /**
     * Parses an object from the {@link ReadableSequentialData} and returns it.
     * <p>
     * If {@code strictMode} is {@code true}, then throws an exception if fields
     * have been defined on the encoded object that are not supported by the parser. This
     * breaks forwards compatibility (an older parser cannot parse a newer encoded object),
     * which is sometimes requires to avoid parsing an object that is newer than the code
     * parsing it is prepared to handle.
     * <p>
     * The {@code maxDepth} specifies the maximum allowed depth of nested messages. The parsing
     * will fail with a ParseException if the maximum depth is reached.
     * <p>
     * This default implementation uses the default limit of `Codec.DEFAULT_MAX_SIZE` for `maxSize`
     *
     * @param input The {@link ReadableSequentialData} from which to read the data to construct an object
     * @param strictMode when {@code true}, the parser errors out on unknown fields; otherwise they'll be simply skipped.
     * @param parseUnknownFields when {@code true} and strictMode is {@code false}, the parser will collect unknown
     *                           fields in the unknownFields list in the model; otherwise they'll be simply skipped.
     * @param maxDepth a ParseException will be thrown if the depth of nested messages exceeds the maxDepth value.
     * @return The parsed object. It must not return null.
     * @throws ParseException If parsing fails
     */
    @NonNull
    default T parse(@NonNull ReadableSequentialData input, boolean strictMode, boolean parseUnknownFields, int maxDepth)
            throws ParseException {
        return parse(input, strictMode, parseUnknownFields, maxDepth, DEFAULT_MAX_SIZE);
    }
    /**
     * Parses an object from the {@link ReadableSequentialData} and returns it.
     * <p>
     * If {@code strictMode} is {@code true}, then throws an exception if fields
     * have been defined on the encoded object that are not supported by the parser. This
     * breaks forwards compatibility (an older parser cannot parse a newer encoded object),
     * which is sometimes requires to avoid parsing an object that is newer than the code
     * parsing it is prepared to handle.
     * <p>
     * The {@code maxDepth} specifies the maximum allowed depth of nested messages. The parsing
     * will fail with a ParseException if the maximum depth is reached.
     *
     * @param input The {@link ReadableSequentialData} from which to read the data to construct an object
     * @param strictMode when {@code true}, the parser errors out on unknown fields; otherwise they'll be simply skipped.
     * @param maxDepth a ParseException will be thrown if the depth of nested messages exceeds the maxDepth value.
     * @return The parsed object. It must not return null.
     * @throws ParseException If parsing fails
     */
    @NonNull
    default T parse(@NonNull ReadableSequentialData input, final boolean strictMode, final int maxDepth)
            throws ParseException {
        return parse(input, strictMode, false, maxDepth);
    }

    /**
     * Parses an object from the {@link Bytes} and returns it.
     * <p>
     * If {@code strictMode} is {@code true}, then throws an exception if fields
     * have been defined on the encoded object that are not supported by the parser. This
     * breaks forwards compatibility (an older parser cannot parse a newer encoded object),
     * which is sometimes requires to avoid parsing an object that is newer than the code
     * parsing it is prepared to handle.
     * <p>
     * The {@code maxDepth} specifies the maximum allowed depth of nested messages. The parsing
     * will fail with a ParseException if the maximum depth is reached.
     *
     * @param bytes The {@link Bytes} from which to read the data to construct an object
     * @param strictMode when {@code true}, the parser errors out on unknown fields; otherwise they'll be simply skipped.
     * @param maxDepth a ParseException will be thrown if the depth of nested messages exceeds the maxDepth value.
     * @return The parsed object. It must not return null.
     * @throws ParseException If parsing fails
     */
    @NonNull
    default T parse(@NonNull Bytes bytes, final boolean strictMode, final int maxDepth) throws ParseException {
        return parse(bytes.toReadableSequentialData(), strictMode, maxDepth);
    }

    /**
     * Parses an object from the {@link ReadableSequentialData} and returns it.
     *
     * @param input The {@link ReadableSequentialData} from which to read the data to construct an object
     * @return The parsed object. It must not return null.
     * @throws ParseException If parsing fails
     */
    @NonNull
    default T parse(@NonNull ReadableSequentialData input) throws ParseException {
        return parse(input, false, DEFAULT_MAX_DEPTH);
    }

    /**
     * Parses an object from the {@link Bytes} and returns it.
     *
     * @param bytes The {@link Bytes} from which to read the data to construct an object
     * @return The parsed object. It must not return null.
     * @throws ParseException If parsing fails
     */
    @NonNull
    default T parse(@NonNull Bytes bytes) throws ParseException {
        return parse(bytes.toReadableSequentialData());
    }

    /**
     * Parses an object from the {@link ReadableSequentialData} and returns it. Throws an exception if fields
     * have been defined on the encoded object that are not supported by the parser. This
     * breaks forwards compatibility (an older parser cannot parse a newer encoded object),
     * which is sometimes requires to avoid parsing an object that is newer than the code
     * parsing it is prepared to handle.
     *
     * @param input The {@link ReadableSequentialData} from which to read the data to construct an object
     * @return The parsed object. It must not return null.
     * @throws ParseException If parsing fails
     */
    @NonNull
    default T parseStrict(@NonNull ReadableSequentialData input) throws ParseException {
        return parse(input, true, DEFAULT_MAX_DEPTH);
    }

    /**
     * Parses an object from the {@link Bytes} and returns it. Throws an exception if fields
     * have been defined on the encoded object that are not supported by the parser. This
     * breaks forwards compatibility (an older parser cannot parse a newer encoded object),
     * which is sometimes requires to avoid parsing an object that is newer than the code
     * parsing it is prepared to handle.
     *
     * @param bytes The {@link Bytes} from which to read the data to construct an object
     * @return The parsed object. It must not return null.
     * @throws ParseException If parsing fails
     */
    @NonNull
    default T parseStrict(@NonNull Bytes bytes) throws ParseException {
        return parseStrict(bytes.toReadableSequentialData());
    }

    /**
     * Writes an item to the given {@link WritableSequentialData}.
     *
     * @param item The item to write. Must not be null.
     * @param output The {@link WritableSequentialData} to write to.
     * @throws IOException If the {@link WritableSequentialData} cannot be written to.
     */
    void write(@NonNull T item, @NonNull WritableSequentialData output) throws IOException;

    /**
     * Writes an item to the given byte array, this is a performance focused method. In non-performance centric use
     * cases there are simpler methods such as {@link #toBytes(T)} or writing to a {@link WritableStreamingData}.
     *
     * @param item The item to write. Must not be null.
     * @param output The byte array to write to, this must be large enough to hold the entire item.
     * @param startOffset The offset in the output array to start writing at.
     * @return The number of bytes written to the output array.
     * @throws UncheckedIOException If the there is a problem writing to the output array.
     * @throws IndexOutOfBoundsException If the output array is not large enough to hold the entire item.
     */
    default int write(@NonNull T item, @NonNull byte[] output, final int startOffset) {
        final BufferedData bufferedData = BufferedData.wrap(output, startOffset, output.length - startOffset);
        try {
            write(item, bufferedData);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return (int) bufferedData.position();
    }

    /**
     * Reads from this data input the length of the data within the input. The implementation may
     * read all the data, or just some special serialized data, as needed to find out the length of
     * the data.
     *
     * @param input The input to use
     * @return The length of the data item in the input
     * @throws ParseException If parsing fails
     */
    int measure(@NonNull ReadableSequentialData input) throws ParseException;

    /**
     * Compute number of bytes that would be written when calling {@code write()} method.
     *
     * @param item The input model data to measure write bytes for
     * @return The length in bytes that would be written
     */
    int measureRecord(T item);

    /**
     * Compares the given item with the bytes in the input, and returns false if it determines that
     * the bytes in the input could not be equal to the given item. Sometimes we need to compare an
     * item in memory with serialized bytes and don't want to incur the cost of deserializing the
     * entire object, when we could have determined the bytes do not represent the same object very
     * cheaply and quickly.
     *
     * @param item The item to compare. Cannot be null.
     * @param input The input with the bytes to compare
     * @return true if the bytes represent the item, false otherwise.
     * @throws ParseException If parsing fails
     */
    boolean fastEquals(@NonNull T item, @NonNull ReadableSequentialData input) throws ParseException;

    /**
     * Converts a Record into a Bytes object
     *
     * @param item The input model data to convert into a Bytes object.
     * @return The new Bytes object.
     * @throws RuntimeException wrapping an IOException If it is impossible
     * to write to the {@link WritableStreamingData}
     */
    default Bytes toBytes(@NonNull T item) {
        // it is cheaper performance wise to measure the size of the object first than grow a buffer as needed
        final byte[] bytes = new byte[measureRecord(item)];
        final BufferedData bufferedData = BufferedData.wrap(bytes);
        try {
            write(item, bufferedData);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return Bytes.wrap(bytes);
    }

    /**
     * Get the default value for the model class.
     *
     * @return The default value for the model class
     */
    T getDefaultInstance();
}
