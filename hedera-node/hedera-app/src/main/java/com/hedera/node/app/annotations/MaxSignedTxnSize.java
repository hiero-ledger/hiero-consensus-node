// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.annotations;

import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import javax.inject.Qualifier;

/**
 * This annotation is used with dependency injection to inject the maximum size of a signed transaction
 * as an argument to a class constructor.
 *
 * <p>NOTE: We may want to consider moving this value into standard configuration.
 */
@Target({ElementType.METHOD, ElementType.PARAMETER})
@Qualifier
@Retention(RUNTIME)
public @interface MaxSignedTxnSize {}
