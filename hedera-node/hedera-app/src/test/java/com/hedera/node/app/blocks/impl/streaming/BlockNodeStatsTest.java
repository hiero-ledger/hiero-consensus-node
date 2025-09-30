// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BlockNodeStatsTest {

    private BlockNodeStats blockNodeStats;

    @BeforeEach
    void beforeEach() {
        blockNodeStats = new BlockNodeStats();
    }

    @Test
    void test_endOfStream_exceededMaxPermitted() {
        final Instant now = Instant.now();
        assertThat(blockNodeStats.addEndOfStreamAndCheckLimit(now.minusSeconds(3), 2, Duration.ofSeconds(10L)))
                .isFalse();
        assertThat(blockNodeStats.addEndOfStreamAndCheckLimit(now.minusSeconds(2), 2, Duration.ofSeconds(10L)))
                .isFalse();
        assertThat(blockNodeStats.addEndOfStreamAndCheckLimit(now.minusSeconds(1), 2, Duration.ofSeconds(10L)))
                .isTrue();
        assertThat(blockNodeStats.getEndOfStreamCount()).isEqualTo(3);
    }

    @Test
    void test_ackLatency_consecutiveHighLatencyAndSwitching() {
        final Duration threshold = Duration.ofMillis(50);
        final int eventsBeforeSwitching = 2;

        // Send and ack with high latency (1)
        blockNodeStats.recordBlockProofSent(1L, Instant.now().minusMillis(100));
        final var res1 =
                blockNodeStats.recordAcknowledgementAndEvaluate(1L, Instant.now(), threshold, eventsBeforeSwitching);
        assertThat(res1.isHighLatency()).isTrue();
        assertThat(res1.consecutiveHighLatencyEvents()).isEqualTo(1);
        assertThat(res1.shouldSwitch()).isFalse();

        // Send and ack with high latency (2) -> should trigger switch and reset counter
        blockNodeStats.recordBlockProofSent(2L, Instant.now().minusMillis(200));
        final var res2 =
                blockNodeStats.recordAcknowledgementAndEvaluate(2L, Instant.now(), threshold, eventsBeforeSwitching);
        assertThat(res2.isHighLatency()).isTrue();
        assertThat(res2.consecutiveHighLatencyEvents()).isEqualTo(2);
        assertThat(res2.shouldSwitch()).isTrue();

        // After switch, counter should be reset; next low latency should keep it at 0
        blockNodeStats.recordBlockProofSent(3L, Instant.now().minusMillis(10));
        final var res3 =
                blockNodeStats.recordAcknowledgementAndEvaluate(3L, Instant.now(), threshold, eventsBeforeSwitching);
        assertThat(res3.isHighLatency()).isFalse();
        assertThat(res3.consecutiveHighLatencyEvents()).isZero();
        assertThat(res3.shouldSwitch()).isFalse();
    }

    @Test
    void test_ackLatency_missingSendTimestampIsNoOp() {
        // No recordBlockSent for block 42; ack should be no-op and not trigger switch
        final var res = blockNodeStats.recordAcknowledgementAndEvaluate(42L, Instant.now(), Duration.ofSeconds(5), 1);
        assertThat(res.isHighLatency()).isFalse();
        assertThat(res.consecutiveHighLatencyEvents()).isGreaterThanOrEqualTo(0);
        assertThat(res.shouldSwitch()).isFalse();
    }
}
