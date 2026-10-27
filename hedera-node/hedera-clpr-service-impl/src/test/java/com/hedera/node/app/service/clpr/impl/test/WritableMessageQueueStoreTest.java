// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.test;

import static com.hedera.node.app.service.clpr.impl.schemas.V0780ClprSchema.MESSAGE_QUEUE_STATE_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.state.clpr.ClprControlMessage;
import com.hedera.hapi.node.state.clpr.ClprMessage;
import com.hedera.hapi.node.state.clpr.ClprMessageKey;
import com.hedera.hapi.node.state.clpr.ClprMessagePayload;
import com.hedera.hapi.node.state.clpr.ClprMessageReply;
import com.hedera.hapi.node.state.clpr.ClprMessageValue;
import com.hedera.node.app.service.clpr.impl.WritableMessageQueueStore;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.spi.WritableStates;
import com.swirlds.state.test.fixtures.MapWritableKVState;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WritableMessageQueueStoreTest {

    private static final Bytes CHANNEL_ID = Bytes.wrap(new byte[32]);
    private static final ClprMessagePayload DATA =
            ClprMessagePayload.newBuilder().message(ClprMessage.DEFAULT).build();
    private static final ClprMessagePayload CONTROL =
            ClprMessagePayload.newBuilder().control(ClprControlMessage.DEFAULT).build();
    private static final ClprMessagePayload REPLY = ClprMessagePayload.newBuilder()
            .messageReply(ClprMessageReply.DEFAULT)
            .build();
    private static final Predicate<ClprMessagePayload> IS_CONTROL_OR_REPLY =
            payload -> payload.hasControl() || payload.hasMessageReply();

    @Mock
    private WritableStates writableStates;

    private WritableMessageQueueStore subject;

    @BeforeEach
    void setUp() {
        final var messages = MapWritableKVState.<ClprMessageKey, ClprMessageValue>builder(
                        MESSAGE_QUEUE_STATE_ID, "ClprService:MESSAGE_QUEUE")
                .build();
        given(writableStates.<ClprMessageKey, ClprMessageValue>get(MESSAGE_QUEUE_STATE_ID))
                .willReturn(messages);
        subject = new WritableMessageQueueStore(writableStates);
    }

    @Test
    @DisplayName("given data, control and reply messages, then only the matching ones in the range are removed")
    void givenMixedMessages_thenOnlyMatchingOnesAreRemoved() {
        putPayload(1, DATA);
        putPayload(2, CONTROL);
        putPayload(3, REPLY);

        subject.removeMessagesInRange(CHANNEL_ID, 1, 3, IS_CONTROL_OR_REPLY);

        assertThat(subject.getMessage(CHANNEL_ID, 1)).isNotNull();
        assertThat(subject.getMessage(CHANNEL_ID, 2)).isNull();
        assertThat(subject.getMessage(CHANNEL_ID, 3)).isNull();
    }

    @Test
    @DisplayName("given matching messages at and beyond both ends, then both ends are removed and the rest kept")
    void givenMatchingMessagesAroundRange_thenBothEndsAreRemovedAndTheRestKept() {
        for (long messageId = 1; messageId <= 5; messageId++) {
            putPayload(messageId, CONTROL);
        }

        subject.removeMessagesInRange(CHANNEL_ID, 2, 4, IS_CONTROL_OR_REPLY);

        assertThat(subject.getMessage(CHANNEL_ID, 1)).isNotNull();
        assertThat(subject.getMessage(CHANNEL_ID, 2)).isNull();
        assertThat(subject.getMessage(CHANNEL_ID, 3)).isNull();
        assertThat(subject.getMessage(CHANNEL_ID, 4)).isNull();
        assertThat(subject.getMessage(CHANNEL_ID, 5)).isNotNull();
    }

    @Test
    @DisplayName("given missing ids and a message without payload, then they are skipped and the rest removed")
    void givenMissingIdsAndMessageWithoutPayload_thenTheyAreSkippedAndTheRestRemoved() {
        put(
                2,
                ClprMessageValue.newBuilder()
                        .runningHashAfterProcessing(Bytes.EMPTY)
                        .build());
        putPayload(4, REPLY);

        subject.removeMessagesInRange(CHANNEL_ID, 1, 5, payload -> true);

        assertThat(subject.getMessage(CHANNEL_ID, 2)).isNotNull();
        assertThat(subject.getMessage(CHANNEL_ID, 4)).isNull();
    }

    @Test
    @DisplayName("given the first id is after the last, then nothing is removed")
    void givenFirstIdAfterLast_thenNothingIsRemoved() {
        putPayload(1, CONTROL);
        putPayload(2, CONTROL);

        subject.removeMessagesInRange(CHANNEL_ID, 2, 1, IS_CONTROL_OR_REPLY);

        assertThat(subject.getMessage(CHANNEL_ID, 1)).isNotNull();
        assertThat(subject.getMessage(CHANNEL_ID, 2)).isNotNull();
    }

    private void putPayload(final long messageId, @NonNull final ClprMessagePayload payload) {
        put(
                messageId,
                ClprMessageValue.newBuilder()
                        .payload(payload)
                        .runningHashAfterProcessing(Bytes.EMPTY)
                        .build());
    }

    private void put(final long messageId, @NonNull final ClprMessageValue value) {
        subject.put(CHANNEL_ID, messageId, value);
    }
}
