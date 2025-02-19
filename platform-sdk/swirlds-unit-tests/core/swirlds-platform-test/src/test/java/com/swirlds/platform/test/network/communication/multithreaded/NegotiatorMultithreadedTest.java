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

import static org.awaitility.Awaitility.await;

import com.swirlds.base.utility.Pair;
import com.swirlds.common.platform.NodeId;
import com.swirlds.platform.network.Connection;
import com.swirlds.platform.test.network.communication.TestPeerProtocol;
import com.swirlds.platform.test.sync.ConnectionFactory;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests negotiations that are executed on separate threads and communicate directly over a connection
 */
class NegotiatorMultithreadedTest {
    @DisplayName("Negotiate and run a protocol on 2 threads talking to each other")
    @Test
    void runProtocol() throws Exception {
        final NegotiatorPair pair = new NegotiatorPair(new TestPeerProtocol()
                .setShouldAccept(true)
                .setShouldInitiate(true)
                .setAcceptOnSimultaneousInitiate(true));
        pair.start();
        await().atMost(5, TimeUnit.SECONDS).until(pair.threadsDone());
        pair.assertTimesRan(1);
        pair.assertHandshakeRan();
        pair.finish();
    }

    @DisplayName("Both negotiators send keepalive because there is no protocol to initiate")
    @Test
    void keepalive() throws Exception {
        final Pair<Connection, Connection> connections =
                ConnectionFactory.createLocalConnections(NodeId.of(0L), NodeId.of(1));
        final NegotiatorPair pair = new NegotiatorPair(
                new TestPeerProtocol().setShouldInitiate(false),
                Pair.of(new ExpiringConnection(connections.left(), 1), new ExpiringConnection(connections.right(), 1)));
        pair.start();
        await().atMost(5, TimeUnit.SECONDS).until(pair.threadsDone());
        pair.assertTimesRan(0);
        pair.assertHandshakeRan();
        pair.finish();
    }
}
