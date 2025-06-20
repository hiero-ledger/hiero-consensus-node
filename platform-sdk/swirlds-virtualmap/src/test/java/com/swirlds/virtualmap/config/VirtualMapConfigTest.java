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
    public void testMaximumVirtualMapSizeOutOfRangeMin() {
        // given
        final ConfigurationBuilder configurationBuilder = ConfigurationBuilder.create()
                .withSources(new SimpleConfigSource("virtualMap.maximumVirtualMapSize", 0L))
                .withSources(new SimpleConfigSource("virtualMap.virtualMapWarningThreshold", 0L))
                .withConfigDataType(VirtualMapConfig.class);

        // then
        final ConfigViolationException exception = Assertions.assertThrows(
                ConfigViolationException.class, () -> configurationBuilder.build(), "init must end in a violation");

        // the "virtualMapWarningThreshold" depends on the "maximumVirtualMapSize" and therefore we can not test it
        // alone
        Assertions.assertEquals(3, exception.getViolations().size(), "We must exactly have 3 violation");
    }

    @Test
    public void testMaximumVirtualMapSizeOutOfRangeMax() {
        // given
        final ConfigurationBuilder configurationBuilder = ConfigurationBuilder.create()
                .withSources(new SimpleConfigSource("virtualMap.maximumVirtualMapSize", Integer.MAX_VALUE + 1L))
                .withConfigDataType(VirtualMapConfig.class);

        // then
        final ConfigViolationException exception = Assertions.assertThrows(
                ConfigViolationException.class, () -> configurationBuilder.build(), "init must end in a violation");

        Assertions.assertEquals(1, exception.getViolations().size(), "We must exactly have 1 violation");
    }

    @Test
    public void testVirtualMapWarningIntervalOutOfRangeMin() {
        // given
        final ConfigurationBuilder configurationBuilder = ConfigurationBuilder.create()
                .withSources(new SimpleConfigSource("virtualMap.virtualMapWarningInterval", 0L))
                .withConfigDataType(VirtualMapConfig.class);

        // then
        final ConfigViolationException exception = Assertions.assertThrows(
                ConfigViolationException.class, () -> configurationBuilder.build(), "init must end in a violation");

        Assertions.assertEquals(1, exception.getViolations().size(), "We must exactly have 1 violation");
    }

    @Test
    public void testVirtualMapWarningThresholdOutOfRangeMin() {
        // given
        final ConfigurationBuilder configurationBuilder = ConfigurationBuilder.create()
                .withSources(new SimpleConfigSource("virtualMap.virtualMapWarningThreshold", 0L))
                .withSources(new SimpleConfigSource("virtualMap.virtualMapWarningInterval", -1L))
                .withConfigDataType(VirtualMapConfig.class);

        // then
        final ConfigViolationException exception = Assertions.assertThrows(
                ConfigViolationException.class, () -> configurationBuilder.build(), "init must end in a violation");

        // the "virtualMapWarningInterval" depends on the "virtualMapWarningThreshold" and therefore we can not test it
        // alone
        Assertions.assertEquals(2, exception.getViolations().size(), "We must exactly have 1 violation");
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
