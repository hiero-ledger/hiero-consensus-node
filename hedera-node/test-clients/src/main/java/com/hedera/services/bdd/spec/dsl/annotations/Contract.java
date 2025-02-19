/*
 * Copyright (C) 2024-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hedera.services.bdd.spec.dsl.annotations;

import com.hedera.services.bdd.junit.extensions.SpecEntityExtension;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Describes a {@link com.hedera.services.bdd.spec.dsl.entities.SpecContract}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@ExtendWith(SpecEntityExtension.class)
public @interface Contract {
    /**
     * If set, a different {@link com.hedera.services.bdd.spec.HapiSpec} name to use for the contract.
     *
     * @return the spec name of the contract
     */
    String name() default "";

    /**
     * The name of the contract; must refer to a contract in the classpath resources.
     *
     * @return the name of the contract
     */
    String contract();

    /**
     * The amount of gas to use when creating the contract.
     *
     * @return the amount of gas to use
     */
    long creationGas() default 250_000L;

    /**
     * Whether this contract is immutable.
     * @return {@code true} if the contract is immutable, {@code false} otherwise
     */
    boolean isImmutable() default false;

    /**
     * If set, the maximum number of auto-associations to allow for the contract.
     * @return the maximum number of auto-associations
     */
    int maxAutoAssociations() default 0;

    /**
     * If set, specifies the variant version of a system contract.  This affects the location to search in the
     * resources directory for the contract.
     */
    String variant() default "";
}
