// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.app.model.codec;

import static com.hedera.pbj.runtime.ProtoConstants.TAG_WIRE_TYPE_MASK;
import static com.hedera.pbj.runtime.ProtoParserTools.TAG_FIELD_OFFSET;
import static com.hedera.pbj.runtime.ProtoParserTools.extractField;
import static com.hedera.pbj.runtime.ProtoParserTools.readUint64;
import static com.hedera.pbj.runtime.ProtoParserTools.skipField;
import static com.hedera.pbj.runtime.ProtoWriterTools.writeLong;

import com.hedera.pbj.runtime.Codec;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.ProtoConstants;
import com.hedera.pbj.runtime.UnknownField;
import com.hedera.pbj.runtime.UnknownFieldException;
import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.WritableSequentialData;
import com.hedera.pbj.runtime.io.stream.EOFException;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.hiero.otter.fixtures.app.model.ConsistencyState;
import org.hiero.otter.fixtures.app.model.schema.ConsistencyStateSchema;

/**
 * Protobuf Codec for ConsistencyState model object. Generated based on protobuf schema.
 */
public final class ConsistencyStateProtoCodec implements Codec<ConsistencyState> {
    /**
     * An initial capacity for the ArrayList where unknown fields are collected.
     * To optimize parsing unknown fields, we store the max value we've seen so far.
     * The variable is prone to a slight thread-race which isn't super-critical for this value
     * because it doesn't have to be precise for correctness. It will eventually have the correct value,
     * and that's sufficient.
     */
    private static int $initialSizeOfUnknownFieldsArray = 1;

    /**
     * Empty constructor
     */
    public ConsistencyStateProtoCodec() {
        // no-op
    }

    /**
     * Parses a ConsistencyState object from ProtoBuf bytes in a {@link ReadableSequentialData}. Throws if in strict mode ONLY.
     *
     * @param input The data input to parse data from, it is assumed to be in a state ready to read with position at start
     *              of data to read and limit set at the end of data to read. The data inputs limit will be changed by this
     *              method. If there are no bytes remaining in the data input,
     *              then the method also returns immediately.
     * @param strictMode when {@code true}, the parser errors out on unknown fields; otherwise they'll be simply skipped.
     * @param parseUnknownFields when {@code true} and strictMode is {@code false}, the parser will collect unknown
     *                           fields in the unknownFields list in the model; otherwise they'll be simply skipped.
     * @param maxDepth a ParseException will be thrown if the depth of nested messages exceeds the maxDepth value.
     * @return Parsed ConsistencyState model object or null if data input was null or empty
     * @throws ParseException If parsing fails
     */
    public @NonNull ConsistencyState parse(
            @NonNull final ReadableSequentialData input,
            final boolean strictMode,
            final boolean parseUnknownFields,
            final int maxDepth)
            throws ParseException {
        if (maxDepth < 0) {
            throw new ParseException("Reached maximum allowed depth of nested messages");
        }
        try {
            // -- TEMP STATE FIELDS --------------------------------------
            long temp_running_checksum = 0;
            long temp_rounds_handled = 0;
            List<UnknownField> $unknownFields = null;

            // -- PARSE LOOP ---------------------------------------------
            // Continue to parse bytes out of the input stream until we get to the end.
            while (input.hasRemaining()) {
                // Note: ReadableStreamingData.hasRemaining() won't flip to false
                // until the end of stream is actually hit with a read operation.
                // So we catch this exception here and **only** here, because an EOFException
                // anywhere else suggests that we're processing malformed data and so
                // we must re-throw the exception then.
                final int tag;
                try {
                    // Read the "tag" byte which gives us the field number for the next field to read
                    // and the wire type (way it is encoded on the wire).
                    tag = input.readVarInt(false);
                } catch (EOFException e) {
                    // There's no more fields. Stop the parsing loop.
                    break;
                }

                // The field is the top 5 bits of the byte. Read this off
                final int field = tag >>> TAG_FIELD_OFFSET;

                // Ask the Schema to inform us what field this represents.
                final var f = ConsistencyStateSchema.getField(field);

                // Given the wire type and the field type, parse the field
                switch (tag) {
                    case 8 /* type=0 [UINT64] field=1 [running_checksum] */ -> {
                        final var value = readUint64(input);
                        temp_running_checksum = value;
                    }
                    case 16 /* type=0 [UINT64] field=2 [rounds_handled] */ -> {
                        final var value = readUint64(input);
                        temp_rounds_handled = value;
                    }

                    default -> {
                        // The wire type is the bottom 3 bits of the byte. Read that off
                        final int wireType = tag & TAG_WIRE_TYPE_MASK;
                        // handle error cases here, so we do not do if statements in normal loop
                        // Validate the field number is valid (must be > 0)
                        if (field == 0) {
                            throw new IOException("Bad protobuf encoding. We read a field value of " + field);
                        }
                        // Validate the wire type is valid (must be >=0 && <= 5).
                        // Otherwise we cannot parse this.
                        // Note: it is always >= 0 at this point (see code above where it is defined).
                        if (wireType > 5) {
                            throw new IOException("Cannot understand wire_type of " + wireType);
                        }
                        // It may be that the parser subclass doesn't know about this field
                        if (f == null) {
                            if (strictMode) {
                                // Since we are parsing is strict mode, this is an exceptional condition.
                                throw new UnknownFieldException(field);
                            } else if (parseUnknownFields) {
                                if ($unknownFields == null) {
                                    $unknownFields = new ArrayList<>($initialSizeOfUnknownFieldsArray);
                                }
                                $unknownFields.add(new UnknownField(
                                        field,
                                        ProtoConstants.get(wireType),
                                        extractField(input, ProtoConstants.get(wireType), 2097152)));
                            } else {
                                // We just need to read off the bytes for this field to skip it
                                // and move on to the next one.
                                skipField(input, ProtoConstants.get(wireType), 2097152);
                            }
                        } else {
                            throw new IOException(
                                    "Bad tag [" + tag + "], field [" + field + "] wireType [" + wireType + "]");
                        }
                    }
                }
            }

            if ($unknownFields != null) {
                Collections.sort($unknownFields);
                $initialSizeOfUnknownFieldsArray = Math.max($initialSizeOfUnknownFieldsArray, $unknownFields.size());
            }
            return new ConsistencyState(temp_running_checksum, temp_rounds_handled, $unknownFields);
        } catch (final Exception anyException) {
            if (anyException instanceof ParseException parseException) {
                throw parseException;
            }
            throw new ParseException(anyException);
        }
    }

