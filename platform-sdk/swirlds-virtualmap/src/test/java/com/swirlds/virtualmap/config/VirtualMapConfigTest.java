// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.config;

import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.config.api.validation.ConfigViolationException;
import com.swirlds.config.extensions.sources.SimpleConfigSource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class VirtualMapConfigTest {

    @Test
    void testDefaultValuesValid() {
        // given
        final ConfigurationBuilder configurationBuilder =
                ConfigurationBuilder.create().withConfigDataType(VirtualMapConfig.class);
        // then
        Assertions.assertDoesNotThrow(() -> configurationBuilder.build(), "All default values should be valid");
    }

    @Test
    void testPercentHashThreadsOutOfRangeMin() {
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
    void testPercentHashThreadsOutOfRangeMax() {
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
    void testPercentCleanerThreadsOutOfRangeMin() {
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
    void testPercentCleanerThreadsOutOfRangeMax() {
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
    void testFlushIntervalOutOfRangeMin() {
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
    void testNumCleanerThreadsRangeMin() {
        // given
        final ConfigurationBuilder configurationBuilder = ConfigurationBuilder.create()
                .withSources(new SimpleConfigSource("virtualMap.numCleanerThreads", -2))
                .withConfigDataType(VirtualMapConfig.class);

        // then
        final ConfigViolationException exception = Assertions.assertThrows(
                ConfigViolationException.class, () -> configurationBuilder.build(), "init must end in a violation");

        Assertions.assertEquals(1, exception.getViolations().size(), "We must exactly have 1 violation");
    }

    @Test
    void testFamilyThrottleThresholdZero() {
        // given
        final Configuration config = ConfigurationBuilder.create()
                .withSources(new SimpleConfigSource("virtualMap.familyThrottleThreshold", 0))
                // familyThrottlePercent should be ignored
                .withSources(new SimpleConfigSource("virtualMap.familyThrottlePercent", 10.0))
                .withConfigDataType(VirtualMapConfig.class)
                .build();
        final VirtualMapConfig virtualMapConfig = config.getConfigData(VirtualMapConfig.class);

        // then
        Assertions.assertEquals(0, virtualMapConfig.getFamilyThrottleThreshold());
    }

    @Test
    void testFamilyThrottleThresholdNonZero() {
        final long value = 1_234_567_890;

        // given
        final Configuration config = ConfigurationBuilder.create()
                .withSources(new SimpleConfigSource("virtualMap.familyThrottleThreshold", value))
                // familyThrottlePercent should be ignored
                .withSources(new SimpleConfigSource("virtualMap.familyThrottlePercent", 10.0))
                .withConfigDataType(VirtualMapConfig.class)
                .build();
        final VirtualMapConfig virtualMapConfig = config.getConfigData(VirtualMapConfig.class);

        // then
        Assertions.assertEquals(value, virtualMapConfig.getFamilyThrottleThreshold());
    }

    @Test
    void testFamilyThrottlePercentZero() {
        // given
        final Configuration config = ConfigurationBuilder.create()
                // familyThrottleThreshold should be ignored
                .withSources(new SimpleConfigSource("virtualMap.familyThrottleThreshold", -1))
                .withSources(new SimpleConfigSource("virtualMap.familyThrottlePercent", 0))
                .withConfigDataType(VirtualMapConfig.class)
                .build();
        final VirtualMapConfig virtualMapConfig = config.getConfigData(VirtualMapConfig.class);

        // then
        Assertions.assertEquals(0, virtualMapConfig.getFamilyThrottleThreshold());
    }

    @Test
    void testFamilyThrottlePercentToHeapSize() {
        final double value = 10.0;
        final long maxHeapSize = Runtime.getRuntime().maxMemory();

        // given
        final Configuration config = ConfigurationBuilder.create()
                // familyThrottleThreshold should be ignored
                .withSources(new SimpleConfigSource("virtualMap.familyThrottleThreshold", -1))
                .withSources(new SimpleConfigSource("virtualMap.familyThrottlePercent", value))
                // Copy threshold should be ignored
                .withSources(new SimpleConfigSource("virtualMap.copyFlushCandidateThreshold", 1))
                .withConfigDataType(VirtualMapConfig.class)
                .build();
        final VirtualMapConfig virtualMapConfig = config.getConfigData(VirtualMapConfig.class);

        // then
        Assertions.assertEquals((long) (maxHeapSize * value / 100.0), virtualMapConfig.getFamilyThrottleThreshold());
    }

    @Test
    void testFamilyThrottlePercentToCopyThreshold() {
        final double value = 10.0;
        final long maxHeapSize = Runtime.getRuntime().maxMemory();

        // given
        final long copyFlushCandidateThreshold = (long) (maxHeapSize * value * 2 / 100.0);
        final Configuration config = ConfigurationBuilder.create()
                // familyThrottleThreshold should be ignored
                .withSources(new SimpleConfigSource("virtualMap.familyThrottleThreshold", -1))
                .withSources(new SimpleConfigSource("virtualMap.familyThrottlePercent", value))
                // Copy threshold should be used, since percent * heap size is less than copy threshold
                .withSources(
                        new SimpleConfigSource("virtualMap.copyFlushCandidateThreshold", copyFlushCandidateThreshold))
                .withConfigDataType(VirtualMapConfig.class)
                .build();
        final VirtualMapConfig virtualMapConfig = config.getConfigData(VirtualMapConfig.class);

        // then
        Assertions.assertEquals(copyFlushCandidateThreshold, virtualMapConfig.getFamilyThrottleThreshold());
    }

    @Test
    void testFamilyThrottleNegativeThrows() {
        // given
        final Configuration config = ConfigurationBuilder.create()
                // familyThrottleThreshold should be ignored
                .withSources(new SimpleConfigSource("virtualMap.familyThrottleThreshold", -1))
                .withSources(new SimpleConfigSource("virtualMap.familyThrottlePercent", -1))
                .withConfigDataType(VirtualMapConfig.class)
                .build();
        final VirtualMapConfig virtualMapConfig = config.getConfigData(VirtualMapConfig.class);

        // then
        Assertions.assertThrows(IllegalArgumentException.class, virtualMapConfig::getFamilyThrottleThreshold);
    }
}
