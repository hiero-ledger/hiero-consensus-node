// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.evm;

import static com.hedera.node.app.service.clpr.impl.verifier.evm.ProofBytes.checkedCopy;
import static com.hedera.node.app.service.clpr.impl.verifier.evm.ProofBytes.leftPad32;

import com.hedera.node.app.hapi.utils.MiscCryptoUtils;
import com.hedera.node.app.service.clpr.impl.verifier.ProofException;
import com.hedera.node.app.service.clpr.impl.verifier.ProvenQueueMetadata;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The proven storage of a peer's ClprService contract, and the CLPR data decoded from it.
 *
 * <p>A verifier proves each storage slot against the contract account's storage root, adds it with
 * {@link #addProvenSlot}, then decodes the data it needs. This class checks no proof: it trusts every slot it is given,
 * so only slots the caller has verified may be added.
 *
 * <p>The contract lays the {@code Channel} struct out the same way on every EVM chain (clpr-smart-contracts
 * {@code storage-layout.json}), so this decoding is shared by the EVM verifiers; only proving the storage is
 * chain-specific.
 *
 * <p>Not thread-safe: fill and decode it within a single verification.
 */
public final class ClprContractStorage {

    private static final Logger logger = LogManager.getLogger(ClprContractStorage.class);

    /**
     * Number of proven slots for an ACK-only bundle: the Channel slots, no message running hash.
     */
    public static final int CHANNEL_SLOTS = ChannelSlot.values().length;

    /**
     * Number of proven slots for a message-bearing bundle: the Channel slots plus the last message's running hash.
     */
    public static final int EXPECTED_SLOTS_WITH_MESSAGE = CHANNEL_SLOTS + 1;

    private static final String SOURCE = "ClprContractStorage";

    private static final int SLOT_VALUE_LENGTH = 32;

    /**
     * Proven slot key (32 bytes) → value; insertion-ordered, so decoding never depends on hash order.
     */
    private final Map<Bytes, byte[]> provenSlots = new LinkedHashMap<>();

    /**
     * Adds a slot whose value the caller has proven against the contract account's storage root.
     *
     * @param slotKey the slot key, left-padded to 32 bytes if shorter
     * @param value32 the proven 32-byte value; 32 zero bytes for a slot proven absent
     * @throws ProofException if the key is longer than 32 bytes or the slot was already added
     */
    public void addProvenSlot(@NonNull final byte[] slotKey, @NonNull final byte[] value32) {
        Objects.requireNonNull(value32, "value32");
        final Bytes slotKey32 = Bytes.wrap(leftPad32(slotKey, "slotKey"));
        if (provenSlots.containsKey(slotKey32)) {
            throw new ProofException(
                    SOURCE, "duplicate proven slot 0x" + HexFormat.of().formatHex(slotKey32.toByteArray()));
        }
        provenSlots.put(slotKey32, value32.clone());
    }

    /**
     * Picks the Channel slots of {@code channelId32} out of the proven slots and decodes the channel's queue metadata
     * from them. The slot keys are derived from the channel id, never taken from the proof, so the decoded values are
     * bound to that channel.
     *
     * <p>Every Channel slot must be present. Any other slot is taken as the last message's running-hash slot, whose
     * value becomes {@link ProvenQueueMetadata#lastMessageRunningHash()}; without one, that hash is 32 zero bytes. The
     * caller bounds how many slots a bundle may prove, so it normally adds at most one other slot; if it adds more, the
     * last one added is used. Which message that slot belongs to is not checked here; callers bind it to this channel's
     * last queued message with {@code EvmChannelContext.orderQueueSlots} before decoding.
     *
     * <p>Solidity packs primitive fields starting at the LSB of a slot, so on a 32-byte big-endian storage word the
     * first-declared field sits at the right (high index of the byte array).
     *
     * @param channelId32 the 32-byte channel id
     * @throws ProofException           if a Channel slot is missing
     * @throws IllegalArgumentException if {@code channelId32} is not 32 bytes
     */
    @NonNull
    public ProvenQueueMetadata decodeQueueMetadata(@NonNull final byte[] channelId32) {
        final Map<ChannelSlot, Bytes> channelSlotKeys = ChannelSlot.keysOf(channelId32);
        final Map<ChannelSlot, byte[]> channelSlotValues = new EnumMap<>(ChannelSlot.class);
        for (final Map.Entry<ChannelSlot, Bytes> slot : channelSlotKeys.entrySet()) {
            final byte[] value = provenSlots.get(slot.getValue());
            if (value == null) {
                throw new ProofException(SOURCE, "proven storage is missing the channel's " + slot.getKey() + " slot");
            }
            channelSlotValues.put(slot.getKey(), value);
        }
        byte[] lastMessageRunningHash = null;
        for (final Map.Entry<Bytes, byte[]> slot : provenSlots.entrySet()) {
            if (!channelSlotKeys.containsValue(slot.getKey())) {
                lastMessageRunningHash = slot.getValue();
            }
        }
        if (lastMessageRunningHash == null) {
            logger.debug("ClprContractStorage: no last-message running-hash slot proven; using 32 zero bytes");
            lastMessageRunningHash = new byte[SLOT_VALUE_LENGTH];
        }

        // Slot +1: verifier(20) | status(1) | nextMessageId(8) — first declared field at LSB.
        // Byte layout (MSB→LSB): 3B padding | 8B nextMessageId | 1B status | 20B verifier.
        final byte[] statusSlot = checkedCopy(
                channelSlotValues.get(ChannelSlot.STATUS_AND_NEXT_MESSAGE_ID),
                SLOT_VALUE_LENGTH,
                "connStatusNextMsgIdSlot");
        final long nextMessageId = readUint64BigEndian(statusSlot, 3);
        final int status = statusSlot[11] & 0xFF;

        // Slot +2: ackedMessageId(8) | receivedMessageId(8) | nextExpectedReplyId(8) — first at LSB.
        // Byte layout (MSB→LSB): 8B padding | 8B nextExpectedReplyId | 8B receivedMessageId | 8B ackedMessageId.
        final byte[] receivedIdSlot = checkedCopy(
                channelSlotValues.get(ChannelSlot.RECEIVED_MESSAGE_ID), SLOT_VALUE_LENGTH, "connReceivedMsgIdSlot");
        final long receivedMessageId = readUint64BigEndian(receivedIdSlot, 16);

        // Slot +16: endpointManifestVersion (uint64) — a lone field in its own slot, LSB-packed (last 8 bytes).
        final byte[] manifestVersionSlot = checkedCopy(
                channelSlotValues.get(ChannelSlot.ENDPOINT_MANIFEST_VERSION),
                SLOT_VALUE_LENGTH,
                "endpointManifestVersionSlot");
        final long endpointManifestVersion = readUint64BigEndian(manifestVersionSlot, 24);

        return new ProvenQueueMetadata(
                nextMessageId,
                channelSlotValues.get(ChannelSlot.SENT_RUNNING_HASH),
                receivedMessageId,
                channelSlotValues.get(ChannelSlot.RECEIVED_RUNNING_HASH),
                status,
                lastMessageRunningHash,
                endpointManifestVersion);
    }

    /**
     * Reads 8 bytes from {@code buf} starting at {@code offset} as a big-endian unsigned long.
     */
    private static long readUint64BigEndian(final byte[] buf, final int offset) {
        return ByteBuffer.wrap(buf, offset, 8).getLong();
    }

    /**
     * The storage slots of a ClprService {@code Channel} struct that a bundle proves. Each constant is one 32-byte
     * slot, at its offset from the struct's base slot {@code keccak256(channelId ‖ uint256(15))}, where 15 is the slot
     * of the {@code _channels} mapping. Mirrors {@code ClprEvmBundleVerifier._channelMetadataSlots} in
     * clpr-smart-contracts.
     */
    private enum ChannelSlot {
        /**
         * {@code verifier | status | nextMessageId}, packed.
         */
        STATUS_AND_NEXT_MESSAGE_ID(1),
        /**
         * {@code ackedMessageId | receivedMessageId | nextExpectedReplyId}, packed.
         */
        RECEIVED_MESSAGE_ID(2),
        /**
         * {@code sentRunningHash}.
         */
        SENT_RUNNING_HASH(4),
        /**
         * {@code receivedRunningHash}.
         */
        RECEIVED_RUNNING_HASH(5),
        /**
         * {@code endpointManifestVersion}.
         */
        ENDPOINT_MANIFEST_VERSION(16);

        /**
         * Storage slot of the ClprService {@code _channels} mapping.
         */
        private static final byte CHANNELS_MAPPING_SLOT = 15;

        private static final int CHANNEL_ID_LENGTH = 32;

        private static final int SLOT_KEY_LENGTH = 32;

        /**
         * Storage slots wrap modulo 2^256, as in the EVM.
         */
        private static final BigInteger SLOT_SPACE = BigInteger.ONE.shiftLeft(256);

        private final int offset;

        ChannelSlot(final int offset) {
            this.offset = offset;
        }

        /**
         * Returns the 32-byte storage slot key of every Channel slot of {@code channelId32}, in declaration order.
         *
         * @throws IllegalArgumentException if {@code channelId32} is not 32 bytes
         */
        @NonNull
        static Map<ChannelSlot, Bytes> keysOf(@NonNull final byte[] channelId32) {
            final byte[] mappingKey = new byte[2 * CHANNEL_ID_LENGTH];
            System.arraycopy(
                    checkedCopy(channelId32, CHANNEL_ID_LENGTH, "channelId"), 0, mappingKey, 0, CHANNEL_ID_LENGTH);
            mappingKey[mappingKey.length - 1] = CHANNELS_MAPPING_SLOT;
            final BigInteger channelBase = new BigInteger(1, MiscCryptoUtils.keccak256DigestOf(mappingKey));
            final Map<ChannelSlot, Bytes> keys = new EnumMap<>(ChannelSlot.class);
            for (final ChannelSlot slot : values()) {
                keys.put(slot, Bytes.wrap(slot.keyFrom(channelBase)));
            }
            return keys;
        }

        /**
         * This slot's key: {@code channelBase + offset}, modulo 2^256, as 32 big-endian bytes.
         */
        @NonNull
        private byte[] keyFrom(@NonNull final BigInteger channelBase) {
            final byte[] key =
                    channelBase.add(BigInteger.valueOf(offset)).mod(SLOT_SPACE).toByteArray();
            // toByteArray() prepends a zero sign byte to values >= 2^255.
            return leftPad32(key.length > SLOT_KEY_LENGTH ? Arrays.copyOfRange(key, 1, key.length) : key, name());
        }
    }
}
