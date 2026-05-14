// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.data;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import java.time.Duration;

/**
 * Configuration for quiescence.
 *
 * @param enabled indicates if quiescence is enabled
 * @param tctDuration the amount of time before the target consensus timestamp (TCT) when quiescence should not be
 * active
 * @param gracePeriod minimum duration of observed inactivity required before the controller will report
 * {@link org.hiero.consensus.model.quiescence.QuiescenceCommand#QUIESCE}. The controller reports
 * {@link org.hiero.consensus.model.quiescence.QuiescenceCommand#DONT_QUIESCE} until this duration has elapsed since
 * the most recently observed transaction activity.
 */
@ConfigData("quiescence")
public record QuiescenceConfig(
        @ConfigProperty(defaultValue = "true") boolean enabled,
        @ConfigProperty(defaultValue = "5s") Duration tctDuration,
        @ConfigProperty(defaultValue = "5s") Duration gracePeriod) {}
