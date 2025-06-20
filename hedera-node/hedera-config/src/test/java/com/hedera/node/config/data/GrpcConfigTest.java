// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.catchThrowable;

import org.junit.jupiter.api.Test;

class GrpcConfigTest {

    @Test
    void testValidConfiguration() {
        // Test valid configuration
        GrpcConfig config = new GrpcConfig(50211, 50212, true, 50213, 60211, 60212, 4194304, 4194304, 4194304);

        assertThat(config).isNotNull();
        assertThat(config.port()).isEqualTo(50211);
        assertThat(config.tlsPort()).isEqualTo(50212);
        assertThat(config.nodeOperatorPortEnabled()).isTrue();
        assertThat(config.nodeOperatorPort()).isEqualTo(50213);
        assertThat(config.workflowsPort()).isEqualTo(60211);
        assertThat(config.workflowsTlsPort()).isEqualTo(60212);
        assertThat(config.maxMessageSize()).isEqualTo(4194304);
        assertThat(config.maxResponseSize()).isEqualTo(4194304);
        assertThat(config.noopMarshallerMaxMessageSize()).isEqualTo(4194304);
    }

    @Test
    void testInvalidPortAndTlsPort() {
        Throwable throwable = catchThrowable(() -> {
            new GrpcConfig(50212, 50212, true, 50213, 50212, 60212, 4194304, 4194304, 4194304);
        });
        assertThat(throwable)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("grpc.port and grpc.tlsPort must be different");
    }

    @Test
    void testInvalidWorkflowsPortAndTlsPort() {
        Throwable throwable = catchThrowable(() -> {
            new GrpcConfig(50211, 50212, true, 50213, 60212, 60212, 4194304, 4194304, 4194304);
        });
        assertThat(throwable)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("grpc.workflowsPort and grpc.workflowsTlsPort must be different");
    }

    @Test
    void testValidZeroPorts() {
        GrpcConfig config = new GrpcConfig(0, 0, true, 50213, 60211, 60212, 4194304, 4194304, 4194304);

        assertThat(config).isNotNull();
        assertThat(config.port()).isEqualTo(0);
        assertThat(config.tlsPort()).isEqualTo(0);
        assertThat(config.workflowsPort()).isEqualTo(60211);
        assertThat(config.workflowsTlsPort()).isEqualTo(60212);
        assertThat(config.maxMessageSize()).isEqualTo(4194304);
        assertThat(config.maxResponseSize()).isEqualTo(4194304);
        assertThat(config.noopMarshallerMaxMessageSize()).isEqualTo(4194304);
    }

    @Test
    void testInvalidMinValue() {
        Throwable throwable = catchThrowable(() -> {
            new GrpcConfig(-1, 50212, true, 50213, 60211, 60212, 4194304, 5194304, 7194304);
        });

        assertThat(throwable)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("grpc.port must be between 0 and 65535");
    }

    @Test
    void testInvalidMaxValue() {
        Throwable throwable = catchThrowable(() -> {
            new GrpcConfig(65536, 50212, true, 50213, 60211, 60212, 4194304, 5194304, 7194304);
        });

        assertThat(throwable)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("grpc.port must be between 0 and 65535");
    }

    @Test
    void testMaxMessageSizeMaxValue() {
        Throwable throwable = catchThrowable(() -> {
            new GrpcConfig(50211, 50212, true, 50213, 60211, 60212, 4194305, 4194304, 4194304);
        });

        assertThat(throwable)
                .isNotNull()
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("grpc.maxMessageSize must be between 0 and 4194304");
    }

    @Test
    void testMaxResponseSizeMaxValue() {
        Throwable throwable = catchThrowable(() -> {
            new GrpcConfig(50211, 50212, true, 50213, 60211, 60212, 4194304, 4194305, 4194304);
        });

        assertThat(throwable)
                .isNotNull()
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("grpc.maxResponseSize must be between 0 and 4194304");
    }

    @Test
    void testNoopMarshallerMaxMessageSizeMaxValue() {
        Throwable throwable = catchThrowable(() -> {
            new GrpcConfig(50211, 50212, true, 50213, 60211, 60212, 4194304, 4194304, 4194305);
        });

        assertThat(throwable)
                .isNotNull()
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("grpc.noopMarshallerMaxMessageSize must be between 0 and 4194304");
    }
}
