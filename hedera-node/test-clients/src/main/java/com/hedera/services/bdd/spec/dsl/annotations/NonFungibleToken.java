// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.dsl.annotations;

import static com.hedera.services.bdd.spec.dsl.entities.SpecTokenKey.ADMIN_KEY;
import static com.hedera.services.bdd.spec.dsl.entities.SpecTokenKey.SUPPLY_KEY;

import com.hedera.services.bdd.junit.extensions.SpecEntityExtension;
import com.hedera.services.bdd.spec.dsl.entities.SpecTokenKey;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Describes a {@link com.hedera.services.bdd.spec.dsl.entities.SpecToken}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@ExtendWith(SpecEntityExtension.class)
public @interface NonFungibleToken {
    /**
     * If set, a {@link com.hedera.services.bdd.spec.HapiSpec} name to use for the token.
     *
     * @return the spec name of the contract
     */
    String name() default "";

    /**
     * The types of keys to associate with the token.
     *
     * @return the types of keys to associate with the token
     */
    SpecTokenKey[] keys() default {ADMIN_KEY, SUPPLY_KEY};

    /**
     * The number of pre-mints to perform.
     *
     * @return the number of pre-mints to perform
     */
    int numPreMints() default 0;

    /**
     * Whether to use an auto-renew account for the token.
     *
     * @return whether to use an auto-renew account for the token
     */
    boolean useAutoRenewAccount() default false;
}
