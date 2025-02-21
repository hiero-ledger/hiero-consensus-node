// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.annotations;

import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import javax.inject.Qualifier;

/**
 * This annotation is used with dependency injection to inject the node's self account ID as an argument to a
 * class constructor.
 */
@Target({ElementType.METHOD, ElementType.PARAMETER})
@Qualifier
@Retention(RUNTIME)
public @interface NodeSelfId {}
