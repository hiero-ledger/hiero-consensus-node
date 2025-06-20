// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.builders;

import static org.hiero.base.utility.test.fixtures.RandomUtils.getRandomPrintSeed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.swirlds.component.framework.schedulers.builders.TaskSchedulerConfiguration;
import com.swirlds.component.framework.schedulers.builders.TaskSchedulerType;
import java.util.Random;
import org.junit.jupiter.api.Test;

class TaskSchedulerConfigurationTests {

    @Test
    void defaultValuesTest() {
        final String configString = "";
        final TaskSchedulerConfiguration config = TaskSchedulerConfiguration.parse(configString);

        assertNull(config.type());
        assertNull(config.unhandledTaskCapacity());
        assertNull(config.unhandledTaskMetricEnabled());
        assertNull(config.busyFractionMetricEnabled());
        assertNull(config.flushingEnabled());
        assertNull(config.squelchingEnabled());
    }

    @Test
    void randomValuesTest() {
        final Random random = getRandomPrintSeed();

        for (int i = 0; i < 100; i++) {
            final StringBuilder configStringBuilder = new StringBuilder();

            final TaskSchedulerType expectedTaskSchedulerType;
            if (random.nextBoolean()) {
                expectedTaskSchedulerType =
                        TaskSchedulerType.values()[random.nextInt(TaskSchedulerType.values().length)];
                configStringBuilder.append(expectedTaskSchedulerType).append(" ");
            } else {
                expectedTaskSchedulerType = null;
            }

            final Long expectedUnhandledTaskCapacity;
            if (random.nextBoolean()) {
                expectedUnhandledTaskCapacity = random.nextLong(0, 100);
                configStringBuilder
                        .append("CAPACITY(")
                        .append(expectedUnhandledTaskCapacity)
                        .append(") ");
            } else {
                expectedUnhandledTaskCapacity = null;
            }

            final Boolean expectedUnhandledTaskMetricEnabled;
            if (random.nextBoolean()) {
                expectedUnhandledTaskMetricEnabled = random.nextBoolean();
                configStringBuilder.append(
                        expectedUnhandledTaskMetricEnabled ? "UNHANDLED_TASK_METRIC " : "!UNHANDLED_TASK_METRIC ");
            } else {
                expectedUnhandledTaskMetricEnabled = null;
            }

            final Boolean expectedBusyFractionMetricEnabled;
            if (random.nextBoolean()) {
                expectedBusyFractionMetricEnabled = random.nextBoolean();
                configStringBuilder.append(
                        expectedBusyFractionMetricEnabled ? "BUSY_FRACTION_METRIC " : "!BUSY_FRACTION_METRIC ");
            } else {
                expectedBusyFractionMetricEnabled = null;
            }

            final Boolean expectedFlushingEnabled;
            if (random.nextBoolean()) {
                expectedFlushingEnabled = random.nextBoolean();
                configStringBuilder.append(expectedFlushingEnabled ? "FLUSHABLE " : "!FLUSHABLE ");
            } else {
                expectedFlushingEnabled = null;
            }

            final Boolean expectedSquelchingEnabled;
            if (random.nextBoolean()) {
                expectedSquelchingEnabled = random.nextBoolean();
                configStringBuilder.append(expectedSquelchingEnabled ? "SQUELCHABLE " : "!SQUELCHABLE ");
            } else {
                expectedSquelchingEnabled = null;
            }

            final String configString = configStringBuilder.toString();

            final TaskSchedulerConfiguration config = TaskSchedulerConfiguration.parse(configString);

            assertEquals(expectedTaskSchedulerType, config.type());
            assertEquals(expectedUnhandledTaskCapacity, config.unhandledTaskCapacity());
            assertEquals(expectedUnhandledTaskMetricEnabled, config.unhandledTaskMetricEnabled());
            assertEquals(expectedBusyFractionMetricEnabled, config.busyFractionMetricEnabled());
            assertEquals(expectedFlushingEnabled, config.flushingEnabled());
            assertEquals(expectedSquelchingEnabled, config.squelchingEnabled());
        }
    }

    /**
     * Test what happens if a configuration string contains multiple values for the same configuration option.
     */
    @Test
    void doubleConfigurationTest() {
        assertThrows(IllegalArgumentException.class, () -> TaskSchedulerConfiguration.parse("DIRECT DIRECT"));
        assertThrows(IllegalArgumentException.class, () -> TaskSchedulerConfiguration.parse("DIRECT SEQUENTIAL"));
        assertThrows(
                IllegalArgumentException.class,
                () -> TaskSchedulerConfiguration.parse("CAPACITY(1234) CAPACITY(1234)"));
        assertThrows(
                IllegalArgumentException.class,
                () -> TaskSchedulerConfiguration.parse("CAPACITY(1234) CAPACITY(5678)"));
        assertThrows(
                IllegalArgumentException.class,
                () -> TaskSchedulerConfiguration.parse("UNHANDLED_TASK_METRIC UNHANDLED_TASK_METRIC"));
        assertThrows(
                IllegalArgumentException.class,
                () -> TaskSchedulerConfiguration.parse("UNHANDLED_TASK_METRIC !UNHANDLED_TASK_METRIC"));
        assertThrows(
                IllegalArgumentException.class,
                () -> TaskSchedulerConfiguration.parse("BUSY_FRACTION_METRIC BUSY_FRACTION_METRIC"));
        assertThrows(
                IllegalArgumentException.class,
                () -> TaskSchedulerConfiguration.parse("BUSY_FRACTION_METRIC !BUSY_FRACTION_METRIC"));
        assertThrows(IllegalArgumentException.class, () -> TaskSchedulerConfiguration.parse("FLUSHABLE !FLUSHABLE"));
        assertThrows(IllegalArgumentException.class, () -> TaskSchedulerConfiguration.parse("FLUSHABLE FLUSHABLE"));
        assertThrows(
                IllegalArgumentException.class, () -> TaskSchedulerConfiguration.parse("SQUELCHABLE !SQUELCHABLE"));
        assertThrows(IllegalArgumentException.class, () -> TaskSchedulerConfiguration.parse("SQUELCHABLE SQUELCHABLE"));
    }

    @Test
    void unmatchedFieldTest() {
        assertThrows(
                IllegalArgumentException.class, () -> TaskSchedulerConfiguration.parse("DIRECT CAPACITY(100) QWERTY"));
    }
}
