// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.clpr.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.codec.ClprPackedInputCodec;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

class ClprPackedInputCodecTest {
    private static final byte[] SELECTOR = new byte[] {0x01, 0x02, 0x03, 0x04};

    @Test
    void decodesInboundRequestPackedPayload() {
        final var sourceLedgerId = new byte[ClprPackedInputCodec.LEDGER_ID_LENGTH];
        sourceLedgerId[sourceLedgerId.length - 1] = 0x0F;
        final long inboundMessageId = 123L;
        final var payload = new byte[] {0x11, 0x22, 0x33};

        final var packed = ByteBuffer.allocate(
                        SELECTOR.length + sourceLedgerId.length + Long.BYTES + Integer.BYTES + payload.length)
                .put(SELECTOR)
                .put(sourceLedgerId)
                .putLong(inboundMessageId)
                .putInt(payload.length)
                .put(payload)
                .array();

        final var decoded = ClprPackedInputCodec.decodeDeliverInboundMessagePacked(packed);
        assertThat(decoded.sourceLedgerId()).isEqualTo(sourceLedgerId);
        assertThat(decoded.inboundMessageId()).isEqualTo(inboundMessageId);
        assertThat(decoded.messageData()).isEqualTo(payload);
    }

    @Test
    void decodesInboundRequestWithEmptyPayload() {
        final var sourceLedgerId = new byte[ClprPackedInputCodec.LEDGER_ID_LENGTH];
        sourceLedgerId[0] = 0x01;

        final var packed = ByteBuffer.allocate(SELECTOR.length + sourceLedgerId.length + Long.BYTES + Integer.BYTES)
                .put(SELECTOR)
                .put(sourceLedgerId)
                .putLong(1L)
                .putInt(0)
                .array();

        final var decoded = ClprPackedInputCodec.decodeDeliverInboundMessagePacked(packed);
        assertThat(decoded.messageData()).isEmpty();
    }

    @Test
    void rejectsMalformedInboundRequestLength() {
        final var sourceLedgerId = new byte[ClprPackedInputCodec.LEDGER_ID_LENGTH];
        final var packed = ByteBuffer.allocate(SELECTOR.length + sourceLedgerId.length + Long.BYTES + Integer.BYTES + 2)
                .put(SELECTOR)
                .put(sourceLedgerId)
                .putLong(7L)
                .putInt(10)
                .put(new byte[] {0x01, 0x02})
                .array();

        assertThatThrownBy(() -> ClprPackedInputCodec.decodeDeliverInboundMessagePacked(packed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("malformed packed request");
    }

    @Test
    void decodesInboundReplyPackedPayload() {
        final var payload = new byte[] {0x44, 0x55};
        final var packed = ByteBuffer.allocate(SELECTOR.length + Integer.BYTES + payload.length)
                .put(SELECTOR)
                .putInt(payload.length)
                .put(payload)
                .array();

        final var decoded = ClprPackedInputCodec.decodeDeliverInboundMessageReplyPacked(packed);
        assertThat(decoded).isEqualTo(payload);
    }

    @Test
    void rejectsMalformedInboundReplyLength() {
        final var packed = ByteBuffer.allocate(SELECTOR.length + Integer.BYTES + 1)
                .put(SELECTOR)
                .putInt(3)
                .put((byte) 0x01)
                .array();

        assertThatThrownBy(() -> ClprPackedInputCodec.decodeDeliverInboundMessageReplyPacked(packed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("malformed packed response");
    }
}
