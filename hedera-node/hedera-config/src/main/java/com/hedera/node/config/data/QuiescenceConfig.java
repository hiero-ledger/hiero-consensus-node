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
 * {@link org.hiero.consensus.model.quiescence.QuiescenceCommand#QUIESCE}. While both the pipeline and pending
 * counts are zero, the controller reports {@code DONT_QUIESCE} until this duration has elapsed since the most
 * recently observed transaction activity. Prevents short inter-transaction gaps from putting the network to
 * sleep — without this, the next user transaction wakes the network and consensus time jumps forward by the
 * sleep duration, which can mass-expire entries in the record cache.
 */
@ConfigData("quiescence")
public record QuiescenceConfig(
        @ConfigProperty(defaultValue = "false") boolean enabled,
        @ConfigProperty(defaultValue = "5s") Duration tctDuration,
        @ConfigProperty(defaultValue = "5s") Duration gracePeriod) {}