    /**
     * Write out a ConsistencyState model to output stream in protobuf format.
     *
     * @param data The input model data to write
     * @param out The output stream to write to
     * @throws IOException If there is a problem writing
     */
    public void write(@NonNull ConsistencyState data, @NonNull final WritableSequentialData out) throws IOException {
        // [1] - running_checksum
        writeLong(out, ConsistencyStateSchema.RUNNING_CHECKSUM, data.runningChecksum(), true);
        // [2] - rounds_handled
        writeLong(out, ConsistencyStateSchema.ROUNDS_HANDLED, data.roundsHandled(), true);

        // Check if not-empty to avoid creating a lambda if there's nothing to write.
        if (!data.getUnknownFields().isEmpty()) {
            data.getUnknownFields().forEach(uf -> {
                final int tag = (uf.field() << TAG_FIELD_OFFSET) | uf.wireType().ordinal();
                out.writeVarInt(tag, false);
                uf.bytes().writeTo(out);
            });
        }
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
    public int measure(@NonNull final ReadableSequentialData input) throws ParseException {
        final var start = input.position();
        parse(input);
        final var end = input.position();
        return (int) (end - start);
    }

    /**
     * Compute number of bytes that would be written when calling {@code write()} method.
     *
     * @param data The input model data to measure write bytes for
     * @return The length in bytes that would be written
     */
    public int measureRecord(ConsistencyState data) {
        return data.protobufSize();
    }

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
    public boolean fastEquals(@NonNull ConsistencyState item, @NonNull final ReadableSequentialData input)
            throws ParseException {
        return item.equals(parse(input));
    }

    /**
     * Get the default value for the model class.
     *
     * @return The default value for the model class
     */
    @Override
    public ConsistencyState getDefaultInstance() {
        return ConsistencyState.DEFAULT;
    }
}
