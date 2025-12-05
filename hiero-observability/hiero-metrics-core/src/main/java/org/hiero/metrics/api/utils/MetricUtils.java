// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.utils;

import com.swirlds.base.ArgumentUtils;
import java.util.List;
import java.util.ServiceLoader;
import java.util.regex.Pattern;

/**
 * Utility class for metrics-related operations.
 */
public final class MetricUtils {

    private static final String METRIC_NAME_REGEX = "^[a-zA-Z][a-zA-Z0-9_:]*$";
    private static final String UNIT_LABEL_NAME_REGEX = "^[a-zA-Z][a-zA-Z0-9_]*$";

    private static final Pattern METRIC_NAME_PATTERN = Pattern.compile(METRIC_NAME_REGEX);
    private static final Pattern UNIT_LABEL_NAME_PATTERN = Pattern.compile(UNIT_LABEL_NAME_REGEX);

    private MetricUtils() {}

    /**
     * Validates that the provided metric name adheres to the required character set. <br>
     * Patter to validate is: {@value METRIC_NAME_REGEX} <br>
     * Definition in ABNF (Augmented Backus-Naur Form):
     * <pre>
     *   name = name-initial-char *name-char
     *   name-initial-char = ALPHA / "_" / ":"
     *   name-char = name-initial-char / DIGIT
     * </pre>
     * @param metricName the name to validate
     * @throws IllegalArgumentException if the name is blank or contains invalid characters
     */
    public static String validateMetricNameCharacters(String metricName) {
        return validateNameCharacters(METRIC_NAME_PATTERN, metricName);
    }

    /**
     * Validates that the provided unit name adheres to the required character set. <br>
     * Patter to validate is: {@value UNIT_LABEL_NAME_REGEX} <br>
     * Definition in ABNF (Augmented Backus-Naur Form):
     * <pre>
     *   name = name-initial-char *name-char
     *   name-initial-char = ALPHA / "_"
     *   name-char = name-initial-char / DIGIT
     * </pre>
     * @param unitName the unit name to validate
     * @throws IllegalArgumentException if the name is blank or contains invalid characters
     */
    public static String validateUnitNameCharacters(String unitName) {
        return validateNameCharacters(UNIT_LABEL_NAME_PATTERN, unitName);
    }

    /**
     * Validates that the provided label name adheres to the required character set. <br>
     * Patter to validate is: {@value UNIT_LABEL_NAME_REGEX} <br>
     * Definition in ABNF (Augmented Backus-Naur Form):
     * <pre>
     *   name = name-initial-char *name-char
     *   name-initial-char = ALPHA / "_"
     *   name-char = name-initial-char / DIGIT
     * </pre>
     * @param labelName the unit name to validate
     * @throws IllegalArgumentException if the name is blank or contains invalid characters
     */
    public static String validateLabelNameCharacters(String labelName) {
        return validateNameCharacters(UNIT_LABEL_NAME_PATTERN, labelName);
    }

    private static String validateNameCharacters(Pattern pattern, String name) {
        ArgumentUtils.throwArgBlank(name, "name");
        if (!pattern.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "Name contains illegal character: " + name + ". Required pattern is " + METRIC_NAME_REGEX);
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
}
