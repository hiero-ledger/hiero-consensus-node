// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.util;

import static com.hedera.pbj.runtime.ProtoParserTools.TAG_FIELD_OFFSET;

import com.hedera.hapi.node.base.schema.QueryHeaderSchema;
import com.hedera.hapi.node.transaction.Query.QueryOneOfType;
import com.hedera.hapi.node.transaction.schema.TransactionGetReceiptQuerySchema;
import com.hedera.pbj.runtime.FieldDefinition;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.ProtoConstants;
import com.hedera.pbj.runtime.ProtoParserTools;
import com.hedera.pbj.runtime.ProtoWriterTools;
import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ProtobufUtils {

    private ProtobufUtils() {}

    private static final Set<Integer> QUERY_FIELDS = Stream.of(QueryOneOfType.values())
            .map(QueryOneOfType::protoOrdinal)
            .collect(Collectors.toUnmodifiableSet());

    @NonNull
    public static Bytes extractPaymentBytes(@NonNull final Bytes serializedQuery) throws IOException, ParseException {
        final var queryBody = extractQuery(serializedQuery.toReadableSequentialData());
        final var queryHeader =
                extractFieldBytes(queryBody.toReadableSequentialData(), TransactionGetReceiptQuerySchema.HEADER);
        return extractFieldBytes(queryHeader.toReadableSequentialData(), QueryHeaderSchema.PAYMENT);
    }

    @NonNull
    private static Bytes extractFieldBytes(
            @NonNull final ReadableSequentialData input, @NonNull final FieldDefinition field)
            throws IOException, ParseException {
        if (field.repeated()) {
            throw new IllegalArgumentException("Cannot extract field bytes for a repeated field: " + field);
        }
        if (ProtoWriterTools.wireType(field) != ProtoConstants.WIRE_TYPE_DELIMITED) {
            throw new IllegalArgumentException("Cannot extract field bytes for a non-length-delimited field: " + field);
        }
        while (input.hasRemaining()) {
            final int tag;
            // hasRemaining() doesn't work very well for streaming data, it returns false only when
            // the end of input is already reached using a read operation. Let's catch an underflow
            // (actually, EOF) exception here and exit cleanly. Underflow exception in any other
            // place means malformed input and should be rethrown
            try {
                tag = input.readVarInt(false);
            } catch (final BufferUnderflowException e) {
                // No more fields
                break;
            }
            final int fieldNum = tag >> TAG_FIELD_OFFSET;
            final ProtoConstants wireType = ProtoConstants.get(tag & ProtoConstants.TAG_WIRE_TYPE_MASK);
            if (fieldNum == field.number()) {
                if (wireType != ProtoConstants.WIRE_TYPE_DELIMITED) {
                    throw new ParseException("Unexpected wire type: " + tag);
                }
                final int length = input.readVarInt(false);
                return input.readBytes(length);
            } else {
                ProtoParserTools.skipField(input, wireType);
            }
        }
        throw new ParseException("Field not found: " + field);
    }

    @NonNull
    private static Bytes extractQuery(@NonNull final ReadableSequentialData input) throws IOException, ParseException {
        while (input.hasRemaining()) {
            final int tag;
            // hasRemaining() doesn't work very well for streaming data, it returns false only when
            // the end of input is already reached using a read operation. Let's catch an underflow
            // (actually, EOF) exception here and exit cleanly. Underflow exception in any other
            // place means malformed input and should be rethrown
            try {
                tag = input.readVarInt(false);
            } catch (final BufferUnderflowException e) {
                // No more fields
                break;
            }
            final int fieldNum = tag >> TAG_FIELD_OFFSET;
            final ProtoConstants wireType = ProtoConstants.get(tag & ProtoConstants.TAG_WIRE_TYPE_MASK);
            if (QUERY_FIELDS.contains(fieldNum)) {
                if (wireType != ProtoConstants.WIRE_TYPE_DELIMITED) {
                    throw new ParseException("Unexpected wire type: " + tag);
                }
                final int length = input.readVarInt(false);
                return input.readBytes(length);
            } else {
                ProtoParserTools.skipField(input, wireType);
            }
        }
        throw new ParseException("Query not found");
    }
}
