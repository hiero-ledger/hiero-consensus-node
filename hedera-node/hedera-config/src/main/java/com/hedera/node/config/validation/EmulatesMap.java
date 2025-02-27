// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.validation;

import com.hedera.node.config.types.KeyValuePair;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A constraint annotation that can be used to define a map-like structure for a config data property (see {@link
 * com.swirlds.config.api.ConfigProperty}). The annotated property should be a {@link java.util.List} of {@link
 * KeyValuePair}. By adding the annotation a {@link com.swirlds.config.api.validation.ConfigViolation} will be thrown if
 * the {@link java.util.List} value of the property contains 2 or more {@link KeyValuePair} with the same key.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface EmulatesMap {}
