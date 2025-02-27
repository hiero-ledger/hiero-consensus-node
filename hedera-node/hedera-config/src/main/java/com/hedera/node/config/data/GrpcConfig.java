// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.data;

import com.hedera.node.config.NodeProperty;
import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import com.swirlds.config.api.validation.annotation.Max;
import com.swirlds.config.api.validation.annotation.Min;

/**
 * @param port The port for plain grpc traffic. Must be non-negative. A value of 0 indicates an ephemeral port should be
 *             automatically selected by the computer. Must not be the same value as {@link #tlsPort()} unless both are
 *             0. Must be a value between 0 and 65535, inclusive.
 * @param tlsPort The port for tls-encrypted grpc traffic. Must be non-negative. A value of 0 indicates an ephemeral
 *                port should be automatically selected by the computer. Must not be the same value as {@link #port()}
 *                unless both are 0. Must be a value between 0 and 65535, inclusive.
 * @param nodeOperatorPortEnabled Whether the node operator port is enabled. If true, the node operator port will be
 *                                enabled and must be a non-negative value between 1 and 65535, inclusive. If false, the
 *                                node operator port will be disabled and the value of {@link #nodeOperatorPort()} will be
 *                                ignored.
 * @param nodeOperatorPort The port for the node operator. Must be a non-negative value between 1 and 65535, inclusive.
 * @param workflowsPort Deprecated
 * @param workflowsTlsPort Deprecated
 * @param maxMessageSize The maximum message size in bytes that the server can receive. Must be non-negative.
 * @param maxResponseSize The maximum message size in bytes that the server can send in the response.
 *             Must be non-negative. Defaults to 4MB.
 * @param noopMarshallerMaxMessageSize The maximum message size in bytes that the server can receive when using a no-op
 *             serialization strategy. Must be non-negative. Defaults to 4MB.
 */
@ConfigData("grpc")
public record GrpcConfig(
        @ConfigProperty(defaultValue = "50211") @Min(0) @Max(65535) @NodeProperty int port,
        @ConfigProperty(defaultValue = "50212") @Min(0) @Max(65535) @NodeProperty int tlsPort,
        @ConfigProperty(defaultValue = "true") @NodeProperty boolean nodeOperatorPortEnabled,
        @ConfigProperty(defaultValue = "50213") @Min(1) @Max(65535) @NodeProperty int nodeOperatorPort,
        @ConfigProperty(defaultValue = "60211") @Min(0) @Max(65535) @NodeProperty int workflowsPort,
        @ConfigProperty(defaultValue = "60212") @Min(0) @Max(65535) @NodeProperty int workflowsTlsPort,
        @ConfigProperty(defaultValue = "4194304") @Max(4194304) @Min(0) int maxMessageSize,
        @ConfigProperty(defaultValue = "4194304") @Max(4194304) @Min(0) int maxResponseSize,
        @ConfigProperty(defaultValue = "4194304") @Max(4194304) @Min(0) int noopMarshallerMaxMessageSize) {

    public GrpcConfig {
        validateFieldRange(port, 0, 65535, "port");
        validateFieldRange(tlsPort, 0, 65535, "tlsPort");
        validateFieldRange(workflowsPort, 0, 65535, "workflowsPort");
        validateFieldRange(workflowsTlsPort, 0, 65535, "workflowsTlsPort");
        validateFieldRange(maxMessageSize, 0, 4194304, "maxMessageSize");
        validateFieldRange(maxResponseSize, 0, 4194304, "maxResponseSize");
        validateFieldRange(noopMarshallerMaxMessageSize, 0, 4194304, "noopMarshallerMaxMessageSize");
        validateUniquePorts(port, tlsPort);
        validateUniqueWorkflowsPorts(workflowsPort, workflowsTlsPort);
    }

    private void validateFieldRange(int value, int minValue, int maxValue, String fieldName) {
        if (value < minValue || value > maxValue) {
            throw new IllegalArgumentException(
                    "grpc." + fieldName + " must be between " + minValue + " and " + maxValue);
        }
    }

    private void validateUniquePorts(int port1, int port2) {
        if (port1 == port2 && port1 != 0) {
            throw new IllegalArgumentException("grpc.port and grpc.tlsPort must be different");
        }
    }

    private void validateUniqueWorkflowsPorts(int port1, int port2) {
        if (port1 == port2 && port1 != 0) {
            throw new IllegalArgumentException("grpc.workflowsPort and grpc.workflowsTlsPort must be different");
        }

        if (nodeOperatorPortEnabled && (nodeOperatorPort == port || nodeOperatorPort == tlsPort)) {
            throw new IllegalArgumentException(
                    "grpc.nodeOperatorPort must be different from grpc.port and grpc.tlsPort");
        }
    }
}
