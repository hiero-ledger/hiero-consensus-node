// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark.reconnect.network;

public record SocketTransportDiagnostics(
        NetworkTransport transport,
        NetworkProfile profile,
        boolean latencyShapingActive,
        boolean bandwidthShapingActive,
        long configuredLatencyNanos,
        long configuredBandwidthBytesPerSecond,
        boolean inflightBytesLimitIgnored,
        int streamBufferBytes,
        int serverReceiveBufferBytes,
        int clientSendBufferBytes,
        int clientReceiveBufferBytes,
        int acceptedSendBufferBytes,
        int acceptedReceiveBufferBytes,
        boolean clientTcpNoDelay,
        boolean acceptedTcpNoDelay) {}
