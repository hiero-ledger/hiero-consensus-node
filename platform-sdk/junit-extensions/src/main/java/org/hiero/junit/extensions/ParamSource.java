// SPDX-License-Identifier: Apache-2.0
package org.hiero.junit.extensions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Indicates the parameter name used in the template.
 * Should match the value in {@link ParamName#value()}
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({})
public @interface ParamSource {
    String param();

    String fullyQualifiedClass() default "";

    String method();
}
