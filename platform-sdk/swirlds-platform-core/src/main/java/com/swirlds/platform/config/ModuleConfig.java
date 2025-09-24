// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.config;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import org.hiero.consensus.event.creator.EventCreatorModule;

/**
 * Configuration related to builder settings.
 *
 * @param eventCreatorModule the implementation class name for the {@link EventCreatorModule}
 */
@ConfigData("platform.builder")
public record ModuleConfig(@ConfigProperty(defaultValue = "") String eventCreatorModule) {}
