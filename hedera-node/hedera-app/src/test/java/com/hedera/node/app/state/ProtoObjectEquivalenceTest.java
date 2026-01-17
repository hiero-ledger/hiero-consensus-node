// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.state;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.hapi.platform.state.QueueState;
import com.hedera.hapi.platform.state.SingletonType;
import com.hedera.hapi.platform.state.StateItem;
import com.hedera.hapi.platform.state.StateKey;
import com.hedera.hapi.platform.state.StateValue;
import com.hedera.pbj.runtime.Codec;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.WritableSequentialData;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.pbj.runtime.io.stream.WritableStreamingData;
import com.swirlds.state.merkle.StateValue.StateValueCodec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies binary compatibility between swirlds-impl protos and HAPI-generated PBJ objects/codecs.
 *
 * The following types are covered:
 * - com.swirlds.state.merkle.StateItem vs com.hedera.hapi.platform.state.StateItem
 * - com.swirlds.state.merkle.StateValue vs com.hedera.hapi.platform.state.StateValue
 * - com.swirlds.state.merkle.disk.QueueState vs com.hedera.hapi.platform.state.QueueState
 */
public class ProtoObjectEquivalenceTest {

    // --- Helpers ---
    private static <T> byte[] encode(final Codec<T> codec, final T value) {
        try (final ByteArrayOutputStream bout = new ByteArrayOutputStream()) {
            final WritableSequentialData out = new WritableStreamingData(bout);
            codec.write(value, out);
            return bout.toByteArray();
        } catch (IOException e) {
            throw new AssertionError("Encoding failed", e);
        }
    }

    private static <T> T decode(final Codec<T> codec, final byte[] bytes) {
        try {
            final ReadableSequentialData in = BufferedData.wrap(bytes);
            return codec.parse(in);
        } catch (ParseException e) {
            throw new AssertionError("Decoding failed", e);
        }
    }

    // --- QueueState ---
    @Test
    @DisplayName("QueueState: bytes match and codecs cross-parse")
    void queueState_bytes_match_and_cross_parse() {
        final com.swirlds.state.merkle.disk.QueueState sw = new com.swirlds.state.merkle.disk.QueueState(123L, 456L);
        final QueueState pbj = new QueueState(123L, 456L);

        final byte[] swBytes = encode(new com.swirlds.state.merkle.disk.QueueState.QueueStateCodec(), sw);
        final byte[] pbjBytes = encode(QueueState.PROTOBUF, pbj);

        assertArrayEquals(pbjBytes, swBytes, "Swirlds and PBJ bytes must be identical for QueueState");

        // Cross-parse: PBJ bytes with Swirlds codec and vice versa
        final com.swirlds.state.merkle.disk.QueueState swParsedFromPbj =
                decode(new com.swirlds.state.merkle.disk.QueueState.QueueStateCodec(), pbjBytes);
        assertEquals(sw, swParsedFromPbj, "Swirlds codec should parse PBJ bytes into equal value");

        final QueueState pbjParsedFromSw = decode(QueueState.PROTOBUF, swBytes);
        assertEquals(pbj, pbjParsedFromSw, "PBJ codec should parse Swirlds bytes into equal value");
    }

