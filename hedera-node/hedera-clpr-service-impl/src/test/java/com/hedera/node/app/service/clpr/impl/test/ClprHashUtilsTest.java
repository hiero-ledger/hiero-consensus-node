// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.test;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.node.state.clpr.ClprMessage;
import com.hedera.hapi.node.state.clpr.ClprMessagePayload;
import com.hedera.node.app.service.clpr.impl.ClprHashUtils;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.security.MessageDigest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ClprHashUtilsTest {

    @Test
    @DisplayName("computeRunningHash produces SHA-256(prevHash || SHA-256(serialized payload))")
    void hashMatchesSpecFormula() throws Exception {
        final var prev = Bytes.wrap(new byte[32]); // all-zero initial hash per spec
        final var payload = ClprMessagePayload.newBuilder()
                .message(ClprMessage.newBuilder()
                        .connectorId(Bytes.wrap(new byte[] {1, 2, 3}))
                        .messageData(Bytes.wrap(new byte[] {4, 5, 6}))
                        .build())
                .build();

        final var payloadHash = MessageDigest.getInstance("SHA-256")
                .digest(ClprMessagePayload.PROTOBUF.toBytes(payload).toByteArray());
        final var outer = MessageDigest.getInstance("SHA-256");
        outer.update(prev.toByteArray());
        outer.update(payloadHash);
        final var expected = Bytes.wrap(outer.digest());

        assertThat(ClprHashUtils.computeRunningHash(prev, payload)).isEqualTo(expected);
    }

    @Test
    @DisplayName("computeRunningHashFromPayloadHash matches the chain step with payload_hash injected")
    void computeRunningHashFromPayloadHashChainsIdentically() throws Exception {
        // The property that makes redaction self-verifying: feeding SHA-256(payload) into the
        // redacted-slot helper produces the same running hash as folding the payload directly.
        final var prev = Bytes.wrap(new byte[32]);
        final var payload = ClprMessagePayload.newBuilder()
                .message(ClprMessage.newBuilder()
                        .messageData(Bytes.wrap(new byte[] {7, 8, 9}))
                        .build())
                .build();
        final var payloadHash = Bytes.wrap(MessageDigest.getInstance("SHA-256")
                .digest(ClprMessagePayload.PROTOBUF.toBytes(payload).toByteArray()));

        final var viaPayload = ClprHashUtils.computeRunningHash(prev, payload);
        final var viaPayloadHash = ClprHashUtils.computeRunningHashFromPayloadHash(prev, payloadHash);

        assertThat(viaPayloadHash).isEqualTo(viaPayload);
    }

    @Test
    @DisplayName("hash chains correctly: second hash takes first hash as prev")
    void hashChainsCorrectly() throws Exception {
        final var initialHash = Bytes.wrap(new byte[32]);
        final var payload1 = ClprMessagePayload.newBuilder()
                .message(ClprMessage.newBuilder()
                        .connectorId(Bytes.wrap(new byte[] {1}))
                        .messageData(Bytes.wrap(new byte[] {10}))
                        .build())
                .build();
        final var payload2 = ClprMessagePayload.newBuilder()
                .message(ClprMessage.newBuilder()
                        .connectorId(Bytes.wrap(new byte[] {2}))
                        .messageData(Bytes.wrap(new byte[] {20}))
                        .build())
                .build();

        final var hash1 = ClprHashUtils.computeRunningHash(initialHash, payload1);
        final var hash2 = ClprHashUtils.computeRunningHash(hash1, payload2);

        // hash2 must differ from hash1 and from the initial hash
        assertThat(hash2).isNotEqualTo(hash1);
        assertThat(hash2).isNotEqualTo(initialHash);
        assertThat(hash2.length()).isEqualTo(32);

        // Independently verify the chain via the spec §4.1 formula.
        final var payload2Hash = MessageDigest.getInstance("SHA-256")
                .digest(ClprMessagePayload.PROTOBUF.toBytes(payload2).toByteArray());
        final var outer = MessageDigest.getInstance("SHA-256");
        outer.update(hash1.toByteArray());
        outer.update(payload2Hash);
        assertThat(hash2).isEqualTo(Bytes.wrap(outer.digest()));
    }

    @Test
    @DisplayName("same payload from different prev hashes produces different results")
    void differentPrevHashProducesDifferentResult() {
        final var payload = ClprMessagePayload.newBuilder()
                .message(ClprMessage.newBuilder()
                        .messageData(Bytes.wrap(new byte[] {99}))
                        .build())
                .build();

        final var hash1 = ClprHashUtils.computeRunningHash(Bytes.wrap(new byte[32]), payload);
        final var prev2 = new byte[32];
        prev2[0] = 1;
        final var hash2 = ClprHashUtils.computeRunningHash(Bytes.wrap(prev2), payload);

        assertThat(hash1).isNotEqualTo(hash2);
    }
}
