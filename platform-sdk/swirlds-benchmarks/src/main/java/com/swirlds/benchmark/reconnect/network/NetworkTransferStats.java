// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark.reconnect.network;

/** Directional byte counts observed by the reconnect benchmark's socket transport. */
public record NetworkTransferStats(long bytesWritten, long bytesRead) {}
