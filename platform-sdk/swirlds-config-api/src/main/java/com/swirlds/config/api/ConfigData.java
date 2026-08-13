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
 * <p>
 * A record component can be a record itself. Such a nested config data object has no value of its own, instead each of
 * its components becomes a property that is prefixed by the name of the component holding the nested record, which
 * allows related properties to be grouped and a group to be reused:
 * <pre>
 * &#64;ConfigData("network")
 * public record NetworkConfig(EndpointConfig primary,
 *                             EndpointConfig secondary) {
 * }
 *
 * &#64;ConfigData
 * public record EndpointConfig(int port,
 *                              String server) {
 * }
 * </pre>
 * This defines the properties {@code "network.primary.port"}, {@code "network.primary.server"},
 * {@code "network.secondary.port"} and {@code "network.secondary.server"}. Nesting can go any number of levels deep and
 * a cycle in the record types fails the creation of the config data object. A nested record needs to be public and to
 * have exactly one constructor, like any config data object, and it needs this annotation: it is what tells a group of
 * properties apart from a value that a converter creates from a single property. The {@link #value()} of a nested
 * record is unused, since the prefix always comes from the component that holds it.
 * <p>
 * The defaults of a nested record are normally defined by the {@link ConfigProperty} annotations of that record, which
 * makes them the same everywhere it is used. Use {@link ConfigDefault} to define them at the place where the nested
 * record is used instead.
 * <p>
 * A record type without this annotation stays a single property whose raw value is converted by a registered
 * {@link com.swirlds.config.api.converter.ConfigConverter}. A record valued component whose type has neither this
 * annotation nor a converter is rejected.
 *
 * @see ConfigProperty
 * @see ConfigDefault
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
