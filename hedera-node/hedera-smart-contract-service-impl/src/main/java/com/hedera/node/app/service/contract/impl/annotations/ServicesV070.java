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
 * Qualifies a binding for use with the {@code v0.70} (Pectra) Services EVM. Updates to the
 * Hedera EVM class, adding support for Account Abstraction (EIP-7702), BLS12-381 precompiles (EIP-2537)
 * and EIP-7632 to increase the call data cost.
 */
@Target({METHOD, PARAMETER, TYPE})
@Retention(RUNTIME)
@Documented
@Qualifier
public @interface ServicesV070 {}
