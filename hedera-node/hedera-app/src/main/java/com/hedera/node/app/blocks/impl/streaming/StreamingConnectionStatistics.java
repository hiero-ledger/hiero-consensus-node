// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Simple data object used to capture connection-specific statistics.
 * <p>
 * A note on block sent and block acknowledgement counters: In general the number of blocks sent and the number of
 * blocks acknowledged should match, with a slight delay between when the block is sent vs when the acknowledgement is
 * received. However, there is a very real scenario where the consensus node may prematurely send a full block (and thus
 * the number of blocks sent being incremented) before the block node responds with a message to switch to an earlier
 * block via something like a BehindPublisher response. In this case, the number of blocks sent may be higher than the
 * number of blocks acknowledged forever.
 */
public class StreamingConnectionStatistics {
    /**
     * Counter to track the total number of blocks sent by the connection.
     */
    private final AtomicInteger numBlocksSent = new AtomicInteger(0);
    /**
     * Reference to track the latest block sent by the connection.
     */
    private final AtomicLong lastBlockSent = new AtomicLong(-1);
    /**
     * Counter to track the total number of blocks acknowledged by the connection
     */
    private final AtomicInteger numBlocksAcked = new AtomicInteger(0);
    /**
     * Reference to track the latest block acknowledged by the connection.
     */
    private final AtomicLong lastBlockAcked = new AtomicLong(-1);
    /**
     * Timestamp that represents the most recent time the connection has executed the worker loop.
     */
    private final AtomicLong lastHeartbeatMillis = new AtomicLong(-1);
    /**
     * Counter to track the number of attempts made to send requests by the connection.
     */
    private final AtomicLong numRequestSendAttempts = new AtomicLong(0);
    /**
     * Counter to track the number of successful attempts to send requests by the connection.
     */
    private final AtomicLong numRequestSendSuccesses = new AtomicLong(0);

    /**
     * @return the number of blocks sent, else 0 if no block sends were recorded
     */
    int numBlocksSent() {
        return numBlocksSent.get();
    }

    /**
     * @return the last block number sent, else -1 if no block sends were recorded
     */
    long lastBlockSent() {
        return lastBlockSent.get();
    }

    /**
     * @return the number of blocks acknowledged, else 0 if no block acknowledgements recorded
     */
    int numBlocksAcked() {
        return numBlocksAcked.get();
    }

    /**
     * @return the highest (and should be last) block number acknowledged, else -1 if no block acknowledgement recorded
     */
    long lastBlockAcked() {
        return lastBlockAcked.get();
    }

    /**
     * @return the latest heartbeat timestamp (as milliseconds), else -1 if no heartbeat recorded
     */
    long lastHeartbeatMillis() {
        return lastHeartbeatMillis.get();
    }

    /**
     * @return the number of attempted requests sent to a block node, else 0 if no send requests were recorded
     */
    long numRequestSendAttempts() {
        return numRequestSendAttempts.get();
    }

    /**
     * @return the number of successful requests sent to a block node, else 0 if no successful send requests were recorded
     */
    long numRequestSendSuccesses() {
        return numRequestSendSuccesses.get();
    }

    /**
     * Records the latest heartbeat. If the specified heartbeat is older than the existing heartbeat captured, it is ignored.
     *
     * @param heartbeatMillis the new heartbeat to capture
     */
    void recordHeartbeat(final long heartbeatMillis) {
        lastHeartbeatMillis.updateAndGet(old -> Math.max(old, heartbeatMillis));
    }

    /**
     * Records a block acknowledgement. If the specified block number is older than the existing block number acknowledged, it is ignored.
     *
     * @param ackedBlockNumber the block that was acknowledged
     */
    void recordAcknowledgement(final long ackedBlockNumber) {
        final long previousAckedBlock = lastBlockAcked.getAndUpdate(old -> Math.max(old, ackedBlockNumber));
        if (previousAckedBlock < ackedBlockNumber) {
            numBlocksAcked.incrementAndGet();
        }
    }

    /**
     * Records a block has been fully sent to a block node.
     *
     * @param blockNumber the block number that was sent
     */
    void recordBlockSent(final long blockNumber) {
        lastBlockSent.set(blockNumber);
        numBlocksSent.incrementAndGet();
    }

    /**
     * Records the number of attempted requests sent to a block node.
     */
    void recordRequestSendAttempt() {
        numRequestSendAttempts.incrementAndGet();
    }

    /**
     * Records the number of successful requests sent to a block node.
     */
    void recordRequestSendSuccess() {
        numRequestSendSuccesses.incrementAndGet();
    }
}
