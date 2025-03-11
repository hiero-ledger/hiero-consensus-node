// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.component;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Label the input wire that a method is associated with.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface InputWireLabel {

    /**
     * The label of the input wire.
     *
     * @return the label of the input wire
     */
    @NonNull
    String value();
}
