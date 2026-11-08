// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier;

import com.hedera.node.app.hapi.utils.MiscCryptoUtils;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.util.Arrays;

/** Binds EVM queue proofs to the channel and service selected by the local Channel record. */
public final class EvmChannelContext {
    private static final int[] CHANNEL_OFFSETS = {1, 2, 4, 5, 16};
    private static final BigInteger UINT256_MODULUS = BigInteger.ONE.shiftLeft(256);

    private EvmChannelContext() {}

    /** Decodes {@code channelId32 || serviceAddress20}, checking the service against the trusted anchor. */
    @NonNull
    public static byte[] channelId(@NonNull final byte[] context, @NonNull final byte[] trustedServiceAddress) {
        if (context.length != 52) {
            throw fail("channelContext must contain a 32-byte channel ID and 20-byte service address");
        }
        if (trustedServiceAddress.length != 20 || !Arrays.equals(context, 32, 52, trustedServiceAddress, 0, 20)) {
            throw fail("channelContext service address does not match the trusted service");
        }
        return Arrays.copyOf(context, 32);
    }

    /**
     * Orders authenticated slot values by exact canonical keys. The optional sixth slot must be the
     * running hash of this channel's last queued message, derived from the proven next-message ID.
     */
    @NonNull
    public static byte[][] orderQueueSlots(
            @NonNull final byte[][] keys, @NonNull final byte[][] values, @NonNull final byte[] channelId) {
        if (channelId.length != 32 || keys.length != values.length || (keys.length != 5 && keys.length != 6)) {
            throw fail("invalid channel queue storage proof shape");
        }
        final byte[] channelBase = mappingBase(channelId, 15);
        final byte[][] ordered = new byte[keys.length][];
        int messageIndex = -1;
        for (int i = 0; i < keys.length; i++) {
            if (keys[i].length != 32 || values[i].length != 32) {
                throw fail("queue storage keys and values must be 32 bytes");
            }
            int index = -1;
            for (int j = 0; j < CHANNEL_OFFSETS.length; j++) {
                if (Arrays.equals(keys[i], add(channelBase, CHANNEL_OFFSETS[j]))) {
                    index = j;
                    break;
                }
            }
            if (index < 0) {
                if (messageIndex >= 0 || keys.length != 6) {
                    throw fail("queue proof contains an unexpected storage slot for this channel");
                }
                messageIndex = i;
            } else {
                if (ordered[index] != null) {
                    throw fail("queue proof contains a duplicate Channel storage slot");
                }
                ordered[index] = values[i];
            }
        }
        for (int i = 0; i < CHANNEL_OFFSETS.length; i++) {
            if (ordered[i] == null) {
                throw fail("queue proof is missing a required Channel storage slot");
            }
        }
        if (keys.length == 6) {
            if (messageIndex < 0) {
                throw fail("queue proof is missing the last-message running-hash slot");
            }
            // Solidity packs nextMessageId into bytes 3..10 of Channel slot +1.
            final BigInteger nextMessageId = new BigInteger(1, Arrays.copyOfRange(ordered[0], 3, 11));
            if (nextMessageId.signum() == 0) {
                throw fail("a message proof requires a positive nextMessageId");
            }
            final byte[] preimage = new byte[64];
            System.arraycopy(uint256(nextMessageId.subtract(BigInteger.ONE)), 0, preimage, 0, 32);
            System.arraycopy(mappingBase(channelId, 1), 0, preimage, 32, 32);
            final byte[] messageHashSlot = add(MiscCryptoUtils.keccak256DigestOf(preimage), 1);
            if (!Arrays.equals(keys[messageIndex], messageHashSlot)) {
                throw fail("last-message running-hash slot does not belong to this channel and message");
            }
            ordered[5] = values[messageIndex];
        }
        return ordered;
    }

    private static byte[] mappingBase(final byte[] channelId, final int slot) {
        final byte[] preimage = new byte[64];
        System.arraycopy(channelId, 0, preimage, 0, 32);
        preimage[63] = (byte) slot;
        return MiscCryptoUtils.keccak256DigestOf(preimage);
    }

    private static byte[] add(final byte[] base, final int offset) {
        return uint256(new BigInteger(1, base).add(BigInteger.valueOf(offset)).mod(UINT256_MODULUS));
    }

    private static byte[] uint256(final BigInteger value) {
        final byte[] raw = value.toByteArray();
        final byte[] out = new byte[32];
        final int count = Math.min(raw.length, 32);
        System.arraycopy(raw, raw.length - count, out, 32 - count, count);
        return out;
    }

    private static ProofException fail(final String message) {
        return new ProofException("EvmChannelContext", message);
    }
}
