// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.api.validation.annotation;

import com.swirlds.config.api.ConfigurationBuilder;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A constraint annotation that can be used define that a value for a config data property (see
 * {@link com.swirlds.config.api.ConfigProperty}) must be positive (&gt;0). The validation of the annotation is
 * automatically executed at the initialization of the configuration (see {@link ConfigurationBuilder#build()}). The
 * annotated property must be a {@link Number}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface Positive {}
