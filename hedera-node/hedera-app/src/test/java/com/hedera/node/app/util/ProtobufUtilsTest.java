// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.io.IOException;
import java.nio.BufferUnderflowException;
import org.junit.jupiter.api.Test;

class ProtobufUtilsTest {

    @Test
    void extractsEmptyPaymentBytes() throws IOException, ParseException {
        // transactionGetReceipt(14) { header(1) { payment(1) {} } }
        final var serializedQuery = Bytes.fromHex("7204" + "0A02" + "0A00");

        assertThat(ProtobufUtils.extractPaymentBytes(serializedQuery)).isEqualTo(Bytes.EMPTY);
    }

    @Test
    void extractsPaymentBytesVerbatimAfterSkippingPrecedingFields() throws IOException, ParseException {
        // transactionGetReceipt(14) { header(1) { responseType(2) = 2, payment(1) { signedTransactionBytes(5) } } }
        final var payment = "0A05" + "2A03010203";
        final var serializedQuery = Bytes.fromHex("720B" + "0A09" + "1002" + payment);

        // The payment is returned exactly as it appeared on the wire, since re-serializing it could break signatures
        assertThat(ProtobufUtils.extractPaymentBytes(serializedQuery)).isEqualTo(Bytes.fromHex("2A03010203"));
    }

    @Test
    void rejectsUint32MaxLengthAsParseException() {
        // transactionGetReceipt(14) with a length varint of 0xFFFFFFFF, which decodes to a negative int
        final var serializedQuery = Bytes.fromHex("72" + "FFFFFFFF0F");

        assertThatThrownBy(() -> ProtobufUtils.extractPaymentBytes(serializedQuery))
                .isInstanceOf(ParseException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNegativeLengthAsParseException() {
        // transactionGetReceipt(14) with a full-width length varint that decodes to -1
        final var serializedQuery = Bytes.fromHex("72" + "FFFFFFFFFFFFFFFFFF01");

        assertThatThrownBy(() -> ProtobufUtils.extractPaymentBytes(serializedQuery))
                .isInstanceOf(ParseException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsOversizedNestedFieldLengthAsParseException() {
        // The header declares 5 bytes, but only one byte remains in the transactionGetReceipt payload
        final var serializedQuery = Bytes.fromHex("7203" + "0A05" + "00");

        assertThatThrownBy(() -> ProtobufUtils.extractPaymentBytes(serializedQuery))
                .isInstanceOf(ParseException.class)
                .hasCauseInstanceOf(BufferUnderflowException.class);
    }

    @Test
    void rejectsTruncatedLengthVarintAsParseException() {
        // transactionGetReceipt(14) with a length varint that is cut off mid-way
        final var serializedQuery = Bytes.fromHex("72" + "80");

        assertThatThrownBy(() -> ProtobufUtils.extractPaymentBytes(serializedQuery))
                .isInstanceOf(ParseException.class);
    }

    @Test
    void rejectsMalformedLengthVarintAsParseException() {
        // transactionGetReceipt(14) followed by ten continuation bytes that never terminate the varint
        final var serializedQuery = Bytes.fromHex("72" + "FFFFFFFFFFFFFFFFFFFF");

        assertThatThrownBy(() -> ProtobufUtils.extractPaymentBytes(serializedQuery))
                .isInstanceOf(ParseException.class);
    }

    @Test
    void rejectsInvalidWireTypeAsParseException() {
        // Field 1, wire type 6, which is not a defined wire type
        final var serializedQuery = Bytes.fromHex("0E" + "01");

        assertThatThrownBy(() -> ProtobufUtils.extractPaymentBytes(serializedQuery))
                .isInstanceOf(ParseException.class)
                .hasMessageContaining("Invalid wire type: 6");
    }

    @Test
    void doesNotTreatOutOfRangeFieldNumberAsAQueryField() {
        // Tag 0xFFFFFFFA is field number 536870911 with wire type 2. An arithmetic shift of the tag would instead
        // yield -1, which is the proto ordinal of the unset query oneof
        final var serializedQuery = Bytes.fromHex("FAFFFFFF0F" + "02" + "0A00");

        assertThatThrownBy(() -> ProtobufUtils.extractPaymentBytes(serializedQuery))
                .isInstanceOf(ParseException.class)
                .hasMessageContaining("Query not found");
    }
}
