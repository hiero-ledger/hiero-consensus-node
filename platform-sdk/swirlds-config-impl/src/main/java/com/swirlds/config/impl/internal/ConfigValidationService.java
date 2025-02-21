// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.impl.internal;

import com.swirlds.base.ArgumentUtils;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.validation.ConfigPropertyConstraint;
import com.swirlds.config.api.validation.ConfigValidator;
import com.swirlds.config.api.validation.ConfigViolation;
import com.swirlds.config.api.validation.ConfigViolationException;
import com.swirlds.config.impl.validators.annotation.internal.ConstraintMethodConstraintsValidation;
import com.swirlds.config.impl.validators.annotation.internal.MaxConstraintsValidation;
import com.swirlds.config.impl.validators.annotation.internal.MinConstraintsValidation;
import com.swirlds.config.impl.validators.annotation.internal.NegativeConstraintsValidation;
import com.swirlds.config.impl.validators.annotation.internal.PositiveConstraintsValidation;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

/**
 * Service for validation of the config.
 */
class ConfigValidationService implements ConfigLifecycle {

    /**
     * Set opf all validators that shpuld be executed on a validation.
     */
    private final Queue<ConfigValidator> validators;

    /**
     * A specific validator that checks all {@link ConfigPropertyConstraint} instances.
     */
    private final ConstraintValidator constraintValidator;

    /**
     * Defines if this service is initialized.
     */
    private boolean initialized = false;

    ConfigValidationService(@NonNull final ConverterService converterService) {
        this.constraintValidator = new ConstraintValidator(converterService);
        this.validators = new ConcurrentLinkedQueue<>();
    }

    void addValidator(@NonNull final ConfigValidator validator) {
        throwIfInitialized();
        Objects.requireNonNull(validator, "validator must not be null");
        validators.add(validator);
    }

    @Override
    public void init() {
        throwIfInitialized();
        // General Validator for constraints
        validators.add(constraintValidator);
        validators.add(new ConstraintMethodConstraintsValidation());
        validators.add(new MaxConstraintsValidation());
        validators.add(new MinConstraintsValidation());
        validators.add(new NegativeConstraintsValidation());
        validators.add(new PositiveConstraintsValidation());
        initialized = true;
    }

    @Override
    public void dispose() {
        validators.clear();
        constraintValidator.clear();
        initialized = false;
    }

    @Override
    public boolean isInitialized() {
        return initialized;
    }

    void validate(@NonNull final Configuration configuration) {
        throwIfNotInitialized();
        Objects.requireNonNull(configuration, "configuration must not be null");
        final List<ConfigViolation> violations =
                validators.stream().flatMap(v -> v.validate(configuration)).collect(Collectors.toList());
        if (!violations.isEmpty()) {
            final int violationCount = violations.size();
            throw new ConfigViolationException(
                    "Configuration failed based on " + violationCount + " violations!", violations);
        }
    }

    <T> void addConstraint(
            @NonNull final String propertyName,
            @NonNull final Class<T> valueType,
            @NonNull final ConfigPropertyConstraint<T> validator) {
        throwIfInitialized();
        ArgumentUtils.throwArgBlank(propertyName, "propertyName");
        Objects.requireNonNull(valueType, "valueType must not be null");
        Objects.requireNonNull(validator, "validator must not be null");
        constraintValidator.addConstraint(propertyName, valueType, validator);
    }
}
