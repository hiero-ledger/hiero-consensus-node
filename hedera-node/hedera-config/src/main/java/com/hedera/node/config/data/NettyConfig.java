// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.data;

import com.hedera.node.config.NodeProperty;
import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;

/**
 *
 * @param prodFlowControlWindow
 * @param prodMaxConcurrentCalls
 * @param prodMaxConnectionAge
 * @param prodMaxConnectionAgeGrace
 * @param prodMaxConnectionIdle
 * @param prodKeepAliveTime
 * @param prodKeepAliveTimeout
 * @param startRetries The number of times to retry starting the gRPC servers, if they fail to start. Defaults to 90.
 * @param startRetryIntervalMs The number of milliseconds between retries. Defaults to 1000ms. Minimum value is 1.
 * @param terminationTimeout The timeout, *in seconds*, to wait for the servers to terminate.
 * @param tlsCrtPath
 * @param tlsKeyPath
 */
@ConfigData("netty")
public record NettyConfig(
        // @ConfigProperty(defaultValue = "PROD") @NodeProperty Profile mode,
        @ConfigProperty(value = "prod.flowControlWindow", defaultValue = "10240") @NodeProperty
                int prodFlowControlWindow,
        @ConfigProperty(value = "prod.maxConcurrentCalls", defaultValue = "10") @NodeProperty
                int prodMaxConcurrentCalls,
        @ConfigProperty(value = "prod.maxConnectionAge", defaultValue = "15") @NodeProperty long prodMaxConnectionAge,
        @ConfigProperty(value = "prod.maxConnectionAgeGrace", defaultValue = "5") @NodeProperty
                long prodMaxConnectionAgeGrace,
        @ConfigProperty(value = "prod.maxConnectionIdle", defaultValue = "10") @NodeProperty long prodMaxConnectionIdle,
        @ConfigProperty(value = "prod.keepAliveTime", defaultValue = "60") @NodeProperty long prodKeepAliveTime,
        @ConfigProperty(value = "prod.keepAliveTimeout", defaultValue = "15") @NodeProperty long prodKeepAliveTimeout,
        @ConfigProperty(defaultValue = "90") @NodeProperty int startRetries,
        @ConfigProperty(defaultValue = "1000") @NodeProperty long startRetryIntervalMs,
        @ConfigProperty(defaultValue = "5") @NodeProperty long terminationTimeout,
        @ConfigProperty(value = "tlsCrt.path", defaultValue = "hedera.crt") @NodeProperty String tlsCrtPath,
        @ConfigProperty(value = "tlsKey.path", defaultValue = "hedera.key") @NodeProperty String tlsKeyPath) {
    public NettyConfig {
        if (startRetries < 0) {
            throw new IllegalArgumentException("startRetries must be non-negative.");
        }

        if (startRetryIntervalMs < 1) {
            throw new IllegalArgumentException("startRetryIntervalMs cannot be less than 1ms");
        }

        if (terminationTimeout < 0) {
            throw new IllegalArgumentException("terminationTimeout must be non-negative");
        }
    }
}
