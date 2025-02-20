// SPDX-License-Identifier: Apache-2.0
package com.swirlds.cli.utility;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Indicate that this command is the subcommand of another. This command will automatically
 * be registered with its parent dynamically at runtime.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface SubcommandOf {

    /**
     * The parent command.
     *
     * @return the parent command
     */
    Class<?> value();
}
