// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gossip.impl.test.fixtures.communication;

import com.swirlds.common.io.utility.IOConsumer;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import org.hiero.consensus.gossip.impl.gossip.sync.SyncInputStream;
import org.hiero.consensus.gossip.impl.gossip.sync.SyncOutputStream;
import org.hiero.consensus.gossip.impl.test.fixtures.sync.FakeConnection;

public class ReadWriteFakeConnection extends FakeConnection {
    private final SyncInputStream in;
    private final SyncOutputStream out;

    private final Configuration configuration =
            new TestConfigBuilder().withValue("socket.gzipCompression", false).getOrCreateConfig();

    public ReadWriteFakeConnection(final InputStream in, final OutputStream out) {
        super();
        this.in = SyncInputStream.createSyncInputStream(configuration, in, 100);
        this.out = SyncOutputStream.createSyncOutputStream(configuration, out, 100);
    }

    /**
     * Create a fake connection with a function that is called on every byte sent to the output stream.
     *
     * @param in
     * 		the input stream
     * @param out
     * 		the output stream
     * @param outputInterceptor
     * 		a function called on each byte sent to the output stream via the write() method, ignored if null
     */
    public ReadWriteFakeConnection(
            final InputStream in, final OutputStream out, final IOConsumer<Integer> outputInterceptor) {

        super();
        this.in = SyncInputStream.createSyncInputStream(configuration, in, 100);
        final OutputStream baseOutput = new OutputStream() {
            @Override
            public void write(final int b) throws IOException {
                if (outputInterceptor != null) {
                    outputInterceptor.accept(b);
                }
                out.write(b);
            }
        };
        this.out = SyncOutputStream.createSyncOutputStream(configuration, baseOutput, 100);
    }

    @Override
    public SyncInputStream getDis() {
        return in;
    }

    @Override
    public SyncOutputStream getDos() {
        return out;
    }
}
