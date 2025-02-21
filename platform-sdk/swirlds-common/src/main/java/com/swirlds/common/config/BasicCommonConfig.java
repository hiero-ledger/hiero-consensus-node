// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.config;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;

/**
 * Basic configuration data record. This record contains all general config properties that can not be defined for a
 * specific subsystem. The record is based on the definition of config data objects as described in {@link ConfigData}.
 *
 * <p>
 * Do not add new settings to this record unless you have a very good reason. New settings should go
 * into config records with a prefix defined by a {@link ConfigData} tag. Adding
 * settings to this record pollutes the top level namespace.
 *
 * @param showInternalStats
 * 		show the user all statistics, including those with category "internal"?
 * @param verboseStatistics
 * 		show expand statistics values, include mean, min, max, stdDev
 */
@ConfigData
public record BasicCommonConfig(
        @ConfigProperty(defaultValue = "true") boolean showInternalStats,
        @ConfigProperty(defaultValue = "false") boolean verboseStatistics) {}
