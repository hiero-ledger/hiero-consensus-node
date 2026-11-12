// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.node.app.hapi.utils.MiscCryptoUtils;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link EvmChannelContext}. The fixtures build the canonical CLPR storage keys of {@link #CHANNEL}
 * independently of the class under test, with {@code nextMessageId = 42}, so the only valid message slot is the running
 * hash of message 41.
 */
class EvmChannelContextTest {
    /** All-zero channel ID; the tests derive "another channel" by flipping its first byte. */
    private static final byte[] CHANNEL = new byte[32];

    private static final byte[] SERVICE = new byte[20];
    /** Channel struct field offsets, in queue-metadata order. */
    private static final int[] OFFSETS = {1, 2, 4, 5, 16};

    @Test
    void requiresFullContextAndMatchingService() {
        assertThat(EvmChannelContext.channelId(new byte[52], SERVICE)).isEqualTo(CHANNEL);
        // Anything other than exactly 32 + 20 bytes is rejected, including a bare channel ID.
        for (final int length : new int[] {0, 31, 32, 51, 53}) {
            assertThatThrownBy(() -> EvmChannelContext.channelId(new byte[length], SERVICE))
                    .isInstanceOf(ProofException.class);
        }
        final byte[] differentService = SERVICE.clone();
        differentService[0] = 1;
        assertThatThrownBy(() -> EvmChannelContext.channelId(new byte[52], differentService))
                .isInstanceOf(ProofException.class)
                .hasMessageContaining("service address");
    }

    @Test
    void bindsAckOnlyProofToExactChannelKeysRegardlessOfOrder() {
        final byte[][] keys = keys(CHANNEL, false);
        final byte[][] values = values(false);
        // Proof order must not matter: move the status slot to the end and expect it back at index 0.
        swap(keys, 0, 4);
        swap(values, 0, 4);
        final var ordered = EvmChannelContext.orderQueueSlots(keys, values, CHANNEL);
        assertThat(ByteBuffer.wrap(ordered[0], 3, 8).getLong()).isEqualTo(42L);
        assertThat(ordered[4][0]).isEqualTo((byte) 5);

        // The same authenticated keys and values do not satisfy another channel's canonical keys.
        final byte[] otherChannel = CHANNEL.clone();
        otherChannel[0] = 1;
        assertThatThrownBy(() -> EvmChannelContext.orderQueueSlots(keys, values, otherChannel))
                .isInstanceOf(ProofException.class);
    }

    @Test
    void rejectsDuplicateAndMissingChannelSlots() {
        // Replacing the offset-16 key with a second offset-1 key leaves one slot duplicated and one missing; the
        // duplicate is reported first.
        final byte[][] keys = keys(CHANNEL, false);
        keys[4] = keys[0];
        assertThatThrownBy(() -> EvmChannelContext.orderQueueSlots(keys, values(false), CHANNEL))
                .isInstanceOf(ProofException.class)
                .hasMessageContaining("duplicate");
    }

    @Test
    void bindsLastMessageSlotToChannelAndProvenCounter() {
        final byte[][] keys = keys(CHANNEL, true);
        final byte[][] values = values(true);
        assertThat(EvmChannelContext.orderQueueSlots(keys, values, CHANNEL)[5]).isEqualTo(values[5]);
        // Any other sixth key, such as an older message's slot or another channel's, must be rejected.
        keys[5][0] ^= 1;
        assertThatThrownBy(() -> EvmChannelContext.orderQueueSlots(keys, values, CHANNEL))
                .isInstanceOf(ProofException.class)
                .hasMessageContaining("last-message");
    }

    @Test
    void rejectsMessageProofBeforeAnyMessageIsSent() {
        // Message IDs start at 1, so with nextMessageId 0 or 1 there is no last message for the sixth slot to
        // belong to; with 1 it would name message 0, which is never written.
        for (final long nextMessageId : new long[] {0, 1}) {
            final byte[][] values = values(true);
            Arrays.fill(values[0], (byte) 0);
            ByteBuffer.wrap(values[0], 3, 8).putLong(nextMessageId);
            assertThatThrownBy(() -> EvmChannelContext.orderQueueSlots(keys(CHANNEL, true), values, CHANNEL))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("nextMessageId > 1");
        }
    }

    /**
     * The canonical keys of {@code channel}: {@code keccak256(channel || uint256(15)) + OFFSETS[i]}, plus (when
     * {@code message}) the running-hash slot of message 41, {@code keccak256(uint256(41) || keccak256(channel ||
     * uint256(1))) + 1}.
     */
    private static byte[][] keys(final byte[] channel, final boolean message) {
        final byte[] preimage = new byte[64];
        System.arraycopy(channel, 0, preimage, 0, 32);
        preimage[63] = 15;
        final var base = new BigInteger(1, MiscCryptoUtils.keccak256DigestOf(preimage));
        final byte[][] keys = new byte[message ? 6 : 5][];
        for (int i = 0; i < 5; i++) {
            keys[i] = word(base.add(BigInteger.valueOf(OFFSETS[i])));
        }
        if (message) {
            preimage[63] = 1;
            final byte[] queueBase = MiscCryptoUtils.keccak256DigestOf(preimage);
            final byte[] inner = new byte[64];
            inner[31] = 41; // last message = nextMessageId (42, see values()) - 1
            System.arraycopy(queueBase, 0, inner, 32, 32);
            keys[5] = word(new BigInteger(1, MiscCryptoUtils.keccak256DigestOf(inner)).add(BigInteger.ONE));
        }
        return keys;
    }

    /** Slot values: {@code nextMessageId = 42} in the status slot and a distinct marker byte in each other slot. */
    private static byte[][] values(final boolean message) {
        final byte[][] values = new byte[message ? 6 : 5][32];
        ByteBuffer.wrap(values[0], 3, 8).putLong(42L);
        for (int i = 1; i < values.length; i++) {
            values[i][0] = (byte) (i + 1);
        }
        return values;
    }

    /** {@code n} as a 32-byte big-endian word, dropping any sign byte from {@link BigInteger#toByteArray()}. */
    private static byte[] word(final BigInteger n) {
        final byte[] raw = n.toByteArray();
        final byte[] padded = new byte[32];
        System.arraycopy(
                raw, Math.max(0, raw.length - 32), padded, Math.max(0, 32 - raw.length), Math.min(32, raw.length));
        return padded;
    }

    private static void swap(final byte[][] array, final int a, final int b) {
        final byte[] value = array[a];
        array[a] = array[b];
        array[b] = value;
    }
}
