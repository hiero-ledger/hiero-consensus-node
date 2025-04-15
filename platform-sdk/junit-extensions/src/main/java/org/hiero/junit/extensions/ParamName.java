// SPDX-License-Identifier: Apache-2.0
package org.hiero.junit.extensions;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Indicates the parameter name used in the template.
 * Should match the value in {@link ParamSource#param()}
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.PARAMETER})
public @interface ParamName {
    String value();
}
