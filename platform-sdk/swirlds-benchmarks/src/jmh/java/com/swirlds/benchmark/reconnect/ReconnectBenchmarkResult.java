// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark.reconnect;

import com.swirlds.benchmark.reconnect.network.SimulatedNetworkStats;
import com.swirlds.virtualmap.VirtualMap;

public record ReconnectBenchmarkResult(
        VirtualMap reconnectedMap,
        AtomicReconnectMapStats reconnectStats,
        SimulatedNetworkStats teacherToLearnerStats,
        SimulatedNetworkStats learnerToTeacherStats) {}
