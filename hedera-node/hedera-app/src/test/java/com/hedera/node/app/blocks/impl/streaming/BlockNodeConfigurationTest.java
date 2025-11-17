// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class BlockNodeConfigurationTest {

    @Test
    void testNullAddress() {
        assertThatThrownBy(() -> new BlockNodeConfiguration(null, 8080, 0, 1_000, 2_000))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("Address must be specified");
    }

    @Test
    void testEmptyAddress() {
        assertThatThrownBy(() -> new BlockNodeConfiguration("      ", 8080, 0, 1_000, 2_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Address must not be empty");
    }

    @Test
    void testBadPort() {
        assertThatThrownBy(() -> new BlockNodeConfiguration("localhost", 0, 0, 1_000, 2_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Port must be greater than or equal to 1");
    }

    @Test
    void testBadPriority() {
        assertThatThrownBy(() -> new BlockNodeConfiguration("localhost", 8080, -10, 1_000, 2_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Priority must be greater than or equal to 0");
    }

    @Test
    void testBadSoftLimitSize() {
        assertThatThrownBy(() -> new BlockNodeConfiguration("localhost", 8080, 0, 0, 2_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Message size soft limit must be greater than 0");
    }

    @Test
    void testBadHardLimitSize() {
        assertThatThrownBy(() -> new BlockNodeConfiguration("localhost", 8080, 0, 1_000, 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Message size hard limit must be greater than or equal to soft limit size");
    }

    @Test
    void testBuilder() {
        final BlockNodeConfiguration config = BlockNodeConfiguration.newBuilder()
                .address("localhost")
                .port(8080)
                .priority(1)
                .messageSizeSoftLimitBytes(2_000_000)
                .messageSizeHardLimitBytes(6_000_000)
                .build();

        assertThat(config).isNotNull();
        assertThat(config.address()).isEqualTo("localhost");
        assertThat(config.port()).isEqualTo(8080);
        assertThat(config.priority()).isEqualTo(1);
        assertThat(config.messageSizeSoftLimitBytes()).isEqualTo(2_000_000);
        assertThat(config.messageSizeHardLimitBytes()).isEqualTo(6_000_000);
    }
}
