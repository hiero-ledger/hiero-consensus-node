// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark.reconnect.network;

/**
 * Selects the byte transport used by ReconnectBench.
 */
public enum NetworkTransport {
    /** Existing in-memory network model. */
    SIMULATED,

    /** Plain loopback TCP sockets configured through the gossip socket helper. */
    LOOPBACK_SOCKET
}
