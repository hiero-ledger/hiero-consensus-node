// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Indicates that the annotated class is the default implementation of an interface or abstract
 * class. This annotation is used in conjunction with a service loader mechanism to identify the
 * default implementation when multiple implementations are available.
 *
 * <p>When a service loader is used to load implementations of a given interface or abstract class,
 * the implementation annotated with {@code @DefaultImplementation} will be preferred if no other
 * specific implementation is requested.
 *
 * <p>Example usage:
 *
 * <pre>
 * &#64;DefaultImplementation
 * public class MyDefaultService implements MyService {
 *     // Implementation details...
 * }
 * </pre>
 *
 * <p>In this example, {@code MyDefaultService} is marked as the default implementation of the
 * {@code MyService} interface. When loading services, if no other implementation is specified, this
 * one will be chosen by default.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface DefaultImplementation {}
