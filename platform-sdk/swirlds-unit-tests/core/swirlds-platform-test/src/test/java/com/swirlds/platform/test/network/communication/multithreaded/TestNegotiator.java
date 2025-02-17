/*
 * Copyright (C) 2022-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.swirlds.platform.test.network.communication.multithreaded;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.platform.network.Connection;
import com.swirlds.platform.network.ConnectionManager;
import com.swirlds.platform.network.communication.NegotiationProtocols;
import com.swirlds.platform.network.communication.ProtocolNegotiatorThread;
import com.swirlds.platform.test.network.communication.TestPeerProtocol;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Used to run a negotiator in a separate thread and capture any exceptions it might throw
 */
class TestNegotiator {
    private final Configuration configuration = new TestConfigBuilder().getOrCreateConfig();
    private final PlatformContext platformContext =
            TestPlatformContextBuilder.create().withConfiguration(configuration).build();

    private final TestPeerProtocol protocol;
    private final ProtocolNegotiatorThread negotiator;
    private final Thread thread;
    private final AtomicInteger handshakeRan = new AtomicInteger(0);
    private volatile Exception thrown;

    public TestNegotiator(final Connection connection, final TestPeerProtocol protocol) {
        final ConnectionManager connectionManager = new ReturnOnceConnectionManager(connection);
        // disconnect the connection after running the protocol once in order to stop the thread
        this.protocol = protocol.setRunProtocol(Connection::disconnect);
        negotiator = new ProtocolNegotiatorThread(
                connectionManager,
                100,
                List.of(c -> handshakeRan.incrementAndGet()),
                new NegotiationProtocols(List.of(protocol)),
                platformContext.getTime());
        thread = new Thread(this::run);
    }

    private void run() {
        try {
            negotiator.run();
        } catch (final InterruptedException ignored) {
            // this is expected
        } catch (final Exception e) {
            thrown = e;
        }
    }

    public TestPeerProtocol getProtocol() {
        return protocol;
    }

    public Thread getThread() {
        return thread;
    }

    public int getHandshakeRunNumber() {
        return handshakeRan.get();
    }

    public void rethrow() throws Exception {
        if (thrown != null) {
            throw thrown;
        }
    }
}
