// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config;

import static java.lang.annotation.ElementType.RECORD_COMPONENT;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import com.swirlds.config.api.ConfigProperty;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Annotates a specific configuration property (annotated with {@link ConfigProperty}) as being a property common to
 * all nodes on the network. Such properties are expected to be saved in state. These properties <b>must</b> be the
 * same on all nodes in the network, or they will ISS at some point. This annotation is mutually exclusive with
 * {@link NodeProperty}.
 */
@Retention(RUNTIME)
@Target(RECORD_COMPONENT)
public @interface NetworkProperty {}
