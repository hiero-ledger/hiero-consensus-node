/*
 * Copyright (C) 2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hedera.node.app.util;

import static com.hedera.pbj.runtime.ProtoParserTools.TAG_FIELD_OFFSET;

import com.hedera.pbj.runtime.FieldDefinition;
import com.hedera.pbj.runtime.FieldType;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.ProtoConstants;
import com.hedera.pbj.runtime.ProtoParserTools;
import com.hedera.pbj.runtime.ProtoWriterTools;
import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.util.ArrayList;
import java.util.List;

public class ProtobufUtils {

    private static final FieldDefinition TRANSACTION_BODY_FIELD =
            new FieldDefinition("body", FieldType.MESSAGE, false, 1);

    private static final FieldDefinition ATOMIC_BATCH_TRANSACTION_BODY_FIELD =
            new FieldDefinition("atomicBatch", FieldType.MESSAGE, false, false, true, 74);
    private static final FieldDefinition INNER_TRANSACTIONS_FIELD =
            new FieldDefinition("transactions", FieldType.MESSAGE, true, 1);

    private static final FieldDefinition QUERY_HEADER_FIELD =
            new FieldDefinition("header", FieldType.MESSAGE, false, 1);
    private static final FieldDefinition PAYMENT_FIELD = new FieldDefinition("payment", FieldType.MESSAGE, false, 1);

    @NonNull
    public static Bytes extractBodyBytes(@NonNull final Bytes serializedTransaction)
            throws IOException, ParseException {
        return extractFieldBytes(serializedTransaction.toReadableSequentialData(), TRANSACTION_BODY_FIELD);
    }

    @NonNull
    public static List<Bytes> extractInnerTransactionBytes(@NonNull final Bytes serializedAtomicBatchTxn)
            throws IOException, ParseException {
        final var atomicTransactionBody = extractFieldBytes(
                serializedAtomicBatchTxn.toReadableSequentialData(), ATOMIC_BATCH_TRANSACTION_BODY_FIELD);
        return extractRepeatedFieldBytes(atomicTransactionBody.toReadableSequentialData(), INNER_TRANSACTIONS_FIELD);
    }

    @NonNull
    public static Bytes extractPaymentBytes(@NonNull final Bytes serializedQuery) throws IOException, ParseException {
        final var queryBody = extractFirstFieldBytes(serializedQuery.toReadableSequentialData());
        final var queryHeader = extractFieldBytes(queryBody.toReadableSequentialData(), QUERY_HEADER_FIELD);
        return extractFieldBytes(queryHeader.toReadableSequentialData(), PAYMENT_FIELD);
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
    private static List<Bytes> extractRepeatedFieldBytes(
            @NonNull final ReadableSequentialData input, @NonNull final FieldDefinition field)
            throws IOException, ParseException {
        if (!field.repeated()) {
            throw new IllegalArgumentException("Cannot extract field bytes for a repeated field: " + field);
        }
        if (ProtoWriterTools.wireType(field) != ProtoConstants.WIRE_TYPE_DELIMITED) {
            throw new IllegalArgumentException("Cannot extract field bytes for a non-length-delimited field: " + field);
        }
        final var result = new ArrayList<Bytes>();
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
                result.add(input.readBytes(length));
            } else {
                ProtoParserTools.skipField(input, wireType);
            }
        }
        return result;
    }

    @NonNull
    private static Bytes extractFirstFieldBytes(@NonNull final ReadableSequentialData input) throws ParseException {
        if (input.hasRemaining()) {
            final int tag;
            try {
                tag = input.readVarInt(false);
            } catch (final BufferUnderflowException e) {
                // No more fields
                return Bytes.EMPTY;
            }
            final ProtoConstants wireType = ProtoConstants.get(tag & ProtoConstants.TAG_WIRE_TYPE_MASK);
            if (wireType != ProtoConstants.WIRE_TYPE_DELIMITED) {
                throw new ParseException("Unexpected wire type: " + tag);
            }
            final int length = input.readVarInt(false);
            return input.readBytes(length);
        }
        return Bytes.EMPTY;
    }
}
