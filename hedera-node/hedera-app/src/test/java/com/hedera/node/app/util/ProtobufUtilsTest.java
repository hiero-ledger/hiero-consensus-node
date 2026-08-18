// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.util;

import static com.hedera.hapi.node.base.schema.QueryHeaderSchema.PAYMENT;
import static com.hedera.hapi.node.base.schema.QueryHeaderSchema.RESPONSE_TYPE;
import static com.hedera.hapi.node.base.schema.TransactionSchema.SIGNED_TRANSACTION_BYTES;
import static com.hedera.hapi.node.transaction.schema.QuerySchema.TRANSACTION_GET_RECEIPT;
import static com.hedera.hapi.node.transaction.schema.TransactionGetReceiptQuerySchema.HEADER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.hapi.node.base.QueryHeader;
import com.hedera.hapi.node.base.ResponseType;
import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.hapi.node.transaction.TransactionGetReceiptQuery;
import com.hedera.pbj.runtime.FieldDefinition;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.ProtoConstants;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.io.IOException;
import java.nio.BufferUnderflowException;
import org.junit.jupiter.api.Test;

class ProtobufUtilsTest {

    private static final int VARINT = ProtoConstants.WIRE_TYPE_VARINT_OR_ZIGZAG.ordinal();
    private static final int FIXED_64_BIT = ProtoConstants.WIRE_TYPE_FIXED_64_BIT.ordinal();
    private static final int DELIMITED = ProtoConstants.WIRE_TYPE_DELIMITED.ordinal();

    /** Protobuf leaves wire types 6 and 7 unassigned, so neither has a {@link ProtoConstants} constant. */
    private static final int UNASSIGNED_WIRE_TYPE = 6;

    /** Any field number outside the query oneof, which the scanner has to skip over. */
    private static final int NOT_A_QUERY_FIELD = 17;

    @Test
    void extractsPaymentBytesVerbatim() throws IOException, ParseException {
        final var payment = Transaction.newBuilder()
                .signedTransactionBytes(Bytes.fromHex("010203"))
                .build();
        final var serializedQuery = Query.PROTOBUF.toBytes(Query.newBuilder()
                .transactionGetReceipt(TransactionGetReceiptQuery.newBuilder()
                        .header(QueryHeader.newBuilder().payment(payment).build())
                        .build())
                .build());

        assertThat(ProtobufUtils.extractPaymentBytes(serializedQuery)).isEqualTo(Transaction.PROTOBUF.toBytes(payment));
    }

    @Test
    void extractsPaymentBytesAfterSkippingPrecedingFields() throws IOException, ParseException {
        // PBJ always writes fields in field number order, so a header whose responseType precedes its
        // payment cannot be produced by the builders and has to be assembled by hand
        final var payment = message(SIGNED_TRANSACTION_BYTES, "010203");
        final var header = tag(RESPONSE_TYPE.number(), VARINT)
                + varint(ResponseType.COST_ANSWER.protoOrdinal())
                + message(PAYMENT, payment);
        final var serializedQuery = Bytes.fromHex(message(TRANSACTION_GET_RECEIPT, message(HEADER, header)));

        assertThat(ProtobufUtils.extractPaymentBytes(serializedQuery)).isEqualTo(Bytes.fromHex(payment));
    }

