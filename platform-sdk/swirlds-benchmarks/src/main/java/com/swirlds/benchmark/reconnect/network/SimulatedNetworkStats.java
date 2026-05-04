// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark.reconnect.network;

public record SimulatedNetworkStats(long bytesWritten, long bytesRead, long maxInflightBytes) {}
