// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.utils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class SimulatedNetworkProxy implements AutoCloseable {
    private final int targetPort;
    private final int latencyMs;
    private final long bandwidthBytesPerSec;
    private final double packetLossRate;

    private ServerSocket serverSocket;
    private final ExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private int listenPort;

    public SimulatedNetworkProxy(int targetPort, int latencyMs, double bandwidthMbps, double packetLossRate) {
        this.targetPort = targetPort;
        this.latencyMs = latencyMs;
        // Convert Mbps to Bytes/sec
        this.bandwidthBytesPerSec = bandwidthMbps > 0 ? (long) ((bandwidthMbps * 1_000_000) / 8) : 0;
        this.packetLossRate = packetLossRate;
        this.executor = Executors.newCachedThreadPool();
    }

    public void start() throws IOException {
        this.serverSocket = new ServerSocket(0, 50, InetAddress.getByName("localhost"));
        this.listenPort = serverSocket.getLocalPort();
        this.running.set(true);

        // Accept loop
        executor.submit(() -> {
            while (running.get()) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    clientSocket.setTcpNoDelay(true);

                    Socket serverSocket = new Socket("localhost", targetPort);
                    serverSocket.setTcpNoDelay(true);

                    // Handle Latency (Simulate RTT/Handshake delay) ONCE per connection
                    if (latencyMs > 0) {
                        try {
                            Thread.sleep(latencyMs);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }

                    // Pipe Client -> Server (Upstream)
                    executor.submit(() -> pipe(clientSocket, serverSocket));

                    // Pipe Server -> Client (Downstream)
                    executor.submit(() -> pipe(serverSocket, clientSocket));

                } catch (IOException e) {
                    if (running.get()) e.printStackTrace();
                }
            }
        });

        System.out.printf(
                "Proxy started on port %d -> forwarding to %d (Lat: %dms start delay)%n",
                listenPort, targetPort, latencyMs);
    }

    public int getPort() {
        return listenPort;
    }

    private void pipe(Socket inSocket, Socket outSocket) {
        try (InputStream in = inSocket.getInputStream();
                OutputStream out = outSocket.getOutputStream()) {

            byte[] buffer = new byte[32 * 1024]; // 32KB buffer
            int bytesRead;
            long totalBytes = 0;
            long startTime = System.nanoTime();

            while (running.get() && (bytesRead = in.read(buffer)) != -1) {

                // 1. Simulate Packet Loss (Random Drop/Retransmit Wait)
                if (packetLossRate > 0 && Math.random() < packetLossRate) {
                    // Simulate a TCP timeout (wait 200ms then continue)
                    Thread.sleep(200);
                }

                // 2. Simulate Bandwidth Throttling
                if (bandwidthBytesPerSec > 0) {
                    totalBytes += bytesRead;
                    // Expected time to transfer 'totalBytes' at 'bandwidthBytesPerSec'
                    long expectedTimeNanos = (long) ((double) totalBytes / bandwidthBytesPerSec * 1_000_000_000.0);
                    long actualTimeNanos = System.nanoTime() - startTime;

                    if (actualTimeNanos < expectedTimeNanos) {
                        long sleepNanos = expectedTimeNanos - actualTimeNanos;
                        long sleepMs = sleepNanos / 1_000_000;
                        int sleepNs = (int) (sleepNanos % 1_000_000);
                        Thread.sleep(sleepMs, sleepNs);
                    }
                }

                out.write(buffer, 0, bytesRead);
                out.flush();
            }
        } catch (SocketException e) {
            // Socket closed normally
        } catch (Exception e) {
            if (running.get()) e.printStackTrace();
        } finally {
            try {
                inSocket.close();
            } catch (IOException ignored) {
            }
            try {
                outSocket.close();
            } catch (IOException ignored) {
            }
        }
    }

    @Override
    public void close() throws Exception {
        running.set(false);
        if (serverSocket != null) serverSocket.close();
        executor.shutdownNow();
    }
}
