// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark.reconnect;

import com.swirlds.benchmark.reconnect.network.SimulatedNetworkStats;
import com.swirlds.virtualmap.VirtualMap;

/**
 * The learner state and diagnostics produced by one reconnect benchmark invocation.
 *
 * <p>The result keeps the synchronized map together with snapshots of the reconnect and simulated-network metrics so
 * the benchmark can verify the map and report the work performed in both network directions after synchronization.
 *
 * @param reconnectedMap the learner map produced by synchronization
 * @param reconnectStats reconnect traversal metrics captured after synchronization
 * @param teacherToLearnerStats simulated-network metrics for data sent by the teacher
 * @param learnerToTeacherStats simulated-network metrics for data sent by the learner
 */
public record ReconnectBenchmarkResult(
        VirtualMap reconnectedMap,
        ReconnectMapStatsSnapshot reconnectStats,
        SimulatedNetworkStats teacherToLearnerStats,
        SimulatedNetworkStats learnerToTeacherStats) {}
