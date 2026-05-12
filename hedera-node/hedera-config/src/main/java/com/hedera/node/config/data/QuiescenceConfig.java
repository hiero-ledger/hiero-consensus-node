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
 * @param gracePeriod the amount of time the controller must observe no transactions (neither pipeline nor pending)
 * before reporting {@link org.hiero.consensus.model.quiescence.QuiescenceCommand#QUIESCE}. During this window the
 * controller reports {@link org.hiero.consensus.model.quiescence.QuiescenceCommand#DONT_QUIESCE}, keeping the platform
 * active so it can drain its tx pool and emit normal events. A non-zero value avoids racy single-shot BREAK_QUIESCENCE
 * cycles that can stall the network when traffic is sparse.
 */
@ConfigData("quiescence")
public record QuiescenceConfig(
        @ConfigProperty(defaultValue = "true") boolean enabled,
        @ConfigProperty(defaultValue = "5s") Duration tctDuration,
        @ConfigProperty(defaultValue = "5s") Duration gracePeriod) {}
