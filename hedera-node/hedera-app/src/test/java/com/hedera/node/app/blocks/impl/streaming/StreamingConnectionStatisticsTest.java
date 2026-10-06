// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StreamingConnectionStatisticsTest {

    StreamingConnectionStatistics statistics;

    @BeforeEach
    void beforeEach() {
        statistics = new StreamingConnectionStatistics();
    }

    @Test
    void testHeartbeat() {
        assertThat(statistics.lastHeartbeatMillis()).isEqualTo(-1);

        // update the heartbeat
        statistics.recordHeartbeat(1_000);
        assertThat(statistics.lastHeartbeatMillis()).isEqualTo(1_000);

        // add a new heartbeat that is older than the previous... it should _not_ be updated
        statistics.recordHeartbeat(500);
        assertThat(statistics.lastHeartbeatMillis()).isEqualTo(1_000);

        // add a later one and make sure it is updated
        statistics.recordHeartbeat(2_000);
        assertThat(statistics.lastHeartbeatMillis()).isEqualTo(2_000);
    }

    @Test
    void testAcknowledge() {
        assertThat(statistics.lastBlockAcked()).isEqualTo(-1);
        assertThat(statistics.numBlocksAcked()).isZero();

        // acknowledge block 10
        statistics.recordAcknowledgement(10);
        assertThat(statistics.lastBlockAcked()).isEqualTo(10);
        assertThat(statistics.numBlocksAcked()).isOne();

        // acknowledge the same block... number of acked blocks should be unchanged
        statistics.recordAcknowledgement(10);
        assertThat(statistics.lastBlockAcked()).isEqualTo(10);
        assertThat(statistics.numBlocksAcked()).isOne();

        // acknowledge an older block... number of acked blocks and last acked block should be unchanged
        statistics.recordAcknowledgement(8);
        assertThat(statistics.lastBlockAcked()).isEqualTo(10);
        assertThat(statistics.numBlocksAcked()).isOne();

        // acknowledge a later block and ensure everything is updated
        statistics.recordAcknowledgement(11);
        assertThat(statistics.lastBlockAcked()).isEqualTo(11);
        assertThat(statistics.numBlocksAcked()).isEqualTo(2);
    }

    @Test
    void testBlockSent() {
        assertThat(statistics.lastBlockSent()).isEqualTo(-1);
        assertThat(statistics.numBlocksSent()).isZero();

        statistics.recordBlockSent(10);
        // simulate a BehindPublisher being received causing us to switch to an older block after we've already sent a
        // later block
        statistics.recordBlockSent(8);

        assertThat(statistics.numBlocksSent()).isEqualTo(2);
        assertThat(statistics.lastBlockSent()).isEqualTo(8);
    }

    @Test
    void testRequestSendAttempt() {
        assertThat(statistics.numRequestSendAttempts()).isZero();

        statistics.recordRequestSendAttempt();
        statistics.recordRequestSendAttempt();

        assertThat(statistics.numRequestSendAttempts()).isEqualTo(2);
    }

    @Test
    void testRequestSendSuccess() {
        assertThat(statistics.numRequestSendSuccesses()).isZero();

        statistics.recordRequestSendSuccess();
        statistics.recordRequestSendSuccess();

        assertThat(statistics.numRequestSendSuccesses()).isEqualTo(2);
    }
}