    // --- StateValue with queue_state one-of (id = 8001) ---
    @Test
    @DisplayName("StateValue (queue_state): bytes match and codecs cross-parse for default and non-default")
    void stateValue_queueState_oneof_bytes_match_and_cross_parse() {
        // Field number for queue_state one-of, as defined by HAPI schema
        final int QUEUE_STATE_ID = 8001;

        // non-default value
        final QueueState pbjQueue = new QueueState(1L, 2L);
        final StateValue pbjValue = StateValue.newBuilder().queueState(pbjQueue).build();

        // swirlds codec for StateValue uses stateId and PBJ codec of the inner value
        final StateValueCodec<QueueState> swCodec = new StateValueCodec<>(QUEUE_STATE_ID, QueueState.PROTOBUF);
        final com.swirlds.state.merkle.StateValue<QueueState> swValue =
                new com.swirlds.state.merkle.StateValue<>(QUEUE_STATE_ID, pbjQueue);

        final byte[] swBytes = encode(swCodec, swValue);
        final byte[] pbjBytes = encode(StateValue.PROTOBUF, pbjValue);
        assertArrayEquals(pbjBytes, swBytes, "Swirlds and PBJ bytes must be identical for StateValue(queue_state)");

        // Cross-parse
        final com.swirlds.state.merkle.StateValue<QueueState> swParsedFromPbj = decode(swCodec, pbjBytes);
        assertEquals(swValue, swParsedFromPbj, "Swirlds codec should parse PBJ bytes into equal StateValue");

        final StateValue pbjParsedFromSw = decode(StateValue.PROTOBUF, swBytes);
        // Re-encode the parsed PBJ value and compare to original bytes to avoid deep equals complexity
        assertArrayEquals(pbjBytes, encode(StateValue.PROTOBUF, pbjParsedFromSw));

        // default inner (QueueState default is 0,0) still should match
        final QueueState pbjQueueDefault = QueueState.DEFAULT;
        final StateValue pbjValueDefault =
                StateValue.newBuilder().queueState(pbjQueueDefault).build();
        final com.swirlds.state.merkle.StateValue<QueueState> swValueDefault =
                new com.swirlds.state.merkle.StateValue<>(QUEUE_STATE_ID, pbjQueueDefault);
        final byte[] swBytesDefault = encode(swCodec, swValueDefault);
        final byte[] pbjBytesDefault = encode(StateValue.PROTOBUF, pbjValueDefault);
        assertArrayEquals(pbjBytesDefault, swBytesDefault);

        // Cross-parse default
        final com.swirlds.state.merkle.StateValue<QueueState> swParsedDefault = decode(swCodec, pbjBytesDefault);
        assertEquals(swValueDefault, swParsedDefault);
        final StateValue pbjParsedDefault = decode(StateValue.PROTOBUF, swBytesDefault);
        assertArrayEquals(pbjBytesDefault, encode(StateValue.PROTOBUF, pbjParsedDefault));
    }

    // --- StateItem ---
    @Test
    @DisplayName("StateItem: bytes match and codecs cross-parse")
    void stateItem_bytes_match_and_cross_parse() {
        // Build a simple PBJ StateKey using a singleton id (uses varint one-of field)
        final StateKey pbjKey = StateKey.newBuilder()
                .singleton(SingletonType.RECORDCACHE_I_TRANSACTION_RECEIPTS)
                .build();
        // Build PBJ StateValue using queue_state one-of with a non-default QueueState
        final QueueState pbjQueue = new QueueState(77L, 88L);
        final StateValue pbjValue = StateValue.newBuilder().queueState(pbjQueue).build();

        // Encode PBJ nested messages so we can feed them into swirlds StateItem (which stores nested bytes)
        final byte[] keyBytes = encode(StateKey.PROTOBUF, pbjKey);
        final byte[] valueBytes = encode(StateValue.PROTOBUF, pbjValue);
        final com.swirlds.state.merkle.StateItem swItem =
                new com.swirlds.state.merkle.StateItem(Bytes.wrap(keyBytes), Bytes.wrap(valueBytes));

        // Construct an equivalent PBJ StateItem
        final StateItem pbjItem = new StateItem(pbjKey, pbjValue);

        final byte[] swBytes = encode(com.swirlds.state.merkle.StateItem.CODEC, swItem);
        final byte[] pbjBytes = encode(StateItem.PROTOBUF, pbjItem);
        assertArrayEquals(pbjBytes, swBytes, "Swirlds and PBJ bytes must be identical for StateItem");

        // Cross-parse: PBJ bytes with swirlds codec
        final com.swirlds.state.merkle.StateItem swParsedFromPbj =
                decode(com.swirlds.state.merkle.StateItem.CODEC, pbjBytes);
        assertEquals(swItem, swParsedFromPbj, "Swirlds codec should parse PBJ bytes into equal StateItem");
        // And swirlds bytes with PBJ codec
        final StateItem pbjParsedFromSw = decode(StateItem.PROTOBUF, swBytes);
        // Re-encode and compare
        assertArrayEquals(pbjBytes, encode(StateItem.PROTOBUF, pbjParsedFromSw));
    }
}
