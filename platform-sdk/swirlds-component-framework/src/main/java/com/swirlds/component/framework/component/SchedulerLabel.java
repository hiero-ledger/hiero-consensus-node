// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.component;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotates a name that should be used as an override for the task scheduler's name instead of a component's interface
 * name. Can also be used to annotate a method parameter used to implement a transformer/filter (these get turned into
 * direct schedulers, which need to be named).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface SchedulerLabel {

    /**
     * The label of the task scheduler that will operate the transformer/filter.
     *
     * @return the label of the task scheduler that will operate the transformer/filter
     */
    @NonNull
    String value();
}
