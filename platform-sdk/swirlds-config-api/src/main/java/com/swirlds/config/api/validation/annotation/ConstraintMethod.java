// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.api.validation.annotation;

import com.swirlds.config.api.ConfigurationBuilder;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A constraint annotation that can be used define how the value for a config data property (see
 * {@link com.swirlds.config.api.ConfigProperty}) must be validated. The value of the annotation must name a public
 * method that is declared by the record that declares the annotated component: the config data record (see
 * {@link com.swirlds.config.api.ConfigData}) for one of its own components, and the nested config data record (see
 * {@link com.swirlds.config.api.NestedConfig}) for a component of a nested config data object. The method must follow
 * the given pattern: {@code public ConfigViolation methodName(Configuration configuration)}. If the validation is
 * successful the method must return null. If the validation fails a ConfigViolation must be returned. The validation of
 * the annotation is automatically executed at the initialization of the configuration (see
 * {@link ConfigurationBuilder#build()})
 * <p>
 * The method creates the {@link com.swirlds.config.api.validation.ConfigViolation} itself and therefore chooses the
 * property name that the violation is reported under; the configuration does not substitute one. A nested config data
 * record that is used in several places is checked once per place, but each of those checks runs the same method, so a
 * reusable nested config data record can not report the name of the place it is used in. Use a constraint on the
 * component that holds the group, on the config data record, when the name of the occurrence matters.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface ConstraintMethod {

    /**
     * Defines the name of the method that will be executed to validate the annotated property.
     *
     * @return name of the method that will be executed to validate the annotated property
     */
    String value();
}
