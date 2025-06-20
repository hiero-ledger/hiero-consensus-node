// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.annotations;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import javax.inject.Qualifier;

/**
 * Identifies the resource prices to be used for child transactions.
 */
@Target({METHOD, PARAMETER, TYPE})
@Retention(RUNTIME)
@Documented
@Qualifier
public @interface ChildTransactionResourcePrices {}
