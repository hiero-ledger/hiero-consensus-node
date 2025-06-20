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
 * method that is part of the config data record (see {@link com.swirlds.config.api.ConfigData}) that contains the
 * annotated property. The method must follow the given pattern:
 * {@code public ConfigViolation methodName(Configuration configuration)}. If the validation is successful the method
 * must return null. If the validation fails a ConfigViolation must be returned. The validation of the annotation is
 * automatically executed at the initialization of the configuration (see {@link ConfigurationBuilder#build()})
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
