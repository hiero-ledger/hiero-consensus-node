// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.evm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.node.app.service.clpr.impl.verifier.ProofException;
import com.hedera.node.app.service.clpr.impl.verifier.ProvenQueueMetadata;
import java.nio.ByteBuffer;
import java.util.HexFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ClprContractStorageTest {

    private static final HexFormat HEX = HexFormat.of();

    private static final byte[] CHANNEL_ID =
            HEX.parseHex("3c5b7a99b8d7f61534537291b0cfee0d2c4b6a89a8c7e60524436281a0bfdefd");

    // The Channel slot keys of CHANNEL_ID, from foundry: cast keccak $(cast abi-encode "f(bytes32,uint256)"
    // <channelId> 15), plus the offsets 1, 2, 4, 5 and 16.
    private static final byte[] STATUS_AND_NEXT_MESSAGE_ID_SLOT =
            HEX.parseHex("44637163277d9a932c1ffaff31b12f7a1ef43c674d0f3387a8f8e6864b1628f4");
    private static final byte[] RECEIVED_MESSAGE_ID_SLOT =
            HEX.parseHex("44637163277d9a932c1ffaff31b12f7a1ef43c674d0f3387a8f8e6864b1628f5");
    private static final byte[] SENT_RUNNING_HASH_SLOT =
            HEX.parseHex("44637163277d9a932c1ffaff31b12f7a1ef43c674d0f3387a8f8e6864b1628f7");
    private static final byte[] RECEIVED_RUNNING_HASH_SLOT =
            HEX.parseHex("44637163277d9a932c1ffaff31b12f7a1ef43c674d0f3387a8f8e6864b1628f8");
    private static final byte[] ENDPOINT_MANIFEST_VERSION_SLOT =
            HEX.parseHex("44637163277d9a932c1ffaff31b12f7a1ef43c674d0f3387a8f8e6864b162903");

    /** A slot outside the Channel struct, standing for the last message's running-hash slot. */
    private static final byte[] MESSAGE_SLOT =
            HEX.parseHex("0000000000000000000000000000000000000000000000000000000000000042");

    @Nested
    class AddProvenSlot {

        @Test
        @DisplayName("given a slot added twice, then addProvenSlot rejects the second one")
        void givenSlotAddedTwice_thenThrowsProofException() {
            final ClprContractStorage storage = new ClprContractStorage();
            storage.addProvenSlot(MESSAGE_SLOT, bytes(32, 1));

            assertThatThrownBy(() -> storage.addProvenSlot(MESSAGE_SLOT, bytes(32, 2)))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("duplicate proven slot 0x" + HEX.formatHex(MESSAGE_SLOT));
        }

        @Test
        @DisplayName("given a key shorter than 32 bytes, then addProvenSlot left-pads it to 32 bytes")
        void givenShortKey_thenLeftPadsIt() {
            final ClprContractStorage storage = new ClprContractStorage();
            storage.addProvenSlot(new byte[] {0x42}, bytes(32, 1));

            // The padded key is the same slot as MESSAGE_SLOT, so adding it again is a duplicate.
            assertThatThrownBy(() -> storage.addProvenSlot(MESSAGE_SLOT, bytes(32, 2)))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("duplicate proven slot");
        }

        @Test
        @DisplayName("given a 33-byte key, then addProvenSlot rejects it")
        void givenKeyLongerThan32Bytes_thenThrowsProofException() {
            final ClprContractStorage storage = new ClprContractStorage();

            assertThatThrownBy(() -> storage.addProvenSlot(new byte[33], bytes(32, 1)))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("slotKey is longer than 32 bytes (33)");
        }

        @Test
        @DisplayName("given the value changed after it was added, then the storage keeps the added value")
        void givenValueChangedAfterAdding_thenKeepsAddedValue() {
            final byte[] last = bytes(32, 0x60);
            final ClprContractStorage storage = defaultStorage();
            storage.addProvenSlot(MESSAGE_SLOT, last);
            final byte[] added = last.clone();

            last[0] ^= 1;

            assertThat(storage.decodeQueueMetadata(CHANNEL_ID).lastMessageRunningHash())
                    .isEqualTo(added);
        }
    }

    @Nested
    class DecodeQueueMetadata {

        @Test
        @DisplayName(
                "given the channel's slots under the keys derived from its id, then decodeQueueMetadata reads every field")
        void givenChannelSlotsAtDerivedKeys_thenDecodesAllFields() {
            final byte[] sent = bytes(32, 0x40);
            final byte[] received = bytes(32, 0x50);
            final ClprContractStorage storage = storage(
                    channelSlot0(0x0102030405060708L, 3),
                    channelSlot1(0x1112131415161718L),
                    sent,
                    received,
                    manifestSlot(42L));

            final ProvenQueueMetadata metadata = storage.decodeQueueMetadata(CHANNEL_ID);

            assertThat(metadata.nextMessageId()).isEqualTo(0x0102030405060708L);
            assertThat(metadata.status()).isEqualTo(3);
            assertThat(metadata.receivedMessageId()).isEqualTo(0x1112131415161718L);
            assertThat(metadata.sentRunningHash()).isEqualTo(sent);
            assertThat(metadata.receivedRunningHash()).isEqualTo(received);
            // proven from the offset-16 slot, never from relayed content
            assertThat(metadata.endpointManifestVersion()).isEqualTo(42L);
            assertThat(metadata.lastMessageRunningHash()).isEqualTo(new byte[32]);
        }

        @Test
        @DisplayName("given one slot besides the Channel slots, then decodeQueueMetadata takes it as the last message's"
                + " running hash")
        void givenOneOtherSlot_thenDecodesItAsLastMessageRunningHash() {
            final byte[] last = bytes(32, 0x60);
            final ClprContractStorage storage = defaultStorage();
            storage.addProvenSlot(MESSAGE_SLOT, last);

            final ProvenQueueMetadata metadata = storage.decodeQueueMetadata(CHANNEL_ID);

            assertThat(metadata.lastMessageRunningHash()).isEqualTo(last);
        }

        @Test
        @DisplayName(
                "given two slots besides the Channel slots, then decodeQueueMetadata takes the last one added as the"
                        + " last message's running hash")
        void givenTwoOtherSlots_thenDecodesLastAddedAsLastMessageRunningHash() {
            final byte[] lastAdded = bytes(32, 0x61);
            final ClprContractStorage storage = defaultStorage();
            storage.addProvenSlot(MESSAGE_SLOT, bytes(32, 0x60));
            storage.addProvenSlot(new byte[] {0x43}, lastAdded);

            final ProvenQueueMetadata metadata = storage.decodeQueueMetadata(CHANNEL_ID);

            assertThat(metadata.lastMessageRunningHash()).isEqualTo(lastAdded);
        }

        @Test
        @DisplayName("given no endpoint-manifest version slot, then decodeQueueMetadata rejects the storage")
        void givenMissingManifestVersionSlot_thenThrowsProofException() {
            final ClprContractStorage storage = new ClprContractStorage();
            storage.addProvenSlot(STATUS_AND_NEXT_MESSAGE_ID_SLOT, channelSlot0(1L, 3));
            storage.addProvenSlot(RECEIVED_MESSAGE_ID_SLOT, channelSlot1(0L));
            storage.addProvenSlot(SENT_RUNNING_HASH_SLOT, bytes(32, 1));
            storage.addProvenSlot(RECEIVED_RUNNING_HASH_SLOT, bytes(32, 2));

            assertThatThrownBy(() -> storage.decodeQueueMetadata(CHANNEL_ID))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("proven storage is missing the channel's ENDPOINT_MANIFEST_VERSION slot");
        }

        @Test
        @DisplayName("given the slots of another channel, then decodeQueueMetadata rejects them")
        void givenAnotherChannelId_thenThrowsProofException() {
            final byte[] otherChannelId = CHANNEL_ID.clone();
            otherChannelId[0] ^= 1;

            assertThatThrownBy(() -> defaultStorage().decodeQueueMetadata(otherChannelId))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("proven storage is missing the channel's STATUS_AND_NEXT_MESSAGE_ID slot");
        }

        @Test
        @DisplayName("given a status byte of 0xFF, then decodeQueueMetadata reads it unsigned")
        void givenStatusByteFF_thenDecodesItUnsigned() {
            final ClprContractStorage storage =
                    storage(channelSlot0(0L, 0xFF), channelSlot1(0L), bytes(32, 1), bytes(32, 2), manifestSlot(0L));

            assertThat(storage.decodeQueueMetadata(CHANNEL_ID).status()).isEqualTo(255);
        }

        @Test
        @DisplayName("given fully packed slots, then decodeQueueMetadata ignores the bytes outside the decoded fields")
        void givenFullyPackedSlots_thenIgnoresBytesOutsideDecodedFields() {
            // Only the nextMessageId/status/receivedMessageId windows are read; verifier/acked/reply bytes are not.
            final byte[] slot0 = bytes(32, 0x80);
            putUint64(slot0, 3, 7L);
            slot0[11] = 2;
            final byte[] slot1 = bytes(32, 0x90);
            putUint64(slot1, 16, 9L);

            final ProvenQueueMetadata metadata = storage(slot0, slot1, bytes(32, 1), bytes(32, 2), manifestSlot(0L))
                    .decodeQueueMetadata(CHANNEL_ID);

            assertThat(metadata.nextMessageId()).isEqualTo(7L);
            assertThat(metadata.status()).isEqualTo(2);
            assertThat(metadata.receivedMessageId()).isEqualTo(9L);
        }

        @Test
        @DisplayName("given a 31-byte channel id, then decodeQueueMetadata rejects it")
        void givenShortChannelId_thenThrowsIllegalArgumentException() {
            assertThatThrownBy(() -> defaultStorage().decodeQueueMetadata(new byte[31]))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("channelId must be 32 bytes, got 31");
        }

        @Test
        @DisplayName("given a null channel id, then decodeQueueMetadata throws NullPointerException")
        void givenNullChannelId_thenThrowsNullPointerException() {
            assertThatThrownBy(() -> defaultStorage().decodeQueueMetadata(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("channelId");
        }
    }

    // ── helpers ──

    private static ClprContractStorage defaultStorage() {
        return storage(channelSlot0(1L, 3), channelSlot1(0L), bytes(32, 1), bytes(32, 2), manifestSlot(1L));
    }

    /** A storage holding the CHANNEL_ID's Channel slots, under their derived slot keys. */
    private static ClprContractStorage storage(
            final byte[] statusAndNextMessageId,
            final byte[] receivedMessageId,
            final byte[] sentRunningHash,
            final byte[] receivedRunningHash,
            final byte[] endpointManifestVersion) {
        final ClprContractStorage storage = new ClprContractStorage();
        storage.addProvenSlot(STATUS_AND_NEXT_MESSAGE_ID_SLOT, statusAndNextMessageId);
        storage.addProvenSlot(RECEIVED_MESSAGE_ID_SLOT, receivedMessageId);
        storage.addProvenSlot(SENT_RUNNING_HASH_SLOT, sentRunningHash);
        storage.addProvenSlot(RECEIVED_RUNNING_HASH_SLOT, receivedRunningHash);
        storage.addProvenSlot(ENDPOINT_MANIFEST_VERSION_SLOT, endpointManifestVersion);
        return storage;
    }

    /** Slot +1 layout (MSB→LSB): 3B padding | 8B nextMessageId | 1B status | 20B verifier. */
    private static byte[] channelSlot0(final long nextMessageId, final int status) {
        final byte[] slot = new byte[32];
        putUint64(slot, 3, nextMessageId);
        slot[11] = (byte) status;
        return slot;
    }

    /** Slot +2 layout (MSB→LSB): 8B padding | 8B nextExpectedReplyId | 8B receivedMessageId | 8B ackedMessageId. */
    private static byte[] channelSlot1(final long receivedMessageId) {
        final byte[] slot = new byte[32];
        putUint64(slot, 16, receivedMessageId);
        return slot;
    }

    /** Slot +16 layout: a lone uint64 at the LSB (last 8 bytes). */
    private static byte[] manifestSlot(final long version) {
        final byte[] slot = new byte[32];
        putUint64(slot, 24, version);
        return slot;
    }

    private static void putUint64(final byte[] slot, final int offset, final long value) {
        ByteBuffer.wrap(slot, offset, 8).putLong(value);
    }

    private static byte[] bytes(final int length, final int seed) {
        final byte[] out = new byte[length];
        for (int i = 0; i < length; i++) {
            out[i] = (byte) (seed + i * 31);
        }
        return out;
    }
}
