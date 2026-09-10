// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.sei;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.node.app.service.clpr.impl.verifier.ProofException;
import org.junit.jupiter.api.Test;

class SeiProtoTest {
    @Test
    void readerRejectsMalformedWireInput() {
        assertThatThrownBy(() -> new SeiProto.Reader(new byte[] {0}).readTag())
                .isInstanceOf(ProofException.class)
                .hasMessageContaining("invalid protobuf tag");
        assertThatThrownBy(() -> new SeiProto.Reader(new byte[] {(byte) 0x80}).readVarint())
                .isInstanceOf(ProofException.class)
                .hasMessageContaining("truncated varint");
        assertThatThrownBy(() -> new SeiProto.Reader(new byte[] {
                            (byte) 0x80,
                            (byte) 0x80,
                            (byte) 0x80,
                            (byte) 0x80,
                            (byte) 0x80,
                            (byte) 0x80,
                            (byte) 0x80,
                            (byte) 0x80,
                            (byte) 0x80,
                            (byte) 0x80
                        })
                        .readVarint())
                .isInstanceOf(ProofException.class)
                .hasMessageContaining("varint longer than 10 bytes");
        assertThatThrownBy(() -> new SeiProto.Reader(new byte[] {5, 1}).readBytes())
                .isInstanceOf(ProofException.class)
                .hasMessageContaining("truncated length-delimited field");
    }

    @Test
    void writerOmitsProto3DefaultsAndRejectsNulls() {
        assertThat(SeiProto.varintField(1, 0)).isEmpty();
        assertThat(SeiProto.bytesField(1, new byte[0])).isEmpty();
        assertThat(SeiProto.sfixed64Field(1, 0)).isEmpty();
        assertThat(SeiProto.delimited(new byte[] {1, 2})).isEqualTo(new byte[] {2, 1, 2});

        assertThatNullPointerException().isThrownBy(() -> new SeiProto.Reader(null));
        assertThatNullPointerException().isThrownBy(() -> SeiProto.bytesField(1, null));
        assertThatNullPointerException().isThrownBy(() -> SeiProto.messageField(1, null));
        assertThatNullPointerException().isThrownBy(() -> SeiProto.delimited(null));
    }
}
