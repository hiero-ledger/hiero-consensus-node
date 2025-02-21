// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.impl.validators.annotation.internal;

import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.validation.ConfigValidator;
import com.swirlds.config.api.validation.ConfigViolation;
import com.swirlds.config.api.validation.annotation.ConstraintMethod;
import com.swirlds.config.extensions.reflection.ConfigReflectionUtils;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * A {@link ConfigValidator} implementation which checks all config data objects for the constraints annotation
 * {@link ConstraintMethod}.
 */
public class ConstraintMethodConstraintsValidation implements ConfigValidator {

    @Override
    public Stream<ConfigViolation> validate(final Configuration configuration) {
        return ConfigReflectionUtils.getAllMatchingPropertiesForConstraintAnnotation(
                        ConstraintMethod.class, configuration)
                .stream()
                .map(property -> execute(configuration, property))
                .filter(Objects::nonNull);
    }

    private static ConfigViolation execute(
            final Configuration configuration,
            final ConfigReflectionUtils.AnnotatedProperty<ConstraintMethod, ?> annotatedProperty) {
        try {
            final String methodName = annotatedProperty.annotation().value();
            final Class<Record> recordType =
                    (Class<Record>) annotatedProperty.component().getDeclaringRecord();
            final Object recordInstance = configuration.getConfigData(recordType);
            final Method method =
                    annotatedProperty.component().getDeclaringRecord().getMethod(methodName, Configuration.class);
            final Object violation = method.invoke(recordInstance, configuration);
            if (violation == null) {
                return null;
            }
            if (violation instanceof ConfigViolation configViolation) {
                return configViolation;
            }
            throw new IllegalStateException("Validation failed since method '" + methodName + " in record '"
                    + recordType + "' does not " + "follow"
                    + " " + "the definition pattern 'public " + ConfigViolation.class.getName() + " method("
                    + Configuration.class.getName() + " config)'");
        } catch (final Exception e) {
            throw new IllegalStateException("Validation failed!", e);
        }
    }
}
