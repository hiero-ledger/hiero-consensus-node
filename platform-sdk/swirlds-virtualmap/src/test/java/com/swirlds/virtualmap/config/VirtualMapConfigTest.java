// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.config;

import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.config.api.validation.ConfigViolationException;
import com.swirlds.config.extensions.sources.SimpleConfigSource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class VirtualMapConfigTest {

    @Test
    public void testDefaultValuesValid() {
        // given
        final ConfigurationBuilder configurationBuilder =
                ConfigurationBuilder.create().withConfigDataType(VirtualMapConfig.class);
        // then
        Assertions.assertDoesNotThrow(() -> configurationBuilder.build(), "All default values should be valid");
    }

    @Test
    public void testPercentHashThreadsOutOfRangeMin() {
        // given
        final ConfigurationBuilder configurationBuilder = ConfigurationBuilder.create()
                .withSources(new SimpleConfigSource("virtualMap.percentHashThreads", -1))
                .withConfigDataType(VirtualMapConfig.class);

        // then
        final ConfigViolationException exception = Assertions.assertThrows(
                ConfigViolationException.class, () -> configurationBuilder.build(), "init must end in a violation");

        Assertions.assertEquals(1, exception.getViolations().size(), "We must exactly have 1 violation");
    }

    @Test
    public void testPercentHashThreadsOutOfRangeMax() {
        // given
        final ConfigurationBuilder configurationBuilder = ConfigurationBuilder.create()
                .withSources(new SimpleConfigSource("virtualMap.percentHashThreads", 101))
                .withConfigDataType(VirtualMapConfig.class);

        // then
        final ConfigViolationException exception = Assertions.assertThrows(
                ConfigViolationException.class, () -> configurationBuilder.build(), "init must end in a violation");

        Assertions.assertEquals(1, exception.getViolations().size(), "We must exactly have 1 violation");
    }

    @Test
    public void testPercentCleanerThreadsOutOfRangeMin() {
        // given
        final ConfigurationBuilder configurationBuilder = ConfigurationBuilder.create()
                .withSources(new SimpleConfigSource("virtualMap.percentCleanerThreads", -1))
                .withConfigDataType(VirtualMapConfig.class);

        // then
        final ConfigViolationException exception = Assertions.assertThrows(
                ConfigViolationException.class, () -> configurationBuilder.build(), "init must end in a violation");

        Assertions.assertEquals(1, exception.getViolations().size(), "We must exactly have 1 violation");
    }

    @Test
    public void testPercentCleanerThreadsOutOfRangeMax() {
        // given
        final ConfigurationBuilder configurationBuilder = ConfigurationBuilder.create()
                .withSources(new SimpleConfigSource("virtualMap.percentCleanerThreads", 101))
                .withConfigDataType(VirtualMapConfig.class);

        // then
        final ConfigViolationException exception = Assertions.assertThrows(
                ConfigViolationException.class, () -> configurationBuilder.build(), "init must end in a violation");

        Assertions.assertEquals(1, exception.getViolations().size(), "We must exactly have 1 violation");
    }

    @Test
    public void testFlushIntervalOutOfRangeMin() {
        // given
        final ConfigurationBuilder configurationBuilder = ConfigurationBuilder.create()
                .withSources(new SimpleConfigSource("virtualMap.flushInterval", 0L))
                .withConfigDataType(VirtualMapConfig.class);

        // then
        final ConfigViolationException exception = Assertions.assertThrows(
                ConfigViolationException.class, () -> configurationBuilder.build(), "init must end in a violation");

        Assertions.assertEquals(1, exception.getViolations().size(), "We must exactly have 1 violation");
    }

    @Test
    public void testNumCleanerThreadsRangeMin() {
        // given
        final ConfigurationBuilder configurationBuilder = ConfigurationBuilder.create()
                .withSources(new SimpleConfigSource("virtualMap.numCleanerThreads", -2))
                .withConfigDataType(VirtualMapConfig.class);

        // then
        final ConfigViolationException exception = Assertions.assertThrows(
                ConfigViolationException.class, () -> configurationBuilder.build(), "init must end in a violation");

        Assertions.assertEquals(1, exception.getViolations().size(), "We must exactly have 1 violation");
    }
}
