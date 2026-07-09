// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.config;

import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.config.api.validation.ConfigViolation;
import com.swirlds.config.api.validation.ConfigViolationException;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class VirtualMapConfigTest {

    private void verifyPropertyViolation(ConfigurationBuilder builder, String propertyName) {
        final ConfigViolationException exception =
                Assertions.assertThrows(ConfigViolationException.class, builder::build, "init must end in a violation");

        List<ConfigViolation> violations = exception.getViolations();
        Assertions.assertEquals(1, violations.size(), "We must exactly have 1 violation");
        Assertions.assertEquals(propertyName, violations.get(0).getPropertyName());
    }

    @Test
    void testDefaultValuesValid() {
        // given
        final ConfigurationBuilder configurationBuilder =
                ConfigurationBuilder.create().withConfigDataType(VirtualMapConfig.class);
        // then
        Assertions.assertDoesNotThrow(configurationBuilder::build, "All default values should be valid");
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "101"})
    void testPercentHashThreadsOutOfRange(String value) {
        // given
        final ConfigurationBuilder configurationBuilder = ConfigurationBuilder.create()
                .withValue(VirtualMapConfig_.PERCENT_HASH_THREADS, value)
                .withConfigDataType(VirtualMapConfig.class);

        // then
        verifyPropertyViolation(configurationBuilder, VirtualMapConfig_.PERCENT_HASH_THREADS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "101"})
    void testPercentCleanerThreadsOutOfRange(String value) {
        // given
        final ConfigurationBuilder configurationBuilder = ConfigurationBuilder.create()
                .withValue(VirtualMapConfig_.PERCENT_CLEANER_THREADS, value)
                .withConfigDataType(VirtualMapConfig.class);

        // then
        verifyPropertyViolation(configurationBuilder, VirtualMapConfig_.PERCENT_CLEANER_THREADS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "0"})
    void testFlushThresholdOutOfRangeMin(String value) {
        // given
        final ConfigurationBuilder configurationBuilder = ConfigurationBuilder.create()
                .withValue(VirtualMapConfig_.COPY_FLUSH_CANDIDATE_THRESHOLD, value)
                .withConfigDataType(VirtualMapConfig.class);

        // then
        verifyPropertyViolation(configurationBuilder, VirtualMapConfig_.COPY_FLUSH_CANDIDATE_THRESHOLD);
    }

    @Test
    void testFlushThresholdMinAllowed() {
        // given
        final ConfigurationBuilder configurationBuilder = ConfigurationBuilder.create()
                .withValue(VirtualMapConfig_.COPY_FLUSH_CANDIDATE_THRESHOLD, "1")
                .withConfigDataType(VirtualMapConfig.class);

        // then
        Assertions.assertDoesNotThrow(configurationBuilder::build, "init must be successful");
    }

    @Test
    void testNumCleanerThreadsRangeMin() {
        // given
        final ConfigurationBuilder configurationBuilder = ConfigurationBuilder.create()
                .withValue(VirtualMapConfig_.NUM_CLEANER_THREADS, "-2")
                .withConfigDataType(VirtualMapConfig.class);

        // then
        verifyPropertyViolation(configurationBuilder, VirtualMapConfig_.NUM_CLEANER_THREADS);
    }

    @Test
    void testValueParseMaxSizeOutOfRangeMin() {
        // given
        final ConfigurationBuilder configurationBuilder = ConfigurationBuilder.create()
                .withValue(VirtualMapConfig_.VALUE_PARSE_MAX_SIZE_BYTES, "0")
                .withConfigDataType(VirtualMapConfig.class);

        // then
        verifyPropertyViolation(configurationBuilder, VirtualMapConfig_.VALUE_PARSE_MAX_SIZE_BYTES);
    }

    @Test
    void testValueParseMaxSizeConfigured() {
        // given
        final int value = 40 * 1024 * 1024;
        final Configuration config = ConfigurationBuilder.create()
                .withValue(VirtualMapConfig_.VALUE_PARSE_MAX_SIZE_BYTES, String.valueOf(value))
                .withConfigDataType(VirtualMapConfig.class)
                .build();
        final VirtualMapConfig virtualMapConfig = config.getConfigData(VirtualMapConfig.class);

        // then
        Assertions.assertEquals(value, virtualMapConfig.valueParseMaxSizeBytes());
    }

    @Test
    void testFamilyThrottleThresholdZero() {
        // given
        final Configuration config = ConfigurationBuilder.create()
                .withValue(VirtualMapConfig_.FAMILY_THROTTLE_THRESHOLD, "0")
                // familyThrottlePercent should be ignored
                .withValue(VirtualMapConfig_.FAMILY_THROTTLE_PERCENT, "10.0")
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
                .withValue(VirtualMapConfig_.FAMILY_THROTTLE_THRESHOLD, String.valueOf(value))
                // familyThrottlePercent should be ignored
                .withValue(VirtualMapConfig_.FAMILY_THROTTLE_PERCENT, "10.0")
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
                .withValue(VirtualMapConfig_.FAMILY_THROTTLE_THRESHOLD, "-1")
                .withValue(VirtualMapConfig_.FAMILY_THROTTLE_PERCENT, "0")
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
                .withValue(VirtualMapConfig_.FAMILY_THROTTLE_THRESHOLD, "-1")
                .withValue(VirtualMapConfig_.FAMILY_THROTTLE_PERCENT, String.valueOf(value))
                // Copy threshold should be ignored
                .withValue(VirtualMapConfig_.COPY_FLUSH_CANDIDATE_THRESHOLD, "1")
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
                .withValue(VirtualMapConfig_.FAMILY_THROTTLE_THRESHOLD, "-1")
                .withValue(VirtualMapConfig_.FAMILY_THROTTLE_PERCENT, String.valueOf(value))
                // Copy threshold should be used, since percent * heap size is less than copy threshold
                .withValue(
                        VirtualMapConfig_.COPY_FLUSH_CANDIDATE_THRESHOLD, String.valueOf(copyFlushCandidateThreshold))
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
                .withValue(VirtualMapConfig_.FAMILY_THROTTLE_THRESHOLD, "-1")
                .withValue(VirtualMapConfig_.FAMILY_THROTTLE_PERCENT, "-1")
                .withConfigDataType(VirtualMapConfig.class)
                .build();
        final VirtualMapConfig virtualMapConfig = config.getConfigData(VirtualMapConfig.class);

        // then
        Assertions.assertThrows(IllegalArgumentException.class, virtualMapConfig::getFamilyThrottleThreshold);
    }
}
