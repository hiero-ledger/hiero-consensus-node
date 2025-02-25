// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.stress;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import com.swirlds.config.api.validation.annotation.Positive;
import java.time.Duration;

/**
 * Configuration for the Stress Testing Tool
 *
 * @param bytesPerTrans bytes in each transaction
 * @param transPerSecToCreate number of transactions to create per second
 * @param transPoolSize the size of the transaction pool
 * @param handleTime simulated handle time for each consensus transaction
 * @param preHandleTime simulated handle time for each consensus transaction
 */
@ConfigData("stressTestingTool")
public record StressTestingToolConfig(
        @ConfigProperty(defaultValue = "100") @Positive int bytesPerTrans,
        @ConfigProperty(defaultValue = "500") @Positive int transPerSecToCreate,
        @ConfigProperty(defaultValue = "1024") @Positive int transPoolSize,
        @ConfigProperty(defaultValue = "0s") Duration handleTime,
        @ConfigProperty(defaultValue = "0s") Duration preHandleTime) {}
