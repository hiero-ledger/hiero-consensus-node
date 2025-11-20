// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit;

import com.hedera.services.bdd.junit.extensions.DualNetworkExtension;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Marks a HAPI test factory that needs two isolated subprocess networks.
 *
 * <p>This annotation augments (rather than replaces) the other {@code @Hapi*} annotations.
 * For example, pair it with {@link HapiTest}, {@link GenesisHapiTest}, or {@link LeakyHapiTest}
 * so those extensions still provide the base HAPI wiring while this annotation provisions the
 * dual-network context.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(DualNetworkExtension.class)
public @interface DualNetworkHapiTest {
    String primaryName() default "PRIMARY";

    String peerName() default "PEER";

    int primarySize() default 4;

    int peerSize() default 4;
}
