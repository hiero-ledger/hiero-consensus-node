// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.ethereum;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.node.app.service.clpr.impl.verifier.ProofException;
import java.math.BigInteger;
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

            QueueMetadata metadata = new QueueMetadata(1L, sent, 2L, received, 3, last, 9L);

            // mutating the inputs after construction must not affect the record
            sent[0] = 0x7F;
            received[0] = 0x7F;
            last[0] = 0x7F;
            assertThat(metadata.sentRunningHash()).isEqualTo(bytes(32, 0x40));
            assertThat(metadata.receivedRunningHash()).isEqualTo(bytes(32, 0x50));
            assertThat(metadata.lastMessageRunningHash()).isEqualTo(bytes(32, 0x60));
            assertThat(metadata.endpointManifestVersion()).isEqualTo(9L);
        }

        @Test
        void accessorsReturnFreshCopies() {
            QueueMetadata metadata = new QueueMetadata(1L, bytes(32, 1), 2L, bytes(32, 2), 0, bytes(32, 3), 0L);

            assertThat(metadata.sentRunningHash()).isNotSameAs(metadata.sentRunningHash());
            assertThat(metadata.receivedRunningHash()).isNotSameAs(metadata.receivedRunningHash());
            assertThat(metadata.lastMessageRunningHash()).isNotSameAs(metadata.lastMessageRunningHash());
        }

        @Test
        void rejectsWrongLengthSentRunningHash() {
            assertThatThrownBy(() -> new QueueMetadata(1L, bytes(31, 1), 2L, bytes(32, 2), 0, bytes(32, 3), 0L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("sentRunningHash must be 32 bytes, got 31");
        }

        @Test
        void rejectsWrongLengthReceivedRunningHash() {
            assertThatThrownBy(() -> new QueueMetadata(1L, bytes(32, 1), 2L, bytes(33, 2), 0, bytes(32, 3), 0L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("receivedRunningHash must be 32 bytes, got 33");
        }

        @Test
        void rejectsWrongLengthLastMessageRunningHash() {
            assertThatThrownBy(() -> new QueueMetadata(1L, bytes(32, 1), 2L, bytes(32, 2), 0, bytes(0, 3), 0L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("lastMessageRunningHash must be 32 bytes, got 0");
        }

        @Test
        void rejectsNullHash() {
            assertThatThrownBy(() -> new QueueMetadata(1L, null, 2L, bytes(32, 2), 0, bytes(32, 3), 0L))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("sentRunningHash");
        }
    }

    @Nested
    class Decode {

        @Test
        void decodesAllFieldsFromTheFiveChannelSlots() {
            // ACK-only: the five Channel slots {1,2,4,5,16}, no last-message running hash.
            byte[] sent = bytes(32, 0x40);
            byte[] received = bytes(32, 0x50);

            QueueMetadata metadata = QueueMetadata.decode(channelKeys(), new byte[][] {
                channelSlot0(0x0102030405060708L, 3),
                channelSlot1(0x1112131415161718L),
                sent,
                received,
                manifestSlot(42L)
            });

            assertThat(metadata.nextMessageId()).isEqualTo(0x0102030405060708L);
            assertThat(metadata.status()).isEqualTo(3);
            assertThat(metadata.receivedMessageId()).isEqualTo(0x1112131415161718L);
            assertThat(metadata.sentRunningHash()).isEqualTo(sent);
            assertThat(metadata.receivedRunningHash()).isEqualTo(received);
            // proven from the offset-16 slot, never from relayed content
            assertThat(metadata.endpointManifestVersion()).isEqualTo(42L);
            // no message slot in an ACK-only bundle → zero running hash
            assertThat(metadata.lastMessageRunningHash()).isEqualTo(new byte[32]);
        }

        @Test
        void decodesLastMessageRunningHashFromTheSixthSlot() {
            // Message-bearing: the five Channel slots + the last-message running-hash outlier (sorts last).
            byte[] sent = bytes(32, 0x40);
            byte[] received = bytes(32, 0x50);
            byte[] last = bytes(32, 0x60);

            QueueMetadata metadata = QueueMetadata.decode(
                    channelKeysWithMessage(),
                    new byte[][] {channelSlot0(7L, 1), channelSlot1(9L), sent, received, manifestSlot(3L), last});

            assertThat(metadata.nextMessageId()).isEqualTo(7L);
            assertThat(metadata.receivedMessageId()).isEqualTo(9L);
            assertThat(metadata.sentRunningHash()).isEqualTo(sent);
            assertThat(metadata.receivedRunningHash()).isEqualTo(received);
            assertThat(metadata.endpointManifestVersion()).isEqualTo(3L);
            assertThat(metadata.lastMessageRunningHash()).isEqualTo(last);
        }

        @Test
        void locatesTheChannelClusterWhenTheMessageSlotSortsFirst() {
            // Outlier key below the cluster → clusterStart == 1; the first proven value is the message hash.
            byte[] last = bytes(32, 0x61);
            final byte[][] keys = new byte[][] {
                key(1L), // outlier (sorts first, below base+1)
                key(BASE + 1),
                key(BASE + 2),
                key(BASE + 4),
                key(BASE + 5),
                key(BASE + 16)
            };
            QueueMetadata metadata = QueueMetadata.decode(keys, new byte[][] {
                last, channelSlot0(5L, 2), channelSlot1(4L), bytes(32, 0x40), bytes(32, 0x50), manifestSlot(8L)
            });

            assertThat(metadata.nextMessageId()).isEqualTo(5L);
            assertThat(metadata.status()).isEqualTo(2);
            assertThat(metadata.receivedMessageId()).isEqualTo(4L);
            assertThat(metadata.endpointManifestVersion()).isEqualTo(8L);
            assertThat(metadata.lastMessageRunningHash()).isEqualTo(last);
        }

        @Test
        void readsStatusAsUnsignedByte() {
            QueueMetadata metadata = QueueMetadata.decode(channelKeys(), new byte[][] {
                channelSlot0(0L, 0xFF), channelSlot1(0L), bytes(32, 1), bytes(32, 2), manifestSlot(0L)
            });

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

            QueueMetadata metadata = QueueMetadata.decode(
                    channelKeys(), new byte[][] {slot0, slot1, bytes(32, 1), bytes(32, 2), manifestSlot(0L)});

            assertThat(metadata.nextMessageId()).isEqualTo(7L);
            assertThat(metadata.status()).isEqualTo(2);
            assertThat(metadata.receivedMessageId()).isEqualTo(9L);
        }

        @Test
        void readsEndpointManifestVersionFromTheSlotLsb() {
            QueueMetadata metadata = QueueMetadata.decode(channelKeys(), new byte[][] {
                channelSlot0(1L, 0), channelSlot1(0L), bytes(32, 1), bytes(32, 2), manifestSlot(0x00FF00FF00L)
            });

            assertThat(metadata.endpointManifestVersion()).isEqualTo(0x00FF00FF00L);
        }

        @Test
        void rejectsNullSlotValues() {
            assertThatThrownBy(() -> QueueMetadata.decode(channelKeys(), null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("provenSlotValues");
        }

        @Test
        void rejectsNullSlotKeys() {
            assertThatThrownBy(() -> QueueMetadata.decode(null, new byte[][] {bytes(32, 1)}))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("slotKeys");
        }

        @Test
        void rejectsWrongSlotCount() {
            assertThatThrownBy(() -> QueueMetadata.decode(
                            new byte[][] {key(1L), key(2L)}, new byte[][] {bytes(32, 1), bytes(32, 2)}))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("expected 5 or 6 proven slot values, got 2");
        }

        @Test
        void rejectsSlotThatIsNot32Bytes() {
            assertThatThrownBy(() -> QueueMetadata.decode(channelKeysWithMessage(), new byte[][] {
                        channelSlot0(0L, 0),
                        channelSlot1(0L),
                        bytes(32, 1),
                        bytes(32, 2),
                        manifestSlot(0L),
                        bytes(16, 3)
                    }))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("lastMessageRunningHash must be 32 bytes, got 16");
        }
    }

    // ── helpers ──

    /** An arbitrary 32-byte-slot base at which the Channel struct's {@code _channels[connId]} entry sits. */
    private static final long BASE = 0x1000L;

    /** The five ascending Channel slot keys {@code base+{1,2,4,5,16}} for an ACK-only bundle. */
    private static byte[][] channelKeys() {
        return new byte[][] {key(BASE + 1), key(BASE + 2), key(BASE + 4), key(BASE + 5), key(BASE + 16)};
    }

    /** The five Channel slot keys plus a far outlier (the last-message running-hash slot), ascending. */
    private static byte[][] channelKeysWithMessage() {
        return new byte[][] {
            key(BASE + 1), key(BASE + 2), key(BASE + 4), key(BASE + 5), key(BASE + 16), key(BASE + 0x1000)
        };
    }

    /** 32-byte big-endian encoding of a storage-slot key. */
    private static byte[] key(long slot) {
        final byte[] out = new byte[32];
        final byte[] v = BigInteger.valueOf(slot).toByteArray();
        System.arraycopy(v, 0, out, out.length - v.length, v.length);
        return out;
    }

    /** Slot +1 layout (MSB→LSB): 3B padding | 8B nextMessageId | 1B status | 20B verifier. */
    private static byte[] channelSlot0(long nextMessageId, int status) {
        byte[] slot = new byte[32];
        putUint64(slot, 3, nextMessageId);
        slot[11] = (byte) status;
        return slot;
    }

    /** Slot +2 layout (MSB→LSB): 8B padding | 8B nextExpectedReplyId | 8B receivedMessageId | 8B ackedMessageId. */
    private static byte[] channelSlot1(long receivedMessageId) {
        byte[] slot = new byte[32];
        putUint64(slot, 16, receivedMessageId);
        return slot;
    }

    /** Slot +16 layout: a lone uint64 at the LSB (last 8 bytes). */
    private static byte[] manifestSlot(long version) {
        byte[] slot = new byte[32];
        putUint64(slot, 24, version);
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
