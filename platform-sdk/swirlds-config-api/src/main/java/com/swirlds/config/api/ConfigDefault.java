// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.api;

import static java.lang.annotation.ElementType.RECORD_COMPONENT;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Annotation that defines the default value of a single property of a nested config data object. A nested config data
 * object is a record component of a config data object (see {@link ConfigData}) whose type is itself a record.
 * <p>
 * The defaults of a nested record are normally defined by the {@link ConfigProperty} annotations of that record, which
 * makes them the same for every place the record is used. This annotation defines a default at the place where the
 * nested record is used instead, so that the same record type can be used several times with different defaults.
 * <p>
 * Example:
 * <pre>
 * &#64;ConfigData("wiring")
 * public record WiringConfig(
 *         &#64;ConfigDefault(property = "type", defaultValue = "CONCURRENT")
 *         &#64;ConfigDefault(property = "capacity", defaultValue = "500")
 *         SchedulerConfig prehandler,
 *
 *         &#64;ConfigDefault(property = "type", defaultValue = "SEQUENTIAL")
 *         &#64;ConfigDefault(property = "capacity", defaultValue = "100")
 *         SchedulerConfig handler) {
 * }
 *
 * public record SchedulerConfig(SchedulerType type, long capacity) {}
 * </pre>
 * Here {@code "wiring.prehandler.type"} defaults to {@code "CONCURRENT"} while
 * {@code "wiring.handler.type"} defaults to {@code "SEQUENTIAL"}, and each of the properties can still be set
 * individually by the config without restating the others.
 * <p>
 * The value of a property is resolved in the following order, from most to least specific:
 * <ol>
 *     <li>the value that is defined for the full property name by the config</li>
 *     <li>the {@link ConfigDefault} for that property, where an annotation that is declared by an enclosing config
 *     data object wins over one that is declared closer to the property</li>
 *     <li>the {@link ConfigProperty#defaultValue()} of the property</li>
 * </ol>
 * If none of them defines a value the creation of the config data object fails.
 */
@Retention(RUNTIME)
@Target(RECORD_COMPONENT)
@Repeatable(ConfigDefault.List.class)
public @interface ConfigDefault {

    /**
     * The property that the default value is defined for, relative to the annotated record component. The property may
     * contain dots to address a property of a record that is nested more deeply, like {@code "inner.capacity"}.
     * <p>
     * A property is addressed by its config name and not by the name of the record component, so a renaming by
     * {@link ConfigProperty#value()} has to be taken into account:
     * <pre>
     * public record Leaf(&#64;ConfigProperty(value = "renamed") String value) {}
     *
     * &#64;ConfigData("root")
     * public record Root(&#64;ConfigDefault(property = "renamed", defaultValue = "x") Leaf leaf) {}
     * </pre>
     * The property is therefore always spelled the same way here, in the dotted path of a more deeply nested property
     * and in the config itself, where the example above is set by {@code "root.leaf.renamed"}. A property that does not
     * exist fails the creation of the config data object instead of being ignored.
     *
     * @return the property
     */
    String property();

    /**
     * The default value of the property. The value is used exactly like a {@link ConfigProperty#defaultValue()}, so it
     * is converted to the type of the property by the registered converters and
     * {@link ConfigProperty#NULL_DEFAULT_VALUE} can be used to default the property to {@code null}.
     *
     * @return the default value
     */
    String defaultValue();

    /**
     * Container for several {@link ConfigDefault} annotations. Since {@link ConfigDefault} is repeatable it can simply
     * be written several times and this annotation never has to be used, but it can be used where grouping the
     * defaults of a nested config data object reads better:
     * <pre>
     * &#64;ConfigData("wiring")
     * public record WiringConfig(
     *         &#64;ConfigDefault.List({
     *             &#64;ConfigDefault(property = "type", defaultValue = "CONCURRENT"),
     *             &#64;ConfigDefault(property = "capacity", defaultValue = "500")
     *         })
     *         SchedulerConfig prehandler) {
     * }
     * </pre>
     */
    @Retention(RUNTIME)
    @Target(RECORD_COMPONENT)
    @interface List {

        /**
         * The default values of the properties of the annotated nested config data object.
         *
         * @return the default values
         */
        ConfigDefault[] value();
    }
}
