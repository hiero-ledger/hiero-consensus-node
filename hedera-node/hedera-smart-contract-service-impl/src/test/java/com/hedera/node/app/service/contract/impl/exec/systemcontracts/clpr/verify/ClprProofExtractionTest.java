// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.verify;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.node.state.clpr.ClprMessageKey;
import com.hedera.hapi.node.state.clpr.ClprMessageValue;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.platform.state.StateItem;
import com.hedera.hapi.platform.state.StateKey;
import com.hedera.hapi.platform.state.StateValue;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ClprProofExtractionTest {

    private static final ClprMessageKey MESSAGE_KEY = ClprMessageKey.newBuilder()
            .channelId(Bytes.wrap(new byte[] {1, 2, 3}))
            .messageId(42)
            .build();

    private static final StateKey MESSAGE_STATE_KEY =
            StateKey.newBuilder().clprServiceIMessageQueue(MESSAGE_KEY).build();

    private static final StateValue MESSAGE_STATE_VALUE = StateValue.newBuilder()
            .clprServiceIMessageQueue(ClprMessageValue.newBuilder()
                    .runningHashAfterProcessing(Bytes.wrap(new byte[32]))
                    .build())
            .build();

    @Test
    @DisplayName("given a keyed state item, then extractStateItemKey returns the serialized StateKey")
    void givenKeyedStateItem_thenExtractStateItemKeyReturnsTheSerializedStateKey() {
        final var item = stateItem(MESSAGE_STATE_KEY, MESSAGE_STATE_VALUE);

        assertThat(ClprProofExtraction.extractStateItemKey(item))
                .isEqualTo(StateKey.PROTOBUF.toBytes(MESSAGE_STATE_KEY));
    }

    @Test
    @DisplayName("given a keyed state item, then extractStateItemValue still returns the serialized StateValue")
    void givenKeyedStateItem_thenExtractStateItemValueReturnsTheSerializedStateValue() {
        final var item = stateItem(MESSAGE_STATE_KEY, MESSAGE_STATE_VALUE);

        assertThat(ClprProofExtraction.extractStateItemValue(item))
                .isEqualTo(StateValue.PROTOBUF.toBytes(MESSAGE_STATE_VALUE));
    }

    @Test
    @DisplayName("given a state item without a key, then extractStateItemKey returns null")
    void givenStateItemWithoutKey_thenExtractStateItemKeyReturnsNull() {
        final var item = StateItem.PROTOBUF.toBytes(
                StateItem.newBuilder().value(MESSAGE_STATE_VALUE).build());

        assertThat(ClprProofExtraction.extractStateItemKey(item)).isNull();
    }

    @Test
    @DisplayName("given empty bytes, then extractStateItemKey returns null")
    void givenEmptyBytes_thenExtractStateItemKeyReturnsNull() {
        assertThat(ClprProofExtraction.extractStateItemKey(Bytes.EMPTY)).isNull();
    }

    @Test
    @DisplayName("given a key whose declared length overruns the bytes, then extractStateItemKey returns null")
    void givenTruncatedKey_thenExtractStateItemKeyReturnsNull() {
        final byte[] full = stateItem(MESSAGE_STATE_KEY, MESSAGE_STATE_VALUE).toByteArray();
        // The key is the first field, so cutting the buffer inside it leaves its length prefix pointing past the end.
        final var truncated = Bytes.wrap(full, 0, 4);

        assertThat(ClprProofExtraction.extractStateItemKey(truncated)).isNull();
    }

    @Test
    @DisplayName("given a message-queue StateKey, then its first tag is SK_MESSAGE_KEY_TAG and it unwraps to the"
            + " ClprMessageKey")
    void givenMessageQueueStateKey_thenFirstTagIsMessageKeyTagAndItUnwrapsToTheMessageKey() throws ParseException {
        final var keyBytes = StateKey.PROTOBUF.toBytes(MESSAGE_STATE_KEY);

        assertThat(ClprProofExtraction.readFirstVarintTag(keyBytes)).isEqualTo(ClprProofExtraction.SK_MESSAGE_KEY_TAG);
        final var inner = ClprProofExtraction.unwrapStateValueField(keyBytes);
        assertThat(inner).isNotNull();
        assertThat(ClprMessageKey.PROTOBUF.parse(inner.toReadableSequentialData()))
                .isEqualTo(MESSAGE_KEY);
    }

    @Test
    @DisplayName("given a channel StateKey, then its first tag is not SK_MESSAGE_KEY_TAG")
    void givenChannelStateKey_thenFirstTagIsNotMessageKeyTag() {
        final var channelKey = StateKey.newBuilder()
                .clprServiceIChannels(
                        ProtoBytes.newBuilder().value(MESSAGE_KEY.channelId()).build())
                .build();

        assertThat(ClprProofExtraction.readFirstVarintTag(StateKey.PROTOBUF.toBytes(channelKey)))
                .isNotEqualTo(ClprProofExtraction.SK_MESSAGE_KEY_TAG);
    }

    private static Bytes stateItem(final StateKey key, final StateValue value) {
        return StateItem.PROTOBUF.toBytes(
                StateItem.newBuilder().key(key).value(value).build());
    }
}
