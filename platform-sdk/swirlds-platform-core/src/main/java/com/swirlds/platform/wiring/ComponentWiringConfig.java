// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.wiring;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;

/**
 * Configuration related to how platform components are wired together.
 *
 * @param inlinePces if true, pre-consensus events will be written to disk before being gossipped, this will ensure that
 *                   a node can never lose an event that it has created due to a crash
 */
@ConfigData("platformWiring")
public record ComponentWiringConfig(@ConfigProperty(defaultValue = "true") boolean inlinePces) {}
