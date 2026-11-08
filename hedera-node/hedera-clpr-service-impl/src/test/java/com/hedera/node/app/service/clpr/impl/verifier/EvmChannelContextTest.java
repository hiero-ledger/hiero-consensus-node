// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.node.app.hapi.utils.MiscCryptoUtils;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class EvmChannelContextTest {
    private static final byte[] CHANNEL = new byte[32];
    private static final byte[] SERVICE = new byte[20];
    private static final int[] OFFSETS = {1, 2, 4, 5, 16};

    @Test
    void requiresFullContextAndMatchingService() {
        assertThat(EvmChannelContext.channelId(new byte[52], SERVICE)).isEqualTo(CHANNEL);
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
        swap(keys, 0, 4);
        swap(values, 0, 4);
        final var ordered = EvmChannelContext.orderQueueSlots(keys, values, CHANNEL);
        assertThat(ByteBuffer.wrap(ordered[0], 3, 8).getLong()).isEqualTo(42L);
        assertThat(ordered[4][0]).isEqualTo((byte) 5);

        final byte[] otherChannel = CHANNEL.clone();
        otherChannel[0] = 1;
        assertThatThrownBy(() -> EvmChannelContext.orderQueueSlots(keys, values, otherChannel))
                .isInstanceOf(ProofException.class);
    }

    @Test
    void rejectsDuplicateAndMissingChannelSlots() {
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
        keys[5][0] ^= 1;
        assertThatThrownBy(() -> EvmChannelContext.orderQueueSlots(keys, values, CHANNEL))
                .isInstanceOf(ProofException.class)
                .hasMessageContaining("last-message");
    }

    @Test
    void rejectsMessageProofWithZeroCounter() {
        final byte[][] values = values(true);
        Arrays.fill(values[0], (byte) 0);
        assertThatThrownBy(() -> EvmChannelContext.orderQueueSlots(keys(CHANNEL, true), values, CHANNEL))
                .isInstanceOf(ProofException.class)
                .hasMessageContaining("positive nextMessageId");
    }

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
            inner[31] = 41;
            System.arraycopy(queueBase, 0, inner, 32, 32);
            keys[5] = word(new BigInteger(1, MiscCryptoUtils.keccak256DigestOf(inner)).add(BigInteger.ONE));
        }
        return keys;
    }

    private static byte[][] values(final boolean message) {
        final byte[][] values = new byte[message ? 6 : 5][32];
        ByteBuffer.wrap(values[0], 3, 8).putLong(42L);
        for (int i = 1; i < values.length; i++) {
            values[i][0] = (byte) (i + 1);
        }
        return values;
    }

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
