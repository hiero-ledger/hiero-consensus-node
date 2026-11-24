// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.test;

import static com.hedera.node.app.service.clpr.impl.ClprThrottlesValidator.MIN_MESSAGE_PAYLOAD_BYTES;
import static com.hedera.node.app.service.clpr.impl.ClprThrottlesValidator.MIN_PROOF_OVERHEAD_BYTES;
import static com.hedera.node.app.service.clpr.impl.ClprThrottlesValidator.areValidThrottles;
import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.node.state.clpr.ClprMessagePayload;
import com.hedera.hapi.node.state.clpr.ClprMessageReply;
import com.hedera.hapi.node.state.clpr.ClprMessageReplyStatus;
import com.hedera.hapi.node.state.clpr.ClprThrottles;
import org.junit.jupiter.api.Test;

class ClprThrottlesValidatorTest {
    private static final ClprThrottles VALID_THROTTLES = ClprThrottles.newBuilder()
            .maxMessagesPerBundle(1_000)
            .maxMessagePayloadBytes(65_536)
            .maxGasPerMessage(15_000_000L)
            .maxQueueDepth(10_000)
            .maxSyncBytes(4_194_304L)
            .build();

    @Test
    void localPayloadMinimumFitsEveryEmptyErrorReply() {
        final var worstCaseEmptyReply = ClprMessagePayload.newBuilder()
                .messageReply(ClprMessageReply.newBuilder()
                        .messageId(-1L) // uint64 maximum, encoded in ten bytes
                        .status(ClprMessageReplyStatus.APPLICATION_ERROR))
                .build();
        final int serializedBytes = ClprMessagePayload.PROTOBUF.measureRecord(worstCaseEmptyReply);
        assertThat(serializedBytes).isEqualTo(MIN_MESSAGE_PAYLOAD_BYTES).isEqualTo(15);
        assertThat(areValidThrottles(VALID_THROTTLES
                        .copyBuilder()
                        .maxMessagePayloadBytes(serializedBytes)
                        .maxSyncBytes(serializedBytes + MIN_PROOF_OVERHEAD_BYTES)
                        .build()))
                .isTrue();
        assertThat(areValidThrottles(VALID_THROTTLES
                        .copyBuilder()
                        .maxMessagePayloadBytes(serializedBytes - 1)
                        .build()))
                .isFalse();
    }

    @Test
    void validatesThrottles() {
        assertThat(areValidThrottles(VALID_THROTTLES)).isTrue();
        assertThat(areValidThrottles(ClprThrottles.DEFAULT)).isFalse();
        assertThat(areValidThrottles(
                        VALID_THROTTLES.copyBuilder().maxGasPerMessage(0).build()))
                .isFalse();
        // max_sync_bytes must leave room for one max-sized message plus its proof overhead
        final var payload = VALID_THROTTLES.maxMessagePayloadBytes();
        assertThat(areValidThrottles(VALID_THROTTLES
                        .copyBuilder()
                        .maxSyncBytes(payload + MIN_PROOF_OVERHEAD_BYTES)
                        .build()))
                .isTrue();
        assertThat(areValidThrottles(VALID_THROTTLES
                        .copyBuilder()
                        .maxSyncBytes(payload + MIN_PROOF_OVERHEAD_BYTES - 1)
                        .build()))
                .isFalse();
    }
}
