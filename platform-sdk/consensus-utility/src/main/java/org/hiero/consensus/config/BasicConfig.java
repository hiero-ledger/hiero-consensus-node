// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.config;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;

/**
 * Basic configuration data record. This record contains all general config properties that can not be defined for a
 * specific subsystem. The record is based on the definition of config data objects as described in {@link ConfigData}.
 *
 * <p>
 * Do not add new settings to this record unless you have a very good reason. New settings should go into config records
 * with a prefix defined by a {@link ConfigData} tag. Adding settings to this record pollutes the top level namespace.
 *
 * @param jvmPauseDetectorSleepMs period of JVMPauseDetectorThread sleeping in the unit of milliseconds
 * @param jvmPauseReportMs        log an error when JVMPauseDetectorThread detect a pause greater than this many
 *                                milliseconds all peers
 */
@ConfigData
public record BasicConfig(
        @ConfigProperty(defaultValue = "1000") int jvmPauseDetectorSleepMs,
        @ConfigProperty(defaultValue = "1000") int jvmPauseReportMs) {}
