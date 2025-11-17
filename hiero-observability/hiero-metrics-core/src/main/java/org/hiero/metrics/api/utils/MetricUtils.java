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

    private static final String NAME_REGEX = "^[a-zA-Z_][a-zA-Z0-9_]*$";
    private static final Pattern NAME_PATTERN = Pattern.compile(NAME_REGEX);

    private MetricUtils() {}

    /**
     * Validates that the provided name adheres to the required character set. <br>
     * Patter to validate is: {@value NAME_REGEX} <br>
     * Definition in ABNF (Augmented Backus-Naur Form):
     * <pre>
     *   name = name-initial-char *name-char
     *   name-char = name-initial-char / DIGIT
     *   name-initial-char = ALPHA / "_"
     * </pre>
     * @param name the name to validate
     * @throws IllegalArgumentException if the name is blank or contains invalid characters
     */
    public static String validateNameCharacters(String name) {
        ArgumentUtils.throwArgBlank(name, "name");
        if (!NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "Name contains illegal character: " + name + ". Required pattern is " + NAME_REGEX);
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
