// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.sources;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DynamicConfigSourceTest {

    @Test
    void testEmpty() {
        // given
        final DynamicConfigSource dynamicConfigSource = new DynamicConfigSource();

        // then
        assertThat(dynamicConfigSource).returns(500, DynamicConfigSource::getOrdinal);
        assertThat(dynamicConfigSource.getPropertyNames()).isEmpty();
    }

    @Test
    void testAddProperty() {
        // given
        final DynamicConfigSource dynamicConfigSource = new DynamicConfigSource();

        // when
        dynamicConfigSource.setProperty("test", "testValue");

        // then
        assertThat(dynamicConfigSource.getPropertyNames()).isNotEmpty();
        assertThat(dynamicConfigSource.getPropertyNames()).contains("test");
        assertThat(dynamicConfigSource.getValue("test")).isEqualTo("testValue");
    }

    @Test
    void testSetProperty() {
        // given
        final DynamicConfigSource dynamicConfigSource = new DynamicConfigSource();
        dynamicConfigSource.setProperty("test", "testValue");

        // when
        dynamicConfigSource.setProperty("test", "testValue2");

        // then
        assertThat(dynamicConfigSource.getPropertyNames()).isNotEmpty();
        assertThat(dynamicConfigSource.getPropertyNames()).contains("test");
        assertThat(dynamicConfigSource.getValue("test")).isEqualTo("testValue2");
    }

    @Test
    void testRemoveProperty() {
        // given
        final DynamicConfigSource dynamicConfigSource = new DynamicConfigSource();
        dynamicConfigSource.setProperty("test", "testValue");

        // when
        dynamicConfigSource.removeProperty("test");

        // then
        assertThat(dynamicConfigSource.getPropertyNames()).isEmpty();
    }
}
