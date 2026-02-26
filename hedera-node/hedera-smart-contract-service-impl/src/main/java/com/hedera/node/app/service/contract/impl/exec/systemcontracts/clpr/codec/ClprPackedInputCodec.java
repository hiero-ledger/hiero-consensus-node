// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.codec;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.ByteBuffer;

/** Utility for packed CLPR node-internal calldata parsing and basic byte helpers. */
public final class ClprPackedInputCodec {
    public static final int SELECTOR_LENGTH = 4;
    public static final int LEDGER_ID_LENGTH = 32;

    private static final int INBOUND_MESSAGE_ID_LENGTH = Long.BYTES;
    private static final int LENGTH_FIELD_SIZE = Integer.BYTES;
    private static final int MIN_PACKED_REQUEST_LENGTH =
            SELECTOR_LENGTH + LEDGER_ID_LENGTH + INBOUND_MESSAGE_ID_LENGTH + LENGTH_FIELD_SIZE;
    private static final int MIN_PACKED_REPLY_LENGTH = SELECTOR_LENGTH + LENGTH_FIELD_SIZE;

    private ClprPackedInputCodec() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static @NonNull PackedInboundRequest decodeDeliverInboundMessagePacked(@NonNull final byte[] input) {
        requireNonNull(input);
        if (input.length < MIN_PACKED_REQUEST_LENGTH) {
            throw new IllegalArgumentException("input too short");
        }

        int offset = SELECTOR_LENGTH;
        final var sourceLedgerId = slice(input, offset, LEDGER_ID_LENGTH);
        offset += LEDGER_ID_LENGTH;

        final long inboundMessageId =
                ByteBuffer.wrap(input, offset, INBOUND_MESSAGE_ID_LENGTH).getLong();
        offset += INBOUND_MESSAGE_ID_LENGTH;

        final int messageDataLength =
                ByteBuffer.wrap(input, offset, LENGTH_FIELD_SIZE).getInt();
        offset += LENGTH_FIELD_SIZE;

        final long expectedSize = (long) offset + messageDataLength;
        if (messageDataLength < 0 || expectedSize != input.length) {
            throw new IllegalArgumentException("malformed packed request");
        }

        final var messageData = slice(input, offset, messageDataLength);
        return new PackedInboundRequest(sourceLedgerId, inboundMessageId, messageData);
    }

    public static @NonNull byte[] decodeDeliverInboundMessageReplyPacked(@NonNull final byte[] input) {
        requireNonNull(input);
        if (input.length < MIN_PACKED_REPLY_LENGTH) {
            throw new IllegalArgumentException("input too short");
        }

        final int dataLength =
                ByteBuffer.wrap(input, SELECTOR_LENGTH, LENGTH_FIELD_SIZE).getInt();
        final int payloadOffset = SELECTOR_LENGTH + LENGTH_FIELD_SIZE;
        final long expectedLength = (long) payloadOffset + dataLength;
        if (dataLength < 0 || expectedLength != input.length) {
            throw new IllegalArgumentException("malformed packed response");
        }

        return slice(input, payloadOffset, dataLength);
    }

    public static @NonNull byte[] prependSelector(@NonNull final byte[] selector, @NonNull final byte[] arguments) {
        requireNonNull(selector);
        requireNonNull(arguments);
        final var out = new byte[selector.length + arguments.length];
        System.arraycopy(selector, 0, out, 0, selector.length);
        System.arraycopy(arguments, 0, out, selector.length, arguments.length);
        return out;
    }

    public static @NonNull byte[] stripSelector(@NonNull final byte[] callData) {
        requireNonNull(callData);
        if (callData.length <= SELECTOR_LENGTH) {
            throw new IllegalArgumentException("missing function arguments");
        }
        final var out = new byte[callData.length - SELECTOR_LENGTH];
        System.arraycopy(callData, SELECTOR_LENGTH, out, 0, out.length);
        return out;
    }

    public static @NonNull byte[] toArray(@NonNull final ByteBuffer byteBuffer) {
        requireNonNull(byteBuffer);
        final var out = new byte[byteBuffer.remaining()];
        byteBuffer.get(out);
        return out;
    }

    public static @NonNull byte[] slice(@NonNull final byte[] input, final int offset, final int length) {
        requireNonNull(input);
        final var out = new byte[length];
        System.arraycopy(input, offset, out, 0, length);
        return out;
    }

    public static boolean isAllZero(@NonNull final byte[] bytes) {
        requireNonNull(bytes);
        for (final byte value : bytes) {
            if (value != 0) {
                return false;
            }
        }
        return true;
    }

    public record PackedInboundRequest(byte[] sourceLedgerId, long inboundMessageId, byte[] messageData) {}
}
