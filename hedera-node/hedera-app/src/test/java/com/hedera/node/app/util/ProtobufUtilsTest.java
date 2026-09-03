// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.hedera.hapi.node.base.QueryHeader;
import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.base.schema.QueryHeaderSchema;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.hapi.node.transaction.Query.QueryOneOfType;
import com.hedera.hapi.node.transaction.TransactionGetReceiptQuery;
import com.hedera.hapi.node.transaction.schema.TransactionGetReceiptQuerySchema;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.io.ByteArrayOutputStream;
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
