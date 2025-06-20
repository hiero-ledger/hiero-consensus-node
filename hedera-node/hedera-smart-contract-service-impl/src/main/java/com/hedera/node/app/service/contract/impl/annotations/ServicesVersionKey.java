// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.annotations;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import com.hedera.node.app.service.contract.impl.hevm.HederaEvmVersion;
import dagger.MapKey;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

@Target({METHOD, PARAMETER, TYPE})
@Retention(RUNTIME)
@Documented
@MapKey
public @interface ServicesVersionKey {
    HederaEvmVersion value();
}
