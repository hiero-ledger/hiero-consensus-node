// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier;

import com.hedera.node.app.hapi.utils.MiscCryptoUtils;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.util.Arrays;

/**
 * Binds EVM queue proofs to the channel and service selected by the local Channel record.
 *
 * <p>A state proof only shows that some storage slots of the peer's CLPR service contract hold some values. On its
 * own it does not say which channel those slots belong to, so a valid proof for channel A could be replayed as a
 * bundle for channel B whenever the counters and running hashes happen to match. The QBFT, Ethereum, and Sei
 * verifiers close that gap with this class: every proven storage key must equal a key recomputed from the
 * {@code channelId} in the local Channel record's {@code channelContext}.
 *
 * <p>The CLPR service contract stores its Channel structs in {@code mapping(bytes32 => Channel) _channels} at
 * storage slot 15, and its messages in a nested mapping keyed by channel ID and then message ID at storage slot 1.
 * Solidity places these entries at:
 * <pre>
 *   Channel base       = keccak256(channelId32 || uint256(15))
 *   Channel field slot = Channel base + offset, for offset in {1, 2, 4, 5, 16}
 *   message entry      = keccak256(uint256(messageId) || keccak256(channelId32 || uint256(1)))
 *   running-hash slot  = message entry + 1
 * </pre>
 */
public final class EvmChannelContext {
    /** Length of the channel ID that opens a {@code channelContext}. */
    private static final int CHANNEL_ID_LENGTH = 32;

    /** Length of the peer service address that closes a {@code channelContext}. */
    private static final int SERVICE_ADDRESS_LENGTH = 20;

    /** Length of {@code channelId32 || serviceAddress20}. */
    private static final int CHANNEL_CONTEXT_LENGTH = CHANNEL_ID_LENGTH + SERVICE_ADDRESS_LENGTH;

    /** Storage slot of the {@code _channels} mapping in the CLPR service contract. */
    private static final int CHANNELS_MAPPING_SLOT = 15;

    /** Storage slot of the per-channel message mapping in the CLPR service contract. */
    private static final int MESSAGES_MAPPING_SLOT = 1;

    /** Offset of the running-hash field inside a stored message. */
    private static final int MESSAGE_RUNNING_HASH_OFFSET = 1;

    /**
     * Channel struct field offsets that every bundle must prove, in the positional order the queue-metadata decoders
     * expect: packed status and next-message ID, packed acked/received/next-reply IDs, sent running hash, received
     * running hash, and endpoint-manifest version.
     */
    private static final int[] CHANNEL_OFFSETS = {1, 2, 4, 5, 16};

    /** Position of the optional last-message running hash in the array returned by {@link #orderQueueSlots}. */
    private static final int LAST_MESSAGE_RUNNING_HASH_INDEX = CHANNEL_OFFSETS.length;

    /** Slot arithmetic wraps modulo 2^256, as it does in the EVM. */
    private static final BigInteger UINT256_MODULUS = BigInteger.ONE.shiftLeft(256);

    private EvmChannelContext() {}

    /**
     * Splits a {@code channelContext} into its channel ID and checks that its service address is the one pinned in
     * the trusted anchor. This stops a caller from pairing a trusted anchor for one service contract with a context
     * that names another.
     *
     * @param context the local Channel record's {@code channelId32 || serviceAddress20}
     * @param trustedServiceAddress the 20-byte service address from the verified trust anchor
     * @return a copy of the 32-byte channel ID
     * @throws ProofException if {@code context} is not 52 bytes, or its service address differs from
     *     {@code trustedServiceAddress}
     */
    @NonNull
    public static byte[] channelId(@NonNull final byte[] context, @NonNull final byte[] trustedServiceAddress) {
        if (context.length != CHANNEL_CONTEXT_LENGTH) {
            throw fail("channelContext must contain a 32-byte channel ID and 20-byte service address");
        }
        if (trustedServiceAddress.length != SERVICE_ADDRESS_LENGTH
                || !Arrays.equals(
                        context,
                        CHANNEL_ID_LENGTH,
                        CHANNEL_CONTEXT_LENGTH,
                        trustedServiceAddress,
                        0,
                        SERVICE_ADDRESS_LENGTH)) {
            throw fail("channelContext service address does not match the trusted service");
        }
        return Arrays.copyOf(context, CHANNEL_ID_LENGTH);
    }

