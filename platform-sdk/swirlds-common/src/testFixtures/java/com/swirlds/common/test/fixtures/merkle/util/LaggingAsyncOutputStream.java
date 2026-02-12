// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.test.fixtures.merkle.util;

import com.swirlds.common.merkle.synchronization.streams.AsyncOutputStream;
import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Supplier;
import org.hiero.base.io.SelfSerializable;
import org.hiero.base.io.streams.SerializableDataOutputStream;
import org.hiero.consensus.concurrent.pool.StandardWorkGroup;
import org.hiero.consensus.reconnect.config.ReconnectConfig;

/**
 * This variant of the async output stream introduces extra latency.
 */
public class LaggingAsyncOutputStream extends AsyncOutputStream {

    private final BlockingQueue<Long> messageTimes;

    private final long latencyMilliseconds;

    public LaggingAsyncOutputStream(
            final SerializableDataOutputStream out,
            final StandardWorkGroup workGroup,
            final Supplier<Boolean> isAlive,
            final long latencyMilliseconds,
            final ReconnectConfig reconnectConfig) {
        super(out, workGroup, isAlive, reconnectConfig);
        this.messageTimes = new LinkedBlockingQueue<>();
        this.latencyMilliseconds = latencyMilliseconds;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void sendAsync(final SelfSerializable message) throws InterruptedException {
        messageTimes.put(System.currentTimeMillis());
        super.sendAsync(message);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void serializeMessage(final SelfSerializable message, final SerializableDataOutputStream out)
            throws IOException {
        long messageTime = messageTimes.remove();
        long now = System.currentTimeMillis();
        long waitTime = (messageTime + latencyMilliseconds) - now;
        if (waitTime > 0) {
            try {
                Thread.sleep(waitTime);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        super.serializeMessage(message, out);
    }
}
