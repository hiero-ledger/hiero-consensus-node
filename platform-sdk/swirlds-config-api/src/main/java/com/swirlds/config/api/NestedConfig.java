// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.api;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Annotation that marks a {@link Record} as a nested config data object, meaning a group of properties that is used as
 * a record component of a config data object (see {@link ConfigData}) rather than being registered on its own.
 * <p>
 * A nested config data object has no value of its own. Instead each of its components becomes a property that is
 * prefixed by the name of the component holding it, which allows related properties to be grouped and a group to be
 * reused:
 * <pre>
 * &#64;ConfigData("network")
 * public record NetworkConfig(EndpointConfig primary,
 *                             EndpointConfig secondary) {
 * }
 *
 * &#64;NestedConfig
 * public record EndpointConfig(int port,
 *                              String server) {
 * }
 * </pre>
 * This defines the properties {@code "network.primary.port"}, {@code "network.primary.server"},
 * {@code "network.secondary.port"} and {@code "network.secondary.server"}. The annotation has no member, since the
 * prefix of a nested config data object always comes from the component that holds it.
 * <p>
 * Nesting can go any number of levels deep, and a cycle in the record types fails the creation of the config data
 * object. A nested record needs to be public and to have exactly one constructor, like any config data object.
 * <p>
 * This annotation is what tells a group of properties apart from a value that a converter creates from a single
 * property, so the following all fail instead of being silently misinterpreted:
 * <ul>
 *     <li>a record type annotated with both this annotation and {@link ConfigData}</li>
 *     <li>a record type annotated with this annotation that is registered as a config data type of its own</li>
 *     <li>a record type annotated with this annotation that also has a registered
 *     {@link com.swirlds.config.api.converter.ConfigConverter}</li>
 *     <li>a record valued component whose type has neither this annotation nor a converter</li>
` *     <li>a {@link java.util.List} or {@link java.util.Set} whose element type is annotated with this annotation, since
 *     a group takes its name from the single component holding it and an element of a collection has none</li>
 * </ul>
 * A record type without this annotation therefore stays a single property whose raw value is converted by a registered
 * converter.
 * <p>
 * A component holding a nested config data object accepts no default value, except
 * {@link ConfigProperty#NULL_DEFAULT_VALUE}, which makes the whole group optional: it is null unless a config source
 * defines at least one of the properties below it. While the group is absent it is still checked for every mistake that
 * follows from its declaration alone, and it contributes nothing to the exported configuration, neither the component
 * holding it nor any property below it.
 * <p>
 * The defaults of a nested record are normally defined by the {@link ConfigProperty} annotations of that record, which
 * makes them the same everywhere it is used. Use {@link ConfigDefault} to define them at the place where the nested
 * record is used instead.
 * <p>
 * Since a nested config data object is never a config data type of its own, the annotation processor generates neither
 * property name constants nor documentation for it. Both are generated for the config data objects that use it, under
 * the full property names.
 *
 * @see ConfigData
 * @see ConfigProperty
 * @see ConfigDefault
 */
@Retention(RUNTIME)
@Target(TYPE)
public @interface NestedConfig {}
