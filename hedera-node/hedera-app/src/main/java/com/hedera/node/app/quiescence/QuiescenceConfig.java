// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.quiescence;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import java.time.Duration;

/**
 * Configuration for quiescence.
 *
 * @param enabled     indicates if quiescence is enabled
 * @param tctDuration the amount of time before the target consensus timestamp (TCT) when quiescence should not be
 *                    active
 */
@ConfigData("quiescence")
public record QuiescenceConfig(
        @ConfigProperty(defaultValue = "false") boolean enabled,
        @ConfigProperty(defaultValue = "5s") Duration tctDuration) {}
