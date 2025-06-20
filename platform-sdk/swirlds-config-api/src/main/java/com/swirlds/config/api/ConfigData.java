// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.api;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Annotation that can be used to annotate a config data object. A config data object is a {@link Record} that provides
 * access to config values in an object-oriented way.
 * <p>
 * Example:
 * <pre>
 * &#64;ConfigData("network")
 * public record NetworkConfig(int port,
 *                             String server) {
 * }
 * </pre>
 * In this example the {@code port} and {@code server} values can easily be accessed by calling the record instance (see
 * {@link Configuration#getConfigData(Class)} for more infos).  The property name of the {@code port} property will be
 * {@code "network.port"} and the property name of the {@code server} property will be {@code "network.server"}
 */
@Retention(RUNTIME)
@Target(TYPE)
public @interface ConfigData {

    /**
     * Defines the prefix for the property names that are part of the annotated record / config data object.
     *
     * @return the prefix for the property names
     */
    String value() default "";
}
