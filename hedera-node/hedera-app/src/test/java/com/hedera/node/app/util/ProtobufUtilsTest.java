// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.util;

import static com.hedera.hapi.node.base.schema.QueryHeaderSchema.PAYMENT;
import static com.hedera.hapi.node.base.schema.QueryHeaderSchema.RESPONSE_TYPE;
import static com.hedera.hapi.node.base.schema.TransactionSchema.SIGNED_TRANSACTION_BYTES;
import static com.hedera.hapi.node.transaction.schema.QuerySchema.TRANSACTION_GET_RECEIPT;
import static com.hedera.hapi.node.transaction.schema.TransactionGetReceiptQuerySchema.HEADER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.hedera.hapi.node.base.QueryHeader;
import com.hedera.hapi.node.base.ResponseType;
import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.base.schema.QueryHeaderSchema;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.hapi.node.transaction.Query.QueryOneOfType;
import com.hedera.hapi.node.transaction.TransactionGetReceiptQuery;
import com.hedera.hapi.node.transaction.schema.TransactionGetReceiptQuerySchema;
import com.hedera.pbj.runtime.FieldDefinition;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.ProtoConstants;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.BufferUnderflowException;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ProtobufUtils#extractPaymentBytes(Bytes)}, the hand-written walker that lifts the payment
 * transaction out of a serialized {@link Query}.
 *
 * <p>The walker must resolve a duplicated non-repeated field the same way as the canonical PBJ parse used by
 * the rest of the query workflow; otherwise the payment it extracts could differ from the one the rest of the
 * node acts on. The parser rejects such ambiguity, so the duplicate-field cases below assert a
 * {@link ParseException} rather than returning a particular occurrence.
 */
class ProtobufUtilsTest {

    private static final int VARINT = ProtoConstants.WIRE_TYPE_VARINT_OR_ZIGZAG.ordinal();
    private static final int FIXED_64_BIT = ProtoConstants.WIRE_TYPE_FIXED_64_BIT.ordinal();
    private static final int DELIMITED = ProtoConstants.WIRE_TYPE_DELIMITED.ordinal();

    /** Protobuf leaves wire types 6 and 7 unassigned, so neither has a {@link ProtoConstants} constant. */
    private static final int UNASSIGNED_WIRE_TYPE = 6;

    /** Any field number outside the query oneof, which the scanner has to skip over. */
    private static final int NOT_A_QUERY_FIELD = 17;

    // protobuf LEN / length-delimited wire type
    private static final int WIRE_LEN = 2;

    private static final int PAYMENT_FIELD = QueryHeaderSchema.PAYMENT.number();
    private static final int HEADER_FIELD = TransactionGetReceiptQuerySchema.HEADER.number();
    private static final int RECEIPT_FIELD = QueryOneOfType.TRANSACTION_GET_RECEIPT.protoOrdinal();

    private static final Bytes PAYMENT_A = Transaction.PROTOBUF.toBytes(Transaction.newBuilder()
            .signedTransactionBytes(Bytes.wrap(new byte[] {1, 2, 3}))
            .build());
    private static final Bytes PAYMENT_B = Transaction.PROTOBUF.toBytes(Transaction.newBuilder()
            .signedTransactionBytes(Bytes.wrap(new byte[] {7, 8, 9}))
            .build());

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

    @Test
    void extractsPaymentFromWellFormedQuery() throws Exception {
        final var payment = Transaction.newBuilder()
                .signedTransactionBytes(Bytes.wrap(new byte[] {1, 2, 3}))
                .build();
        final var query = Query.newBuilder()
                .transactionGetReceipt(TransactionGetReceiptQuery.newBuilder()
                        .header(QueryHeader.newBuilder().payment(payment)))
                .build();

        assertEquals(
                Transaction.PROTOBUF.toBytes(payment),
                ProtobufUtils.extractPaymentBytes(Query.PROTOBUF.toBytes(query)));
    }

