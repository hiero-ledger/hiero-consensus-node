// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark.reconnect.network;

/**
 * Construction-time snapshot of the effective socket-transport settings, logged once per reconnect run so socket
 * experiments are auditable. Buffer values are the post-connect/accept readbacks (OS-clamped); they may autotune
 * further under load — the live per-window values appear in the end-of-run pacing summary, not here.
 *
 * @param profile the selected network profile
 * @param latencyShapingActive whether read-side latency pacing (RTT-windowed release by {@link PacingInputStream})
 *     is active — REALISTIC profile with a positive latency
 * @param bandwidthShapingActive whether read-side bandwidth pacing (release-then-wait cursor) is active — REALISTIC
 *     profile with a finite bandwidth
 * @param configuredLatencyNanos configured one-way latency; the pacer's window period (RTT) is twice this value
 * @param configuredBandwidthBytesPerSecond configured bandwidth cap
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
        boolean latencyShapingActive,
        boolean bandwidthShapingActive,
        long configuredLatencyNanos,
        long configuredBandwidthBytesPerSecond,
        int streamBufferBytes,
        int serverReceiveBufferBytes,
        int clientSendBufferBytes,
        int clientReceiveBufferBytes,
        int acceptedSendBufferBytes,
        int acceptedReceiveBufferBytes,
        boolean clientTcpNoDelay,
        boolean acceptedTcpNoDelay) {}
