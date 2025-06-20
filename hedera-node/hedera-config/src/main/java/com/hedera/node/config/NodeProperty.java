// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config;

import static java.lang.annotation.ElementType.RECORD_COMPONENT;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import com.swirlds.config.api.ConfigProperty;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Annotates a specific configuration property (annotated with {@link ConfigProperty}) as being a property specific
 * to the node itself. Such properties can have (and almost certainly do have) different values for each node in the
 * network. This annotation is mutually exclusive with {@link NetworkProperty}.
 */
@Retention(RUNTIME)
@Target(RECORD_COMPONENT)
public @interface NodeProperty {}
