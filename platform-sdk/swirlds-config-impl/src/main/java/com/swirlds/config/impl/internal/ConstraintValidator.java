// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.impl.internal;

import com.swirlds.base.ArgumentUtils;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.converter.ConfigConverter;
import com.swirlds.config.api.validation.ConfigPropertyConstraint;
import com.swirlds.config.api.validation.ConfigValidator;
import com.swirlds.config.api.validation.ConfigViolation;
import com.swirlds.config.api.validation.PropertyMetadata;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Stream;

final class ConstraintValidator implements ConfigValidator {

    private final ConverterService converterService;

    @SuppressWarnings("rawtypes")
    private final Queue<ConfigPropertyConstraintData> constraintData;

    ConstraintValidator(@NonNull final ConverterService converterService) {
        this.converterService = Objects.requireNonNull(converterService, "converterService must not be null");
        this.constraintData = new ConcurrentLinkedQueue<>();
    }

    @SuppressWarnings("unchecked")
    @NonNull
    @Override
    public Stream<ConfigViolation> validate(@NonNull final Configuration configuration) {
        Objects.requireNonNull(configuration, "configuration must not be null");
        return constraintData.stream()
                .map(d -> {
                    final PropertyMetadata<?> propertyMetadata =
                            createMetadata(d.propertyName, d.valueType, configuration);
                    return d.validator.check(propertyMetadata);
                })
                .filter(Objects::nonNull);
    }

    @NonNull
    private <T> PropertyMetadata<T> createMetadata(
            @NonNull final String propertyName,
            @NonNull final Class<T> valueType,
            @NonNull final Configuration configuration) {
        ArgumentUtils.throwArgBlank(propertyName, "propertyName");
        Objects.requireNonNull(valueType, "valueType must not be null");
        Objects.requireNonNull(configuration, "configuration must not be null");
        if (configuration.exists(propertyName)) {
            final ConfigConverter<T> converter = converterService.getConverterForType(valueType);
            return new PropertyMetadataImpl<>(
                    propertyName, configuration.getValue(propertyName), valueType, true, converter);
        } else {
            final ConfigConverter<T> converter = converterService.getConverterForType(valueType);
            return new PropertyMetadataImpl<>(propertyName, null, valueType, false, converter);
        }
    }

    <T> void addConstraint(
            @NonNull final String propertyName,
            @NonNull final Class<T> valueType,
            @NonNull final ConfigPropertyConstraint<T> validator) {
        ArgumentUtils.throwArgBlank(propertyName, "propertyName");
        Objects.requireNonNull(valueType, "valueType must not be null");
        Objects.requireNonNull(validator, "validator must not be null");
        constraintData.add(new ConfigPropertyConstraintData<>(propertyName, valueType, validator));
    }

    void clear() {
        constraintData.clear();
    }

    private record ConfigPropertyConstraintData<T>(
            String propertyName, Class<T> valueType, ConfigPropertyConstraint<T> validator) {}
}
