// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.dsl.annotations;

import com.hedera.services.bdd.junit.extensions.SpecEntityExtension;
import com.hedera.services.bdd.spec.dsl.entities.SpecKey;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@ExtendWith(SpecEntityExtension.class)
public @interface Key {
    /**
     * If set, a {@link com.hedera.services.bdd.spec.HapiSpec} name to use for the key.
     *
     * @return the spec name of the key
     */
    String name() default "";

    /**
     * The type of key to create.
     *
     * @return the type of key to create
     */
    SpecKey.Type type() default SpecKey.Type.ED25519;
}
