// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.api;

import static java.lang.annotation.ElementType.RECORD_COMPONENT;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/// Annotation that overrides the default value of one or more properties of a nested config data object.
///
/// A nested config data object (see [NestedConfig]) is a group of properties rather than a value of its own, so
/// the component that holds it still accepts no [ConfigProperty#defaultValue()]. Use this annotation on that
/// component instead to override the default values of the leaf properties below it.
///
/// The [#property()] names the property relative to the nested config data object held by the annotated component,
/// using the same dotted notation that the same declaration written flat would use:
///
/// ``````
/// @ConfigData("wiring")
/// public record WiringConfig(
///         @ConfigDefault(property = "type", defaultValue = "CONCURRENT")
///         @ConfigDefault(property = "capacity", defaultValue = "1000")
///         SchedulerConfig prehandler,
///         SchedulerConfig handler) {}
///
/// @NestedConfig
/// public record SchedulerConfig(
///         @ConfigProperty(defaultValue = "SEQUENTIAL") SchedulerType type,
///         @ConfigProperty(defaultValue = "500") long capacity) {}
/// ```
///
/// In this example the default values of `wiring.prehandler.type` and `wiring.prehandler.capacity` are
/// overridden while the defaults of `wiring.handler.type` and `wiring.handler.capacity` stay unchanged.
///
/// Like [ConfigProperty#defaultValue()], the [#defaultValue()] is a raw string value that is converted to the
/// type of the targeted property. It therefore supports [ConfigProperty#NULL_DEFAULT_VALUE] and collection values
/// in exactly the same way.
///
/// When several components on the path to the same leaf property define an override for it, the override declared
/// closest to the config data root wins.
/// If several leaf properties below one component resolve to the same relative path, the override applies to each of
/// them.
@Retention(RUNTIME)
@Target(RECORD_COMPONENT)
@Repeatable(ConfigDefault.List.class)
public @interface ConfigDefault {

    /// The dotted property path, relative to the nested config data object held by the annotated component.
    /// @return the relative property path
    String property();

    /// The raw string default value for the targeted property.
    /// @return the default value
    String defaultValue();

    /// Container for several [ConfigDefault] annotations.
    @Retention(RUNTIME)
    @Target(RECORD_COMPONENT)
    @interface List {

        /// The contained [ConfigDefault] annotations.
        /// @return the overrides
        ConfigDefault[] value();
    }
}
