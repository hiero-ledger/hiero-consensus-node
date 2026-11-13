// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.canton;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.node.app.service.clpr.impl.verifier.canton.CantonRlp.Slice;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/** Tests for the strict RLP reader {@link CantonRlp}, which mirrors the reference's OpenZeppelin {@code RLP}. */
class CantonRlpTest {

    // ── isEmpty ──────────────────────────────────────────────────────────────

    @Test
    void isEmptyAcceptsEmptyStringAndEmptyList() {
        assertThat(CantonRlp.isEmpty(CantonRlp.of(new byte[] {(byte) 0x80}))).isTrue();
        assertThat(CantonRlp.isEmpty(CantonRlp.of(new byte[] {(byte) 0xc0}))).isTrue();
    }

    @Test
    void isEmptyAcceptsZeroLengthSlice() {
        assertThat(CantonRlp.isEmpty(CantonRlp.of(new byte[0]))).isTrue();
    }

    @Test
    void isEmptyRejectsNonEmptyItems() {
        assertThat(CantonRlp.isEmpty(CantonRlp.of(new byte[] {0x01}))).isFalse();
        assertThat(CantonRlp.isEmpty(CantonRlp.of(new byte[] {(byte) 0x81, (byte) 0x80})))
                .isFalse();
        assertThat(CantonRlp.isEmpty(CantonRlp.of(new byte[] {(byte) 0xc1, 0x01})))
                .isFalse();
    }

    @Test
    void isEmptyHonoursSliceOffset() {
        final byte[] buf = {0x01, (byte) 0x80, 0x01};
        assertThat(CantonRlp.isEmpty(new Slice(buf, 1, 1))).isTrue();
        assertThat(CantonRlp.isEmpty(new Slice(buf, 2, 1))).isFalse();
    }

    // ── readList ─────────────────────────────────────────────────────────────

    @Test
    void readListSplitsElementsWithoutCopying() {
        // [ "a", "bc", [] ]
        final byte[] rlp = {
            (byte) 0xc5, 0x61, (byte) 0x82, 0x62, 0x63, (byte) 0xc0,
        };
        final List<Slice> items = CantonRlp.readList(CantonRlp.of(rlp));
        assertThat(items).hasSize(3);
        assertThat(CantonRlp.readBytes(items.get(0))).containsExactly(0x61);
        assertThat(CantonRlp.readBytes(items.get(1))).containsExactly(0x62, 0x63);
        assertThat(CantonRlp.isEmpty(items.get(2))).isTrue();
    }

    @Test
    void readListAcceptsEmptyList() {
        assertThat(CantonRlp.readList(CantonRlp.of(new byte[] {(byte) 0xc0}))).isEmpty();
    }

    @Test
    void readListRejectsStringItem() {
        assertInvalid(() -> CantonRlp.readList(CantonRlp.of(new byte[] {(byte) 0x82, 0x01, 0x02})));
    }

    @Test
    void readListRejectsTrailingBytes() {
        assertInvalid(() -> CantonRlp.readList(CantonRlp.of(new byte[] {(byte) 0xc0, 0x01})));
    }

    @Test
    void readListRejectsElementOverrunningList() {
        // list payload length 2 holding a string header that claims 5 bytes
        assertInvalid(() -> CantonRlp.readList(CantonRlp.of(new byte[] {(byte) 0xc2, (byte) 0x85, 0x01})));
    }

    @Test
    void readListRejectsTruncatedList() {
        assertInvalid(() -> CantonRlp.readList(CantonRlp.of(new byte[] {(byte) 0xc3, 0x01})));
    }

    // ── readBytes ────────────────────────────────────────────────────────────

    @Test
    void readBytesReturnsSingleByteAndShortStringPayloads() {
        assertThat(CantonRlp.readBytes(CantonRlp.of(new byte[] {0x7f}))).containsExactly(0x7f);
        assertThat(CantonRlp.readBytes(CantonRlp.of(new byte[] {(byte) 0x80}))).isEmpty();
        assertThat(CantonRlp.readBytes(CantonRlp.of(new byte[] {(byte) 0x82, 0x01, 0x02})))
                .containsExactly(0x01, 0x02);
    }

    @Test
    void readBytesReadsLongString() {
        final byte[] payload = new byte[56];
        Arrays.fill(payload, (byte) 0x07);
        final byte[] rlp = new byte[2 + payload.length];
        rlp[0] = (byte) 0xb8;
        rlp[1] = (byte) payload.length;
        System.arraycopy(payload, 0, rlp, 2, payload.length);
        assertThat(CantonRlp.readBytes(CantonRlp.of(rlp))).isEqualTo(payload);
    }

    @Test
    void readBytesRejectsList() {
        assertInvalid(() -> CantonRlp.readBytes(CantonRlp.of(new byte[] {(byte) 0xc0})));
    }

    @Test
    void readBytesRejectsEmptyInput() {
        assertInvalid(() -> CantonRlp.readBytes(CantonRlp.of(new byte[0])));
    }

    @Test
    void readBytesRejectsNonMinimalSingleByteString() {
        // 0x81 0x05 should have been encoded as the single byte 0x05.
        assertInvalid(() -> CantonRlp.readBytes(CantonRlp.of(new byte[] {(byte) 0x81, 0x05})));
    }

    @Test
    void readBytesRejectsShortString() {
        assertInvalid(() -> CantonRlp.readBytes(CantonRlp.of(new byte[] {(byte) 0x83, 0x01})));
    }

    @Test
    void readBytesRejectsNonCanonicalLongLengths() {
        // Leading-zero length, and a long form whose value is <= 55.
        assertInvalid(() -> CantonRlp.readBytes(CantonRlp.of(new byte[] {(byte) 0xb8, 0x00})));
        final byte[] small = new byte[2 + 10];
        small[0] = (byte) 0xb8;
        small[1] = 10;
        assertInvalid(() -> CantonRlp.readBytes(CantonRlp.of(small)));
    }

    // ── readBytes32 ──────────────────────────────────────────────────────────

    @Test
    void readBytes32LeftPadsShortValues() {
        final byte[] out = CantonRlp.readBytes32(CantonRlp.of(new byte[] {(byte) 0x82, 0x01, 0x02}));
        assertThat(out).hasSize(32);
        assertThat(out[30]).isEqualTo((byte) 0x01);
        assertThat(out[31]).isEqualTo((byte) 0x02);
        assertThat(Arrays.copyOf(out, 30)).containsOnly((byte) 0);
    }

    @Test
    void readBytes32AcceptsExactly32Bytes() {
        final byte[] value = new byte[32];
        Arrays.fill(value, (byte) 0x09);
        final byte[] rlp = new byte[33];
        rlp[0] = (byte) 0xa0;
        System.arraycopy(value, 0, rlp, 1, 32);
        assertThat(CantonRlp.readBytes32(CantonRlp.of(rlp))).isEqualTo(value);
    }

    @Test
    void readBytes32RejectsMoreThan32Bytes() {
        final byte[] rlp = new byte[2 + 33];
        rlp[0] = (byte) 0xb8;
        rlp[1] = 33;
        assertInvalid(() -> CantonRlp.readBytes32(CantonRlp.of(rlp)));
    }

    private static void assertInvalid(final Executable body) {
        assertThatThrownBy(body::execute)
                .isInstanceOf(CantonProofException.class)
                .satisfies(
                        e -> assertThat(((CantonProofException) e).errorName()).isEqualTo("InvalidPayloadShape"));
    }
}
