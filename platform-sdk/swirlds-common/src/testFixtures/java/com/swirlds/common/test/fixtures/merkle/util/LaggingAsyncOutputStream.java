// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.test.fixtures.merkle.util;

import com.swirlds.common.merkle.synchronization.config.ReconnectConfig;
import com.swirlds.common.merkle.synchronization.streams.AsyncOutputStream;
import com.swirlds.common.merkle.synchronization.utility.MerkleSynchronizationException;
import com.swirlds.common.threading.pool.StandardWorkGroup;
import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import org.hiero.consensus.model.io.SelfSerializable;
import org.hiero.consensus.model.io.streams.SerializableDataOutputStream;

/**
 * This variant of the async output stream introduces extra latency.
 */
public class LaggingAsyncOutputStream<T extends SelfSerializable> extends AsyncOutputStream<T> {

    private final BlockingQueue<Long> messageTimes;

    private final long latencyMilliseconds;

    public LaggingAsyncOutputStream(
            final SerializableDataOutputStream out,
            final StandardWorkGroup workGroup,
            final long latencyMilliseconds,
            final ReconnectConfig reconnectConfig) {
        super(out, workGroup, reconnectConfig);
        this.messageTimes = new LinkedBlockingQueue<>();
        this.latencyMilliseconds = latencyMilliseconds;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void sendAsync(final T message) throws InterruptedException {
        if (!isAlive()) {
            throw new MerkleSynchronizationException("Messages can not be sent after close has been called.");
        }
        messageTimes.put(System.currentTimeMillis());
        getOutgoingMessages().put(message);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void serializeMessage(final T message) throws IOException {
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
        message.serialize(getOutputStream());
    }
}
