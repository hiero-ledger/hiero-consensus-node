// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.test.fixtures.merkle.util;

import com.swirlds.common.merkle.synchronization.streams.AsyncOutputStream;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import org.hiero.consensus.concurrent.pool.StandardWorkGroup;
import org.hiero.consensus.reconnect.config.ReconnectConfig;

/**
 * This variant of the async output stream introduces extra latency. For each message,
 * the enqueue timestamp is recorded in {@link #sendAsync(byte[])}. When the message is
 * actually written on the writer thread in {@link #writeMessage(byte[])}, the thread
 * sleeps for any remaining time needed to reach the configured latency, ensuring a
 * minimum delay between enqueue and write.
 */
public class LaggingAsyncOutputStream extends AsyncOutputStream {

    private final BlockingQueue<Long> messageTimes;

    private final long latencyMilliseconds;

    /**
     * Create a new lagging async output stream.
     *
     * @param out the underlying output stream
     * @param workGroup the work group managing this stream's thread
     * @param latencyMilliseconds the simulated latency in milliseconds
     * @param reconnectConfig the reconnect configuration
     */
    public LaggingAsyncOutputStream(
            @NonNull final DataOutputStream out,
            @NonNull final StandardWorkGroup workGroup,
            final long latencyMilliseconds,
            @NonNull final ReconnectConfig reconnectConfig) {
        super(out, workGroup, reconnectConfig);
        this.messageTimes = new LinkedBlockingQueue<>();
        this.latencyMilliseconds = latencyMilliseconds;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Records the current time before enqueueing so that the writer thread can compute
     * the remaining delay.
     */
    @Override
    public void sendAsync(@NonNull final byte[] messageBytes) throws InterruptedException {
        messageTimes.put(System.currentTimeMillis());
        super.sendAsync(messageBytes);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Sleeps on the writer thread for any remaining time needed to ensure a minimum
     * latency between enqueue and write, then delegates to the parent.
     */
    @Override
    protected void writeMessage(@NonNull final byte[] messageBytes) throws IOException {
        final long messageTime = messageTimes.remove();
        final long now = System.currentTimeMillis();
        final long waitTime = (messageTime + latencyMilliseconds) - now;
        if (waitTime > 0) {
            try {
                Thread.sleep(waitTime);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        super.writeMessage(messageBytes);
    }
}
