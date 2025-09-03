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
}
