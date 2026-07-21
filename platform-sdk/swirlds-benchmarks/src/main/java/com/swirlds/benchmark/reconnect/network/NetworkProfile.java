// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark.reconnect.network;

/** Selects how the reconnect benchmark's loopback socket transport models network visibility. */
public enum NetworkProfile {
    /** Apply refined-A1 sender observation and receiver visibility scheduling with the configured latency/bandwidth. */
    REALISTIC,

    /**
     * Install the same observer/gate path and range splitting as {@link #REALISTIC}, but make all bytes immediately
     * eligible. This isolates instrumentation overhead from modeled network delay.
     */
    INSTRUMENTED_LOOPBACK,

    /** Use the raw loopback socket streams without refined-A1 instrumentation or shaping. */
    LOOPBACK
}
