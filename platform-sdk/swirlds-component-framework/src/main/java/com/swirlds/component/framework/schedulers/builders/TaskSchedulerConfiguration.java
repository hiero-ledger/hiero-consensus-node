// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.schedulers.builders;

import static com.swirlds.component.framework.schedulers.builders.TaskSchedulerConfigOption.BUSY_FRACTION_METRIC;
import static com.swirlds.component.framework.schedulers.builders.TaskSchedulerConfigOption.FLUSHABLE;
import static com.swirlds.component.framework.schedulers.builders.TaskSchedulerConfigOption.SQUELCHABLE;
import static com.swirlds.component.framework.schedulers.builders.TaskSchedulerConfigOption.UNHANDLED_TASK_METRIC;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Configures a task scheduler.
 *
 * @param type                       the type of task scheduler, if null then {@link TaskSchedulerType#SEQUENTIAL} is
 *                                   used
 * @param unhandledTaskCapacity      the maximum number of unhandled tasks, or 0 if unbounded, if null then 0 is used
 * @param unhandledTaskMetricEnabled whether the unhandled task count metric should be enabled, if null than false is
 *                                   used
 * @param busyFractionMetricEnabled  whether the busy fraction metric should be enabled, if null then false is used
 * @param flushingEnabled            whether flushing is enabled, if null then false is used
 * @param squelchingEnabled          whether squelching is enabled, if null then false is used
 */
public record TaskSchedulerConfiguration(
        @Nullable TaskSchedulerType type,
        @Nullable Long unhandledTaskCapacity,
        @Nullable Boolean unhandledTaskMetricEnabled,
        @Nullable Boolean busyFractionMetricEnabled,
        @Nullable Boolean flushingEnabled,
        @Nullable Boolean squelchingEnabled) {

    /**
     * This configuration is for a no-op task scheduler. It is not necessary to use this constant for a no-op task
     * scheduler, but it is provided for convenience.
     */
    public static final TaskSchedulerConfiguration NO_OP_CONFIGURATION =
            new TaskSchedulerConfiguration(TaskSchedulerType.NO_OP, 0L, false, false, false, false);

    /**
     * This configuration is for a simple direct task scheduler. It is not necessary to use this constant for a direct
     * task scheduler, but it is provided for convenience.
     */
    public static final TaskSchedulerConfiguration DIRECT_CONFIGURATION =
            new TaskSchedulerConfiguration(TaskSchedulerType.DIRECT, 0L, false, false, false, false);

    /**
     * This configuration is for a thread-safe direct task scheduler. It is not necessary to use this constant for a
     * thread-safe direct task scheduler, but it is provided for convenience.
     */
    public static final TaskSchedulerConfiguration DIRECT_THREADSAFE_CONFIGURATION =
            new TaskSchedulerConfiguration(TaskSchedulerType.DIRECT_THREADSAFE, 0L, false, false, false, false);

    /**
     * Parse a string representation of a task scheduler configuration.
     * <p>
     * Syntax is as follows:
     * <ul>
     *     <li>
     *         Zero or one values from the {@link TaskSchedulerType} enum, specifies the type of the task scheduler.
     *         If not present then the default is used.
     *     </li>
     *     <li>
     *         Zero or one string of the form "CAPACITY(1234)", specifies the maximum number of unhandled tasks.
     *     </li>
     *     <li>
     *         Zero or more values from the {@link TaskSchedulerConfigOption} enum, specifies the configuration options.
     *         Sets a boolean configuration option to true if the value is present, and false if the value is prefixed
     *         with a "!". If not present then the default is used.
     *     </li>
     * </ul>
     * Example: "SEQUENTIAL CAPACITY(500) !FLUSHABLE UNHANDLED_TASK_METRIC"
     * <p>
     * Note that default values are not specified within this class. Default values are the responsibility of the
     * {@link TaskSchedulerBuilder} class.
     *
     * @param string the string to parse
     * @return the parsed configuration
     */
    @NonNull
    public static TaskSchedulerConfiguration parse(@NonNull final String string) {
        TaskSchedulerType type = null;
        Long unhandledTaskCapacity = null;
        Boolean unhandledTaskMetricEnabled = null;
        Boolean busyFractionMetricEnabled = null;
        Boolean flushingEnabled = null;
        Boolean squelchingEnabled = null;

        final String[] parts = string.split(" ");
        for (final String part : parts) {
            final String strippedPart = part.strip();
            if (strippedPart.isEmpty()) {
                continue;
            }

            final TaskSchedulerType parsedType = tryToParseTaskSchedulerType(strippedPart);
            if (parsedType != null) {
                if (type != null) {
                    throw new IllegalArgumentException("Multiple task scheduler types specified: " + string);
                }
                type = parsedType;
                continue;
            }

            final Long parsedCapacity = tryToParseCapacity(strippedPart);
            if (parsedCapacity != null) {
                if (unhandledTaskCapacity != null) {
                    throw new IllegalArgumentException("Multiple capacities specified: " + string);
                }
                unhandledTaskCapacity = parsedCapacity;
                continue;
            }

            final Boolean parsedUnhandledTaskMetric = tryToParseOption(UNHANDLED_TASK_METRIC, strippedPart);
            if (parsedUnhandledTaskMetric != null) {
                if (unhandledTaskMetricEnabled != null) {
                    throw new IllegalArgumentException(
                            "Multiple unhandled task metric configurations specified: " + string);
                }
                unhandledTaskMetricEnabled = parsedUnhandledTaskMetric;
                continue;
            }

            final Boolean parsedBusyFractionMetric = tryToParseOption(BUSY_FRACTION_METRIC, strippedPart);
            if (parsedBusyFractionMetric != null) {
                if (busyFractionMetricEnabled != null) {
                    throw new IllegalArgumentException(
                            "Multiple busy fraction metric configurations specified: " + string);
                }
                busyFractionMetricEnabled = parsedBusyFractionMetric;
                continue;
            }

            final Boolean parsedFlushing = tryToParseOption(FLUSHABLE, strippedPart);
            if (parsedFlushing != null) {
                if (flushingEnabled != null) {
                    throw new IllegalArgumentException("Multiple flushing configurations specified: " + string);
                }
                flushingEnabled = parsedFlushing;
                continue;
            }

            final Boolean parsedSquelching = tryToParseOption(SQUELCHABLE, strippedPart);
            if (parsedSquelching != null) {
                if (squelchingEnabled != null) {
                    throw new IllegalArgumentException("Multiple squelching configurations specified: " + string);
                }
                squelchingEnabled = parsedSquelching;
                continue;
            }

            throw new IllegalArgumentException("Invalid task scheduler configuration: " + part);
        }

        return new TaskSchedulerConfiguration(
                type,
                unhandledTaskCapacity,
                unhandledTaskMetricEnabled,
                busyFractionMetricEnabled,
                flushingEnabled,
                squelchingEnabled);
    }

    /**
     * Try to parse a string as a {@link TaskSchedulerType}.
     *
     * @param string the string to parse
     * @return the parsed type, or null if the string is not a valid type
     */
    @Nullable
    private static TaskSchedulerType tryToParseTaskSchedulerType(@NonNull final String string) {
        try {
            return TaskSchedulerType.valueOf(string);
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Try to parse a string as a capacity.
     *
     * @param string the string to parse
     * @return the parsed capacity, or null if the string is not a valid capacity
     */
    @Nullable
    private static Long tryToParseCapacity(@NonNull final String string) {
        if (string.startsWith(TaskSchedulerConfigOption.CAPACITY)) {

            try {
                // parse a string in the form "CAPACITY(1234)"
                final int openParenIndex = string.indexOf('(');
                final int closeParenIndex = string.indexOf(')');
                if (openParenIndex == -1 || closeParenIndex == -1) {
                    throw new IllegalArgumentException("Invalid capacity \"" + string + "\"");
                }
                final String capacityString = string.substring(openParenIndex + 1, closeParenIndex);
                return Long.parseLong(capacityString);
            } catch (final NumberFormatException e) {
                throw new IllegalArgumentException("Invalid capacity \"" + string + "\"", e);
            }
        }
        return null;
    }

    /**
     * Try to parse a string as a configuration option that is represented by an enum string and an optional "!".
     *
     * @param option the option to look for
     * @param string the string to parse
     * @return the parsed option, or null if the string is not a valid option
     */
    @Nullable
    private static Boolean tryToParseOption(
            @NonNull final TaskSchedulerConfigOption option, @NonNull final String string) {

        if (string.equals(option.toString())) {
            return true;
        } else if (string.equals("!" + option)) {
            return false;
        }
        return null;
    }
}
