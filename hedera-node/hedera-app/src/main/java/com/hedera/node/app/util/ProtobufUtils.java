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

import com.hedera.hapi.node.base.schema.QueryHeaderSchema;
import com.hedera.hapi.node.base.schema.TransactionSchema;
import com.hedera.hapi.node.transaction.Query.QueryOneOfType;
import com.hedera.hapi.node.transaction.schema.TransactionBodySchema;
import com.hedera.hapi.node.transaction.schema.TransactionGetReceiptQuerySchema;
import com.hedera.hapi.node.util.schema.AtomicBatchTransactionBodySchema;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ProtobufUtils {

    private ProtobufUtils() {}

    private static final Set<Integer> QUERY_FIELDS = Stream.of(QueryOneOfType.values())
            .map(QueryOneOfType::protoOrdinal)
            .collect(Collectors.toUnmodifiableSet());

    @NonNull
    public static Bytes extractBodyBytes(@NonNull final Bytes serializedTransaction)
            throws IOException, ParseException {
        return extractFieldBytes(serializedTransaction.toReadableSequentialData(), TransactionSchema.BODY);
    }

    @NonNull
    public static List<Bytes> extractInnerTransactionBytes(@NonNull final Bytes serializedAtomicBatchTxn)
            throws IOException, ParseException {
        final var atomicTransactionBody = extractFieldBytes(
                serializedAtomicBatchTxn.toReadableSequentialData(), TransactionBodySchema.ATOMIC_BATCH);
        return extractRepeatedFieldBytes(
                atomicTransactionBody.toReadableSequentialData(), AtomicBatchTransactionBodySchema.TRANSACTIONS);
    }

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
    private static List<Bytes> extractRepeatedFieldBytes(
            @NonNull final ReadableSequentialData input, @NonNull final FieldDefinition field)
            throws IOException, ParseException {
        if (!field.repeated()) {
            throw new IllegalArgumentException("Cannot extract field bytes for a non-repeated field: " + field);
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
