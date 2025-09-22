// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal;

import static com.swirlds.component.framework.schedulers.builders.TaskSchedulerType.SEQUENTIAL;
import static java.util.Optional.ofNullable;

import com.swirlds.component.framework.schedulers.builders.TaskSchedulerConfiguration;
import com.swirlds.component.framework.schedulers.builders.TaskSchedulerType;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.assertj.core.api.AbstractAssert;

/**
 * Custom AssertJ assertion for TaskSchedulerConfiguration that accounts for {@code null}-values.
 */
@SuppressWarnings("UnusedReturnValue")
public class TaskSchedulerConfigurationAssert
        extends AbstractAssert<TaskSchedulerConfigurationAssert, TaskSchedulerConfiguration> {

    /**
     * Constructor for the custom assertion.
     *
     * @param actual the actual TaskSchedulerConfiguration instance to be verified
     */
    public TaskSchedulerConfigurationAssert(@NonNull final TaskSchedulerConfiguration actual) {
        super(actual, TaskSchedulerConfigurationAssert.class);
    }

    /**
     * Entry point for the custom assertion.
     *
     * @param actual the actual TaskSchedulerConfiguration instance to be verified
     * @return a new instance of TaskSchedulerConfigurationAssert
     */
    @NonNull
    public static TaskSchedulerConfigurationAssert assertThat(@NonNull final TaskSchedulerConfiguration actual) {
        return new TaskSchedulerConfigurationAssert(actual);
    }

    /**
     * Verifies that the actual configuration matches the expected configuration,
     * accounting for default values if a property is {@code null}.
     *
     * @param expected the expected TaskSchedulerConfiguration instance
     * @return this assertion object for method chaining
     */
    @NonNull
    public TaskSchedulerConfigurationAssert isEqualTo(@NonNull final TaskSchedulerConfiguration expected) {
        isNotNull();

        // Type defaults to SEQUENTIAL if null
        final TaskSchedulerType expectedType = ofNullable(expected.type()).orElse(SEQUENTIAL);
        final TaskSchedulerType actualType = ofNullable(actual.type()).orElse(SEQUENTIAL);

        if (!expectedType.equals(actualType)) {
            failWithMessage("Expected type to be <%s> but was <%s>", expectedType, actualType);
        }

        // Capacity defaults to 0 if null
        final long expectedCapacity =
                ofNullable(expected.unhandledTaskCapacity()).orElse(0L);
        final long actualCapacity = ofNullable(actual.unhandledTaskCapacity()).orElse(0L);

        if (expectedCapacity != actualCapacity) {
            failWithMessage("Expected unhandledTaskCapacity to be <%s> but was <%s>", expectedCapacity, actualCapacity);
        }

        // Boolean defaults to false if null
        assertBooleanField(
                actual.unhandledTaskMetricEnabled(),
                expected.unhandledTaskMetricEnabled(),
                "unhandledTaskMetricEnabled");

        assertBooleanField(
                actual.busyFractionMetricEnabled(), expected.busyFractionMetricEnabled(), "busyFractionMetricEnabled");

        assertBooleanField(actual.flushingEnabled(), expected.flushingEnabled(), "flushingEnabled");

        assertBooleanField(actual.squelchingEnabled(), expected.squelchingEnabled(), "squelchingEnabled");

        return this;
    }

    private void assertBooleanField(
            @Nullable final Boolean actual, @Nullable final Boolean expected, @NonNull final String fieldName) {
        final boolean expectedValue = ofNullable(expected).orElse(false);
        final boolean actualValue = ofNullable(actual).orElse(false);
        if (expectedValue != actualValue) {
            failWithMessage("Expected %s to be <%s> but was <%s>", fieldName, expectedValue, actualValue);
        }
    }
}
