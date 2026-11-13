// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ProvenQueueMetadataTest {

    @Nested
    class Constructor {

        @Test
        void storesDefensiveCopiesOfHashes() {
            final byte[] sent = bytes(32, 0x40);
            final byte[] received = bytes(32, 0x50);
            final byte[] last = bytes(32, 0x60);

            final ProvenQueueMetadata metadata = new ProvenQueueMetadata(1L, sent, 2L, received, 3, last, 9L);

            // mutating the inputs after construction must not affect the record
            sent[0] = 0x7F;
            received[0] = 0x7F;
            last[0] = 0x7F;
            assertThat(metadata.sentRunningHash()).isEqualTo(bytes(32, 0x40));
            assertThat(metadata.receivedRunningHash()).isEqualTo(bytes(32, 0x50));
            assertThat(metadata.lastMessageRunningHash()).isEqualTo(bytes(32, 0x60));
            assertThat(metadata.endpointManifestVersion()).isEqualTo(9L);
        }

        @Test
        void accessorsReturnFreshCopies() {
            final ProvenQueueMetadata metadata =
                    new ProvenQueueMetadata(1L, bytes(32, 1), 2L, bytes(32, 2), 0, bytes(32, 3), 0L);

            assertThat(metadata.sentRunningHash()).isNotSameAs(metadata.sentRunningHash());
            assertThat(metadata.receivedRunningHash()).isNotSameAs(metadata.receivedRunningHash());
            assertThat(metadata.lastMessageRunningHash()).isNotSameAs(metadata.lastMessageRunningHash());
        }

        @Test
        void rejectsWrongLengthSentRunningHash() {
            assertThatThrownBy(() -> new ProvenQueueMetadata(1L, bytes(31, 1), 2L, bytes(32, 2), 0, bytes(32, 3), 0L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("sentRunningHash must be 32 bytes, got 31");
        }

        @Test
        void rejectsWrongLengthReceivedRunningHash() {
            assertThatThrownBy(() -> new ProvenQueueMetadata(1L, bytes(32, 1), 2L, bytes(33, 2), 0, bytes(32, 3), 0L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("receivedRunningHash must be 32 bytes, got 33");
        }

        @Test
        void rejectsWrongLengthLastMessageRunningHash() {
            assertThatThrownBy(() -> new ProvenQueueMetadata(1L, bytes(32, 1), 2L, bytes(32, 2), 0, bytes(0, 3), 0L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("lastMessageRunningHash must be 32 bytes, got 0");
        }

        @Test
        void rejectsNullHash() {
            assertThatThrownBy(() -> new ProvenQueueMetadata(1L, null, 2L, bytes(32, 2), 0, bytes(32, 3), 0L))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("sentRunningHash");
        }
    }

    // ── helpers ──

    private static byte[] bytes(final int length, final int seed) {
        final byte[] out = new byte[length];
        for (int i = 0; i < length; i++) {
            out[i] = (byte) (seed + i * 31);
        }
        return out;
    }
}
