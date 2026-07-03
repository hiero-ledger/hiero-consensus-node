// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark.reconnect.network;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.config.extensions.sources.SimpleConfigSource;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.hiero.consensus.gossip.config.GossipConfig;
import org.hiero.consensus.gossip.config.SocketConfig;
import org.junit.jupiter.api.Test;

class LoopbackSocketTransportTest {

    private static Configuration configuration() {
        return ConfigurationBuilder.create()
                .withSource(new SimpleConfigSource("socket.tcpNoDelay", "true"))
                .withConfigDataType(SocketConfig.class)
                .withConfigDataType(GossipConfig.class)
                .build();
    }

    private static NetworkSimulationConfig loopbackConfig() {
        return NetworkSimulationConfig.resolve(NetworkProfile.LOOPBACK, 0, 1, 1);
    }

    @Test
    void loopbackRoundTripsFramedBytesAndCountsThem() throws Exception {
        try (LoopbackSocketTransport transport = new LoopbackSocketTransport(loopbackConfig(), configuration())) {
            final DataOutputStream out = transport.getTeacherOutput();
            out.writeInt(4);
            out.write(new byte[] {1, 2, 3, 4});
            out.flush();

            final DataInputStream in = transport.getLearnerInput();
            assertEquals(4, in.readInt());
            final byte[] data = new byte[4];
            in.readFully(data);
            assertArrayEquals(new byte[] {1, 2, 3, 4}, data);

            assertEquals(8, transport.getTeacherToLearnerStats().bytesWritten());
            assertEquals(8, transport.getTeacherToLearnerStats().bytesRead());
        }
    }

    @Test
    void diagnosticsExposeEffectiveSocketSettings() throws Exception {
        try (LoopbackSocketTransport transport = new LoopbackSocketTransport(loopbackConfig(), configuration())) {
            final SocketTransportDiagnostics diagnostics = transport.diagnostics();

            assertEquals(NetworkTransport.LOOPBACK_SOCKET, diagnostics.transport());
            assertEquals(NetworkProfile.LOOPBACK, diagnostics.profile());
            assertFalse(diagnostics.latencyShapingActive());
            assertFalse(diagnostics.bandwidthShapingActive());
            assertTrue(diagnostics.inflightBytesLimitIgnored());
            assertTrue(diagnostics.serverReceiveBufferBytes() > 0);
            assertTrue(diagnostics.clientSendBufferBytes() > 0);
            assertTrue(diagnostics.clientReceiveBufferBytes() > 0);
            assertTrue(diagnostics.acceptedSendBufferBytes() > 0);
            assertTrue(diagnostics.acceptedReceiveBufferBytes() > 0);
            assertTrue(diagnostics.clientTcpNoDelay());
            assertTrue(diagnostics.acceptedTcpNoDelay());
        }
    }

    @Test
    void disconnectWakesBlockedReader() throws Exception {
        final LoopbackSocketTransport transport = new LoopbackSocketTransport(loopbackConfig(), configuration());
        try {
            final AtomicReference<Throwable> thrown = new AtomicReference<>();
            final CountDownLatch entered = new CountDownLatch(1);
            final Thread reader = new Thread(() -> {
                entered.countDown();
                try {
                    transport.getTeacherInput().read();
                } catch (final Throwable t) {
                    thrown.set(t);
                }
            });
            reader.setDaemon(true);
            reader.start();

            assertTrue(entered.await(5, TimeUnit.SECONDS), "reader thread should start");
            Thread.sleep(200);
            transport.disconnect();
            reader.join(5_000);

            assertFalse(reader.isAlive(), "disconnect should unblock the reader");
            assertTrue(thrown.get() instanceof IOException, "reader should fail with an IOException");
        } finally {
            transport.close();
        }
    }
}