    /**
     * Matches authenticated storage slots to the canonical keys of {@code channelId} and returns their values in
     * queue-metadata order. Proof order does not matter.
     *
     * <p>The five Channel slots must each appear exactly once. A sixth slot is allowed only when it is the running
     * hash of this channel's last queued message, that is message {@code nextMessageId - 1}, where
     * {@code nextMessageId} is read from the proven Channel slot rather than from the relayed bundle and must be at
     * least 2. Duplicate, missing, and unrelated keys are rejected.
     *
     * <p>Callers must have already verified each key/value pair against a trusted state root; this method only checks
     * which slots were proven.
     *
     * @param keys the proven 32-byte storage keys, in proof order
     * @param values the proven 32-byte storage values, aligned with {@code keys}
     * @param channelId the 32-byte channel ID from the local Channel record
     * @return 5 values (no message) or 6 values (last-message running hash appended), in the order of Channel
     *     offsets {@code {1, 2, 4, 5, 16}} followed by the optional last-message running hash
     * @throws ProofException if the proof shape is wrong or any key does not belong to this channel
     */
    @NonNull
    public static byte[][] orderQueueSlots(
            @NonNull final byte[][] keys, @NonNull final byte[][] values, @NonNull final byte[] channelId) {
        if (channelId.length != CHANNEL_ID_LENGTH
                || keys.length != values.length
                || (keys.length != CHANNEL_OFFSETS.length && keys.length != CHANNEL_OFFSETS.length + 1)) {
            throw fail("invalid channel queue storage proof shape");
        }
        final byte[] channelBase = mappingBase(channelId, CHANNELS_MAPPING_SLOT);
        final byte[][] ordered = new byte[keys.length][];
        // Proof position of the one key that is not a Channel slot; only allowed in a 6-slot proof.
        int messageIndex = -1;
        for (int i = 0; i < keys.length; i++) {
            if (keys[i].length != 32 || values[i].length != 32) {
                throw fail("queue storage keys and values must be 32 bytes");
            }
            // Find which Channel field, if any, this key is.
            int index = -1;
            for (int j = 0; j < CHANNEL_OFFSETS.length; j++) {
                if (Arrays.equals(keys[i], add(channelBase, CHANNEL_OFFSETS[j]))) {
                    index = j;
                    break;
                }
            }
            if (index < 0) {
                // Not a Channel slot. Hold it as the message slot candidate; it is checked below once the
                // proven nextMessageId is known. A second such key, or any such key in a 5-slot proof, fails.
                if (messageIndex >= 0 || keys.length != CHANNEL_OFFSETS.length + 1) {
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
        if (keys.length == CHANNEL_OFFSETS.length + 1) {
            if (messageIndex < 0) {
                throw fail("queue proof is missing the last-message running-hash slot");
            }
            // Solidity packs nextMessageId into bytes 3..10 of Channel slot +1
            // (3 bytes padding | 8 bytes nextMessageId | 1 byte status | 20 bytes verifier).
            final BigInteger nextMessageId = new BigInteger(1, Arrays.copyOfRange(ordered[0], 3, 11));
            // Message IDs start at 1 (a new channel has nextMessageId = 1), so a last message exists only once
            // nextMessageId reaches 2. Below that, the sixth slot would name message 0, which is never written.
            if (nextMessageId.compareTo(BigInteger.TWO) < 0) {
                throw fail("a message proof requires nextMessageId > 1 (message IDs start at 1)");
            }
            // The only acceptable message slot is the running hash of message (nextMessageId - 1) in this
            // channel's queue. Deriving it from the proven counter stops a relay from substituting the
            // running hash of an older message or of another channel's queue.
            final byte[] preimage = new byte[64];
            System.arraycopy(uint256(nextMessageId.subtract(BigInteger.ONE)), 0, preimage, 0, 32);
            System.arraycopy(mappingBase(channelId, MESSAGES_MAPPING_SLOT), 0, preimage, 32, 32);
            final byte[] messageHashSlot =
                    add(MiscCryptoUtils.keccak256DigestOf(preimage), MESSAGE_RUNNING_HASH_OFFSET);
            if (!Arrays.equals(keys[messageIndex], messageHashSlot)) {
                throw fail("last-message running-hash slot does not belong to this channel and message");
            }
            ordered[LAST_MESSAGE_RUNNING_HASH_INDEX] = values[messageIndex];
        }
        return ordered;
    }

    /**
     * Returns the Solidity storage slot of {@code mapping[channelId]} for a mapping declared at {@code slot}:
     * {@code keccak256(channelId32 || uint256(slot))}. {@code slot} must fit in one byte.
     */
    private static byte[] mappingBase(final byte[] channelId, final int slot) {
        final byte[] preimage = new byte[64];
        System.arraycopy(channelId, 0, preimage, 0, 32);
        preimage[63] = (byte) slot;
        return MiscCryptoUtils.keccak256DigestOf(preimage);
    }

    /** Returns {@code base + offset} as a 32-byte word, wrapping modulo 2^256 like EVM slot arithmetic. */
    private static byte[] add(final byte[] base, final int offset) {
        return uint256(new BigInteger(1, base).add(BigInteger.valueOf(offset)).mod(UINT256_MODULUS));
    }

    /**
     * Encodes a non-negative value below 2^256 as a 32-byte big-endian word. Copies at most the low 32 bytes, which
     * drops the extra sign byte that {@link BigInteger#toByteArray()} adds when the top bit is set.
     */
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
