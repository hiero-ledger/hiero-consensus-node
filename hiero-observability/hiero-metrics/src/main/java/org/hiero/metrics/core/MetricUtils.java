// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.core;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * Utility class for metrics-related operations.
 */
public final class MetricUtils {

    /** Regex for validating metric names. */
    public static final String METRIC_NAME_REGEX = "^[a-zA-Z][a-zA-Z0-9_:]*$";

    /** Regex for validating unit and label names. */
    public static final String UNIT_LABEL_NAME_REGEX = "^[a-zA-Z][a-zA-Z0-9_]*$";

    private static final Pattern METRIC_NAME_PATTERN = Pattern.compile(METRIC_NAME_REGEX);
    private static final Pattern UNIT_LABEL_NAME_PATTERN = Pattern.compile(UNIT_LABEL_NAME_REGEX);

    public static final DoubleSupplier DOUBLE_ZERO_INIT = () -> 0.0;
    public static final LongSupplier LONG_ZERO_INIT = () -> 0L;

    private MetricUtils() {}

    /**
     * Validates that the provided metric name adheres to the required character set. <br>
     * Patter to validate is: {@value #METRIC_NAME_REGEX} <br>
     * Definition in ABNF (Augmented Backus-Naur Form):
     * <pre>
     *   name = name-initial-char *name-char
     *   name-initial-char = ALPHA / "_" / ":"
     *   name-char = name-initial-char / DIGIT
     * </pre>
     * @param metricName the name to validate
     * @throws NullPointerException if metric name is {@code null}
     * @throws IllegalArgumentException if metric name is blank or contains invalid characters
     */
    public static String validateMetricNameCharacters(String metricName) {
        return validateNameCharacters(METRIC_NAME_PATTERN, metricName);
    }

    /**
     * Validates that the provided unit name adheres to the required character set. <br>
     * Patter to validate is: {@value #UNIT_LABEL_NAME_REGEX} <br>
     * Definition in ABNF (Augmented Backus-Naur Form):
     * <pre>
     *   name = name-initial-char *name-char
     *   name-initial-char = ALPHA / "_"
     *   name-char = name-initial-char / DIGIT
     * </pre>
     * @param unitName the unit name to validate
     * @throws NullPointerException if unit name is {@code null}
     * @throws IllegalArgumentException if unit name is blank or contains invalid characters
     */
    public static String validateUnitNameCharacters(String unitName) {
        return validateNameCharacters(UNIT_LABEL_NAME_PATTERN, unitName);
    }

    /**
     * Validates that the provided label name adheres to the required character set. <br>
     * Patter to validate is: {@value #UNIT_LABEL_NAME_REGEX} <br>
     * Definition in ABNF (Augmented Backus-Naur Form):
     * <pre>
     *   name = name-initial-char *name-char
     *   name-initial-char = ALPHA / "_"
     *   name-char = name-initial-char / DIGIT
     * </pre>
     * @param labelName the unit name to validate
     * @throws NullPointerException if label name is {@code null}
     * @throws IllegalArgumentException if label name is blank or contains invalid characters
     */
    public static String validateLabelNameCharacters(String labelName) {
        return validateNameCharacters(UNIT_LABEL_NAME_PATTERN, labelName);
    }

    private static String validateNameCharacters(Pattern pattern, String name) {
        MetricUtils.throwArgBlank(name, "name");
        if (!pattern.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "Name contains illegal character: " + name + ". Required pattern is " + pattern.pattern());
        }
        return name;
    }

    /**
     * Loads implementations of the specified class using Java's ServiceLoader mechanism.
     *
     * @param serviceType   the class of the implementations to load
     * @param <T>           the type of the implementation
     * @return a list of loaded implementations
     */
    public static <T> List<T> load(Class<T> serviceType) {
        ServiceLoader<T> serviceLoader = ServiceLoader.load(serviceType);
        return serviceLoader.stream().map(ServiceLoader.Provider::get).toList();
    }

    /**
     * Validates that provided argument is not null or blank.
     *
     * @param argument     the argument checked
     * @param argumentName the name of the argument
     * @throws NullPointerException of passed argument is {@code null}
     * @throws IllegalArgumentException of passed argument is blank using {@link String#isBlank()}
     */
    @NonNull
    public static String throwArgBlank(@NonNull final String argument, @NonNull final String argumentName)
            throws NullPointerException, IllegalArgumentException {
        Objects.requireNonNull(argument, argumentName + " cannot be null");
        if (argument.isBlank()) {
            throw new IllegalArgumentException(argumentName + " cannot be blank");
        }
        return argument;
    }

    /**
     * @return {@link LongSupplier} that always returns the provided value.
     */
    @NonNull
    public static LongSupplier asSupplier(long value) {
        return value == 0L ? LONG_ZERO_INIT : () -> value;
    }

    /**
     * @return {@link DoubleSupplier} that always returns the provided value.
     */
    @NonNull
    public static DoubleSupplier asSupplier(double value) {
        return value == 0.0 ? DOUBLE_ZERO_INIT : () -> value;
    }
}
