// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.api;

import static java.lang.annotation.ElementType.RECORD_COMPONENT;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Annotation that can be used to annotate properties for a config data object. A config data object is a {@link Record}
 * that provides access to config values in an object-oriented way (see {@link ConfigData} for more information).
 * <p>
 * Example:
 * <pre>
 * &#64;ConfigData("network")
 * public record NetworkConfig(&#64;ConfigProperty(value="port", defaultValue="8080") int port,
 *                             &#64;ConfigProperty("serverName") String server) {
 * }
 * </pre>
 * In this example the {@code port} and {@code server} values can easily be accessed by calling the record instance
 * (see {@link Configuration#getConfigData(Class)} for more infos). The property name of the {@code port} property will
 * be {@code "network.port"} and the property name of the {@code server} property will be {@code "network.serverName"}.
 * For the {@code port} the default value {@code "8080"} is defined and will be used if no value is specified by the
 * config.
 */
@Retention(RUNTIME)
@Target(RECORD_COMPONENT)
public @interface ConfigProperty {

    /**
     * A constant that is used to check if a default is defined.
     */
    String UNDEFINED_DEFAULT_VALUE = "com.swirlds.config.value.unconfiguredValue#1j8!-235u-hBHJ-#nxs-!n2n";

    /**
     * A constant that is used to check if a default is defined as null.
     */
    String NULL_DEFAULT_VALUE = "com.swirlds.config.value.nullValue#1j8!-235u-hBHJ-#nxs-!n2n";

    /**
     * This value is used to define the name of the property. the prefix of the property name is defined by the
     * {@link ConfigData} annotation and the suffix is defined by this value while the concrete property name will be
     * {@code PREFIX.SUFFIX}
     *
     * @return the suffix of the name
     */
    String value() default "";

    /**
     * Returns a default value for the property that will be used if the value of the property is not defined in the
     * config. Since an empty string is a valid value {@link ConfigProperty#UNDEFINED_DEFAULT_VALUE} is used as default
     * for this value.
     *
     * @return the default value or {@link ConfigProperty#UNDEFINED_DEFAULT_VALUE}
     */
    String defaultValue() default UNDEFINED_DEFAULT_VALUE;
}
