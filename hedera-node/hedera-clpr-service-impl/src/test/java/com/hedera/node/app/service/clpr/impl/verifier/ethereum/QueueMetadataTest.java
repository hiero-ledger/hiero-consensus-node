// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.ethereum;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.node.app.service.clpr.impl.verifier.ProofException;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class QueueMetadataTest {

    @Nested
    class Constructor {

        @Test
        void storesDefensiveCopiesOfHashes() {
            byte[] sent = bytes(32, 0x40);
            byte[] received = bytes(32, 0x50);
            byte[] last = bytes(32, 0x60);

            QueueMetadata metadata = new QueueMetadata(1L, sent, 2L, received, 3, last);

            // mutating the inputs after construction must not affect the record
            sent[0] = 0x7F;
            received[0] = 0x7F;
            last[0] = 0x7F;
            assertThat(metadata.sentRunningHash()).isEqualTo(bytes(32, 0x40));
            assertThat(metadata.receivedRunningHash()).isEqualTo(bytes(32, 0x50));
            assertThat(metadata.lastMessageRunningHash()).isEqualTo(bytes(32, 0x60));
        }

        @Test
        void accessorsReturnFreshCopies() {
            QueueMetadata metadata = new QueueMetadata(1L, bytes(32, 1), 2L, bytes(32, 2), 0, bytes(32, 3));

            assertThat(metadata.sentRunningHash()).isNotSameAs(metadata.sentRunningHash());
            assertThat(metadata.receivedRunningHash()).isNotSameAs(metadata.receivedRunningHash());
            assertThat(metadata.lastMessageRunningHash()).isNotSameAs(metadata.lastMessageRunningHash());
        }

        @Test
        void rejectsWrongLengthSentRunningHash() {
            assertThatThrownBy(() -> new QueueMetadata(1L, bytes(31, 1), 2L, bytes(32, 2), 0, bytes(32, 3)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("sentRunningHash must be 32 bytes, got 31");
        }

        @Test
        void rejectsWrongLengthReceivedRunningHash() {
            assertThatThrownBy(() -> new QueueMetadata(1L, bytes(32, 1), 2L, bytes(33, 2), 0, bytes(32, 3)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("receivedRunningHash must be 32 bytes, got 33");
        }

        @Test
        void rejectsWrongLengthLastMessageRunningHash() {
            assertThatThrownBy(() -> new QueueMetadata(1L, bytes(32, 1), 2L, bytes(32, 2), 0, bytes(0, 3)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("lastMessageRunningHash must be 32 bytes, got 0");
        }

        @Test
        void rejectsNullHash() {
            assertThatThrownBy(() -> new QueueMetadata(1L, null, 2L, bytes(32, 2), 0, bytes(32, 3)))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("sentRunningHash");
        }
    }

    @Nested
    class Decode {

        @Test
        void decodesAllFieldsFromTheFiveSlots() {
            byte[] sent = bytes(32, 0x40);
            byte[] received = bytes(32, 0x50);
            byte[] last = bytes(32, 0x60);

            QueueMetadata metadata = QueueMetadata.decode(new byte[][] {
                channelSlot0(0x0102030405060708L, 3), channelSlot1(0x1112131415161718L), sent, received, last
            });

            assertThat(metadata.nextMessageId()).isEqualTo(0x0102030405060708L);
            assertThat(metadata.status()).isEqualTo(3);
            assertThat(metadata.receivedMessageId()).isEqualTo(0x1112131415161718L);
            assertThat(metadata.sentRunningHash()).isEqualTo(sent);
            assertThat(metadata.receivedRunningHash()).isEqualTo(received);
            assertThat(metadata.lastMessageRunningHash()).isEqualTo(last);
        }

        @Test
        void readsStatusAsUnsignedByte() {
            QueueMetadata metadata = QueueMetadata.decode(
                    new byte[][] {channelSlot0(0L, 0xFF), channelSlot1(0L), bytes(32, 1), bytes(32, 2), bytes(32, 3)});

            assertThat(metadata.status()).isEqualTo(255);
        }

        @Test
        void ignoresBytesOutsideTheDecodedFields() {
            // Fill the packed slots completely; only the nextMessageId/status/receivedMessageId
            // windows must be read, the surrounding verifier/acked/reply bytes ignored.
            byte[] slot0 = bytes(32, 0x80);
            putUint64(slot0, 3, 7L);
            slot0[11] = 2;
            byte[] slot1 = bytes(32, 0x90);
            putUint64(slot1, 16, 9L);

            QueueMetadata metadata =
                    QueueMetadata.decode(new byte[][] {slot0, slot1, bytes(32, 1), bytes(32, 2), bytes(32, 3)});

            assertThat(metadata.nextMessageId()).isEqualTo(7L);
            assertThat(metadata.status()).isEqualTo(2);
            assertThat(metadata.receivedMessageId()).isEqualTo(9L);
        }

        @Test
        void rejectsNullInput() {
            assertThatThrownBy(() -> QueueMetadata.decode(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("provenSlotValues");
        }

        @Test
        void rejectsWrongSlotCount() {
            assertThatThrownBy(() -> QueueMetadata.decode(new byte[][] {bytes(32, 1), bytes(32, 2)}))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("expected 5 proven slot values, got 2");
        }

        @Test
        void rejectsSlotThatIsNot32Bytes() {
            assertThatThrownBy(() -> QueueMetadata.decode(new byte[][] {
                        channelSlot0(0L, 0), channelSlot1(0L), bytes(32, 1), bytes(32, 2), bytes(16, 3)
                    }))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("lastMessageRunningHash must be 32 bytes, got 16");
        }
    }

    // ── helpers ──

    /** Slot 0 layout (MSB→LSB): 3B padding | 8B nextMessageId | 1B status | 20B verifier. */
    private static byte[] channelSlot0(long nextMessageId, int status) {
        byte[] slot = new byte[32];
        putUint64(slot, 3, nextMessageId);
        slot[11] = (byte) status;
        return slot;
    }

    /** Slot 1 layout (MSB→LSB): 8B padding | 8B nextExpectedReplyId | 8B receivedMessageId | 8B ackedMessageId. */
    private static byte[] channelSlot1(long receivedMessageId) {
        byte[] slot = new byte[32];
        putUint64(slot, 16, receivedMessageId);
        return slot;
    }

    private static void putUint64(byte[] slot, int offset, long value) {
        ByteBuffer.wrap(slot, offset, 8).putLong(value);
    }

    private static byte[] bytes(int length, int seed) {
        byte[] out = new byte[length];
        for (int i = 0; i < length; i++) {
            out[i] = (byte) (seed + i * 31);
        }
        return out;
    }
}
