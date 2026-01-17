// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.system.status;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import java.time.Duration;

/**
 * Configuration for the platform status state machine
 *
 * @param observingStatusDelay              the amount of wall clock time to wait before transitioning out of the
 *                                          OBSERVING status
 * @param activeStatusDelay                 the amount of wall clock time that must be exceeded for transitioning
 *                                          out of the ACTIVE status when
 *                                             (1) not observing a self event reached during this period
 *  *                                          or (2) the quiescence command changed from QUIESCE to any of (BREAK_QUIESCENCE or DONT_QUIESCE).
 *  *
 * @param statusStateMachineHeartbeatPeriod the amount of wall clock time between heartbeats sent to the status state
 *                                          machine
 */
@ConfigData("platformStatus")
public record PlatformStatusConfig(
        @ConfigProperty(defaultValue = "10s") Duration observingStatusDelay,
        @ConfigProperty(defaultValue = "10s") Duration activeStatusDelay,
        @ConfigProperty(defaultValue = "100ms") Duration statusStateMachineHeartbeatPeriod) {}
