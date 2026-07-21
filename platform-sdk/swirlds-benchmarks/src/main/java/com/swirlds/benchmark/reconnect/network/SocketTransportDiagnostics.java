// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark.reconnect.network;

/**
 * Construction-time snapshot of the effective socket-transport settings, logged once per reconnect run so refined-A1
 * experiments are auditable. Buffer values are the post-connect/accept readbacks (OS-clamped) and may autotune
 * further under load.
 *
 * @param profile the selected network profile
 * @param visibilitySchedulingActive whether the sender observer and receiver gate are installed
 * @param latencyShapingActive whether the gate applies a positive sender-relative visibility latency
 * @param bandwidthShapingActive whether the gate progressively releases bytes at a finite bandwidth
 * @param configuredLatencyNanos supplied target one-way latency, including under control profiles
 * @param configuredBandwidthBytesPerSecond supplied target bandwidth, including under control profiles
 * @param modeledLatencyNanos effective one-way visibility latency
 * @param modeledBandwidthBytesPerSecond effective visibility bandwidth; {@link Long#MAX_VALUE} means unlimited
 * @param releaseQuantumNanos target-derived maximum algorithmic release quantum; zero for raw loopback
 * @param maxObservedRangeBytes largest sender range published before one raw write; zero for raw loopback
 * @param streamBufferBytes Java stream buffer size ({@code SocketConfig.bufferSize()}), distinct from the kernel
 *     socket buffers
 * @param serverReceiveBufferBytes listening socket receive buffer (inherited by the accepted socket)
 * @param clientSendBufferBytes teacher/client socket send buffer (SocketFactory-configured)
 * @param clientReceiveBufferBytes teacher/client socket receive buffer (SocketFactory-configured)
 * @param acceptedSendBufferBytes learner/accepted socket send buffer (never set by SocketFactory; OS default,
 *     matching production)
 * @param acceptedReceiveBufferBytes learner/accepted socket receive buffer (inherited from the listening socket)
 * @param clientTcpNoDelay whether TCP_NODELAY is set on the teacher/client socket
 * @param acceptedTcpNoDelay whether TCP_NODELAY is set on the learner/accepted socket
 */
public record SocketTransportDiagnostics(
        NetworkProfile profile,
        boolean visibilitySchedulingActive,
        boolean latencyShapingActive,
        boolean bandwidthShapingActive,
        long configuredLatencyNanos,
        long configuredBandwidthBytesPerSecond,
        long modeledLatencyNanos,
        long modeledBandwidthBytesPerSecond,
        long releaseQuantumNanos,
        int maxObservedRangeBytes,
        int streamBufferBytes,
        int serverReceiveBufferBytes,
        int clientSendBufferBytes,
        int clientReceiveBufferBytes,
        int acceptedSendBufferBytes,
        int acceptedReceiveBufferBytes,
        boolean clientTcpNoDelay,
        boolean acceptedTcpNoDelay) {}
