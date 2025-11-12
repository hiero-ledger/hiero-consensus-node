package com.hedera.node.app.blocks.impl.streaming;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

public class BlockNodeConfigurationTest {

    @Test
    void testNullAddress() {
        assertThatThrownBy(() -> new BlockNodeConfiguration(null, 8080, 0, 1_000, 2_000))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("Address must be specified");
    }
}
