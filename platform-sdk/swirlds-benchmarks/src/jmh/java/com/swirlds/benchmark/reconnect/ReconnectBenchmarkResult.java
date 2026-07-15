// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark.reconnect;

import com.swirlds.benchmark.reconnect.network.NetworkTransferStats;
import com.swirlds.virtualmap.VirtualMap;

public record ReconnectBenchmarkResult(
        VirtualMap reconnectedMap,
        ReconnectMapStatsSnapshot reconnectStats,
        NetworkTransferStats teacherToLearnerStats,
        NetworkTransferStats learnerToTeacherStats) {}
