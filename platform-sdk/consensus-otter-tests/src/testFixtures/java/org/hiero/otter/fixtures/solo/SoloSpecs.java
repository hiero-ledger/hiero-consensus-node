// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.solo;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation to specify optional configuration parameters that are specific to the Solo environment.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface SoloSpecs {}
