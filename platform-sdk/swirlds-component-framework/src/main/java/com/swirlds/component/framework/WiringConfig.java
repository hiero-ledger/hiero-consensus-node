// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import java.time.Duration;

/**
 * Configuration for the wiring framework.
 *
 * @param healthMonitorEnabled           whether the health monitor is enabled
 * @param hardBackpressureEnabled        whether hard backpressure is enabled
 * @param defaultPoolMultiplier          used when calculating the size of the default platform fork join pool. Maximum
 *                                       parallelism in this pool is calculated as max(1, (defaultPoolMultiplier *
 *                                       [number of processors] + defaultPoolConstant)).
 * @param defaultPoolConstant            used when calculating the size of the default platform fork join pool. Maximum
 *                                       parallelism in this pool is calculated as max(1, (defaultPoolMultiplier *
 *                                       [number of processors] + defaultPoolConstant)). It is legal for this constant
 *                                       to be a negative number.
 * @param healthMonitorSchedulerCapacity the unhandled task capacity of the health monitor's scheduler
 * @param healthMonitorHeartbeatPeriod   the period between heartbeats sent to the health monitor
 * @param healthLogThreshold             the amount of time a scheduler may be unhealthy before the platform is
 *                                       considered to be unhealthy and starts to write log warnings
 * @param healthLogPeriod                the minimum amount of time that must pass between health log messages for the
 *                                       same scheduler
 * @param healthyReportThreshold         indicates how long between two consecutive reports when the system is healthy.
 *
 */
@ConfigData("platform.wiring")
public record WiringConfig(
        @ConfigProperty(defaultValue = "true") boolean healthMonitorEnabled,
        @ConfigProperty(defaultValue = "false") boolean hardBackpressureEnabled,
        @ConfigProperty(defaultValue = "0") double defaultPoolMultiplier,
        @ConfigProperty(defaultValue = "8") int defaultPoolConstant,
        @ConfigProperty(defaultValue = "500") int healthMonitorSchedulerCapacity,
        @ConfigProperty(defaultValue = "1ms") Duration healthMonitorHeartbeatPeriod,
        @ConfigProperty(defaultValue = "1s") Duration healthLogThreshold,
        @ConfigProperty(defaultValue = "10m") Duration healthLogPeriod,
        @ConfigProperty(defaultValue = "1s") Duration healthyReportThreshold) {}
