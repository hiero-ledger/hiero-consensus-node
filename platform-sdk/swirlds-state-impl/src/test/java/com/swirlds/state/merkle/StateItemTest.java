// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.merkle;

import static com.hedera.pbj.runtime.ProtoConstants.WIRE_TYPE_DELIMITED;
import static com.hedera.pbj.runtime.ProtoConstants.WIRE_TYPE_VARINT_OR_ZIGZAG;
import static com.hedera.pbj.runtime.ProtoParserTools.TAG_FIELD_OFFSET;
import static com.hedera.pbj.runtime.io.buffer.BufferedData.wrap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.pbj.runtime.io.stream.WritableStreamingData;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class StateItemTest {

    @Nested
    @DisplayName("StateItem record basics")
    class RecordBasics {
        @Test
        @DisplayName("Null key is rejected")
        void nullKeyRejected() {
            //noinspection ConstantConditions
            assertThrows(NullPointerException.class, () -> new StateItem(null, Bytes.EMPTY));
        }

        @Test
        @DisplayName("Null value is rejected")
        void nullValueRejected() {
            //noinspection ConstantConditions
            assertThrows(NullPointerException.class, () -> new StateItem(Bytes.EMPTY, null));
        }

        @Test
        @DisplayName("Default instance has empty key and value")
        void defaultInstance() {
            final var def = StateItem.CODEC.getDefaultInstance();
            assertEquals(Bytes.EMPTY, def.key());
            assertEquals(Bytes.EMPTY, def.value());
        }
    }

    @Nested
    @DisplayName("StateItemCodec behavior")
    class CodecBehavior {
        private byte[] serialize(@NonNull final StateItem item) throws IOException {
            final var baos = new ByteArrayOutputStream();
            final var out = new WritableStreamingData(baos);
            StateItem.CODEC.write(item, out);
            return baos.toByteArray();
        }

        private StateItem deserialize(final byte[] bytes) throws ParseException {
            return StateItem.CODEC.parse(wrap(bytes));
        }

        @Test
        @DisplayName("Round-trip with empty key and value")
        void roundTripEmpty() throws IOException, ParseException {
            final var item = new StateItem(Bytes.EMPTY, Bytes.EMPTY);
            final var bytes = serialize(item);
            final var parsed = deserialize(bytes);
            assertEquals(item, parsed);
        }

        @Test
        @DisplayName("Round-trip with non-empty key and value")
        void roundTripNonEmpty() throws IOException, ParseException {
            final var key = Bytes.wrap("key".getBytes());
            final var value = Bytes.wrap("value".getBytes());
            final var item = new StateItem(key, value);
            final var bytes = serialize(item);
            final var parsed = deserialize(bytes);
            assertEquals(item, parsed);
        }

        @Test
        @DisplayName("fastEquals matches parse equality for same bytes")
        void fastEqualsTrue() throws IOException, ParseException {
            final var key = Bytes.wrap(new byte[] {10, 11, 12});
            final var value = Bytes.wrap(new byte[] {42});
            final var item = new StateItem(key, value);
            final var bytes = serialize(item);
            final var in = wrap(bytes);
            assertThat(StateItem.CODEC.fastEquals(item, in)).isTrue();
        }

        @Test
        @DisplayName("fastEquals is false when bytes differ")
        void fastEqualsFalse() throws IOException, ParseException {
            final var item = new StateItem(Bytes.wrap(new byte[] {1}), Bytes.wrap(new byte[] {2}));
            final var other = new StateItem(Bytes.wrap(new byte[] {3}), Bytes.wrap(new byte[] {4}));
            final var bytes = serialize(other);
            final var in = wrap(bytes);
            assertThat(StateItem.CODEC.fastEquals(item, in)).isFalse();
        }

        @Test
        @DisplayName("measure equals actual encoded length")
        void measureMatchesEncodedLength() throws IOException, ParseException {
            final var rnd = new Random(1234);
            final byte[] keyArr = new byte[17];
            rnd.nextBytes(keyArr);
            final byte[] valArr = new byte[31];
            rnd.nextBytes(valArr);
            final var item = new StateItem(Bytes.wrap(keyArr), Bytes.wrap(valArr));

            final var bytes = serialize(item);
            final var measured = StateItem.CODEC.measure(wrap(bytes));
            assertEquals(bytes.length, measured);
        }

        @Test
        @DisplayName("measureRecord matches actual encoded length and is monotonic with size")
        void measureRecordMonotonic() throws IOException {
            final var small = new StateItem(Bytes.wrap(new byte[] {1}), Bytes.wrap(new byte[] {2, 3}));
            final var big = new StateItem(Bytes.wrap(new byte[] {1, 2, 3, 4}), Bytes.wrap(new byte[] {2, 3, 4, 5, 6}));

            // Compare measured record size to actual encoded length
            final var smallBytes = serialize(small);
            final var bigBytes = serialize(big);
            final int smallMeasure = StateItem.CODEC.measureRecord(small);
            final int bigMeasure = StateItem.CODEC.measureRecord(big);

            assertEquals(smallBytes.length, smallMeasure);
            assertEquals(bigBytes.length, bigMeasure);
            assertThat(bigMeasure).isGreaterThan(smallMeasure);
        }

        @Test
        @DisplayName("parse throws when key field number is not KEY_FIELD_ORDINAL")
        void parseThrowsOnWrongKeyFieldNum() {
            final var baos = new ByteArrayOutputStream();
            final var out = new WritableStreamingData(baos);
            // Create a tag with wrong field number (1 instead of 2) and correct wire type (delimited)
            final int wrongKeyField = 1;
            final int tag = (wrongKeyField << TAG_FIELD_OFFSET) | WIRE_TYPE_DELIMITED.ordinal();
            out.writeVarInt(tag, false);

            final var in = wrap(baos.toByteArray());
            assertThrows(ParseException.class, () -> StateItem.CODEC.parse(in));
        }

        @Test
        @DisplayName("parse throws when key wire type is not WIRE_TYPE_DELIMITED")
        void parseThrowsOnWrongKeyWireType() {
            final var baos = new ByteArrayOutputStream();
            final var out = new WritableStreamingData(baos);
            // Create a tag with correct field number (2) but wrong wire type (use VARINT instead of DELIMITED)
            final int field = StateItem.StateItemCodec.FIELD_KEY.number();
            final int wrongWire = WIRE_TYPE_VARINT_OR_ZIGZAG.ordinal();
            final int tag = (field << TAG_FIELD_OFFSET) | wrongWire;
            out.writeVarInt(tag, false);

            final var in = wrap(baos.toByteArray());
            assertThrows(ParseException.class, () -> StateItem.CODEC.parse(in));
        }

        @Test
        @DisplayName("parse throws when value field number is not VALUE_FIELD_ORDINAL")
        void parseThrowsOnWrongValueFieldNum() throws java.io.IOException {
            // First, write a valid key tag and size (size=0 to keep it simple)
            final var baos = new ByteArrayOutputStream();
            final var out = new WritableStreamingData(baos);
            final int keyTag =
                    (StateItem.StateItemCodec.FIELD_KEY.number() << TAG_FIELD_OFFSET) | WIRE_TYPE_DELIMITED.ordinal();
            out.writeVarInt(keyTag, false);
            out.writeVarInt(0, false); // key size = 0
            // Now write an invalid value tag with wrong field number (e.g., 4 instead of 3)
            final int wrongValueField = 4;
            final int badValueTag = (wrongValueField << TAG_FIELD_OFFSET) | WIRE_TYPE_DELIMITED.ordinal();
            out.writeVarInt(badValueTag, false);

            final var in = wrap(baos.toByteArray());
            assertThrows(ParseException.class, () -> StateItem.CODEC.parse(in));
        }
    }
}