    @Test
    void ignoresUnrelatedFieldsWhenExtracting() throws Exception {
        // A well-formed query prefixed with an unrelated (non-query) field must still resolve correctly.
        final var wellFormed = query(receipt(header(field(PAYMENT_FIELD, PAYMENT_A))));
        final var withNoise = concat(field(999, Bytes.wrap(new byte[] {42})), wellFormed);

        assertEquals(PAYMENT_A, ProtobufUtils.extractPaymentBytes(withNoise));
    }

    @Test
    void rejectsDuplicatePaymentField() throws Exception {
        // A QueryHeader whose payment field is present twice (A then B).
        final var dupHeader = concat(field(PAYMENT_FIELD, PAYMENT_A), field(PAYMENT_FIELD, PAYMENT_B));
        final var serialized = query(receipt(dupHeader));

        // Our walker refuses the ambiguity...
        assertThrows(ParseException.class, () -> ProtobufUtils.extractPaymentBytes(serialized));

        // ...whereas the canonical PBJ parse keeps the LAST occurrence — the divergence this parser must not allow.
        final var pbjPayment = Query.PROTOBUF
                .parse(serialized.toReadableSequentialData())
                .transactionGetReceiptOrThrow()
                .headerOrThrow()
                .paymentOrThrow();
        assertEquals(PAYMENT_B, Transaction.PROTOBUF.toBytes(pbjPayment));
    }

    @Test
    void rejectsDuplicateHeaderField() {
        final var goodHeader = field(PAYMENT_FIELD, PAYMENT_A);
        final var serialized = query(concat(field(HEADER_FIELD, goodHeader), field(HEADER_FIELD, goodHeader)));

        assertThrows(ParseException.class, () -> ProtobufUtils.extractPaymentBytes(serialized));
    }

    @Test
    void rejectsDuplicateQueryField() {
        final var goodReceipt = receipt(header(field(PAYMENT_FIELD, PAYMENT_A)));
        // The query oneof set twice (even to the same type) is ambiguous and must be rejected.
        final var serialized = concat(field(RECEIPT_FIELD, goodReceipt), field(RECEIPT_FIELD, goodReceipt));

        assertThrows(ParseException.class, () -> ProtobufUtils.extractPaymentBytes(serialized));
    }

    @Test
    void throwsWhenPaymentMissing() {
        // A valid query and header, but no payment field inside the header.
        final var serialized = query(receipt(Bytes.EMPTY));

        assertThrows(ParseException.class, () -> ProtobufUtils.extractPaymentBytes(serialized));
    }

    @Test
    void throwsWhenQueryMissing() {
        // Bytes that contain only a non-query field.
        final var serialized = field(999, Bytes.wrap(new byte[] {42}));

        assertThrows(ParseException.class, () -> ProtobufUtils.extractPaymentBytes(serialized));
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

    // --- wire-format helpers: build raw protobuf bytes with full control over field occurrences ---

    private static Bytes query(final Bytes receiptBytes) {
        return field(RECEIPT_FIELD, receiptBytes);
    }

    private static Bytes receipt(final Bytes headerBytes) {
        return field(HEADER_FIELD, headerBytes);
    }

    private static Bytes header(final Bytes paymentField) {
        return paymentField;
    }

    /** Encode a single length-delimited field: tag (fieldNum, LEN) + varint length + value. */
    private static Bytes field(final int fieldNum, final Bytes value) {
        final var out = new ByteArrayOutputStream();
        writeVarint(out, ((long) fieldNum << 3) | WIRE_LEN);
        final byte[] v = value.toByteArray();
        writeVarint(out, v.length);
        out.writeBytes(v);
        return Bytes.wrap(out.toByteArray());
    }

    private static Bytes concat(final Bytes... parts) {
        final var out = new ByteArrayOutputStream();
        for (final var part : parts) {
            out.writeBytes(part.toByteArray());
        }
        return Bytes.wrap(out.toByteArray());
    }

    private static void writeVarint(final ByteArrayOutputStream out, long value) {
        while (true) {
            final int b = (int) (value & 0x7F);
            value >>>= 7;
            if (value != 0) {
                out.write(b | 0x80);
            } else {
                out.write(b);
                return;
            }
        }
    }
}