    @Test
    void rejectsUint32MaxLengthAsParseException() {
        // A length of 0xFFFFFFFF, which is -1 once the varint is truncated to an int
        final var serializedQuery =
                Bytes.fromHex(tag(TRANSACTION_GET_RECEIPT.number(), DELIMITED) + varint(0xFFFFFFFFL));

        assertThatThrownBy(() -> ProtobufUtils.extractPaymentBytes(serializedQuery))
                .isInstanceOf(ParseException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNegativeLengthAsParseException() {
        // The same -1, but written out as a full width ten byte varint
        final var serializedQuery = Bytes.fromHex(tag(TRANSACTION_GET_RECEIPT.number(), DELIMITED) + varint(-1L));

        assertThatThrownBy(() -> ProtobufUtils.extractPaymentBytes(serializedQuery))
                .isInstanceOf(ParseException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsOversizedNestedFieldLengthAsParseException() {
        // The header claims five bytes, but only one is left inside the query
        final var serializedQuery =
                Bytes.fromHex(message(TRANSACTION_GET_RECEIPT, tag(HEADER.number(), DELIMITED) + varint(5) + "00"));

        assertThatThrownBy(() -> ProtobufUtils.extractPaymentBytes(serializedQuery))
                .isInstanceOf(ParseException.class)
                .hasCauseInstanceOf(BufferUnderflowException.class);
    }

    @Test
    void rejectsTruncatedLengthVarintAsParseException() {
        // 0x80 sets the continuation bit, but the input ends before the varint does
        final var serializedQuery = Bytes.fromHex(tag(TRANSACTION_GET_RECEIPT.number(), DELIMITED) + "80");

        assertThatThrownBy(() -> ProtobufUtils.extractPaymentBytes(serializedQuery))
                .isInstanceOf(ParseException.class)
                .hasCauseInstanceOf(BufferUnderflowException.class);
    }

    @Test
    void rejectsMalformedLengthVarintAsParseException() {
        // Ten continuation bytes in a row, so the varint never terminates
        final var serializedQuery = Bytes.fromHex(tag(TRANSACTION_GET_RECEIPT.number(), DELIMITED) + "FF".repeat(10));

        assertThatThrownBy(() -> ProtobufUtils.extractPaymentBytes(serializedQuery))
                .isInstanceOf(ParseException.class);
    }

    @Test
    void rejectsTruncatedSkippedFieldAsParseException() {
        // The skipped field says a fixed 64-bit value follows, but only two bytes are left
        final var serializedQuery = Bytes.fromHex(tag(NOT_A_QUERY_FIELD, FIXED_64_BIT) + "0000");

        assertThatThrownBy(() -> ProtobufUtils.extractPaymentBytes(serializedQuery))
                .isInstanceOf(ParseException.class)
                .hasCauseInstanceOf(BufferUnderflowException.class);
    }

    @Test
    void rejectsUnassignedWireTypeAsParseException() {
        final var serializedQuery = Bytes.fromHex(tag(PAYMENT.number(), UNASSIGNED_WIRE_TYPE) + "01");

        assertThatThrownBy(() -> ProtobufUtils.extractPaymentBytes(serializedQuery))
                .isInstanceOf(ParseException.class)
                .hasMessageContaining("Invalid wire type: " + UNASSIGNED_WIRE_TYPE);
    }

    @Test
    void doesNotTreatOutOfRangeFieldNumberAsAQueryField() {
        // Field number 536870911 with wire type 2, whose tag is 0xFFFFFFFA. Shifting that tag arithmetically
        // rather than logically would yield -1, which is the proto ordinal of the unset query oneof
        final var serializedQuery = Bytes.fromHex(lengthDelimited(varint(0xFFFFFFFAL), message(PAYMENT, "")));

        assertThatThrownBy(() -> ProtobufUtils.extractPaymentBytes(serializedQuery))
                .isInstanceOf(ParseException.class)
                .hasMessageContaining("Query not found");
    }

    /** Encodes a value as a protobuf varint: seven bits per byte, high bit set on all but the last. */
    private static String varint(final long value) {
        final var hex = new StringBuilder();
        var remaining = value;
        do {
            final var septet = remaining & 0x7F;
            remaining >>>= 7;
            hex.append(String.format("%02X", remaining == 0 ? septet : septet | 0x80));
        } while (remaining != 0);
        return hex.toString();
    }

    /** Encodes a protobuf tag: the field number in the upper bits, the wire type in the lowest three. */
    private static String tag(final int fieldNumber, final int wireType) {
        return varint(((long) fieldNumber << 3) | wireType);
    }

    /** Encodes an already tagged length-delimited field, measuring the payload rather than declaring it. */
    private static String lengthDelimited(final String tag, final String payload) {
        return tag + varint(payload.length() / 2) + payload;
    }

    /** Encodes {@code field} as a length-delimited field wrapping {@code payload}. */
    private static String message(final FieldDefinition field, final String payload) {
        return lengthDelimited(tag(field.number(), DELIMITED), payload);
    }
}
