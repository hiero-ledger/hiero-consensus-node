// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit;

import static org.junit.jupiter.api.parallel.ResourceAccessMode.READ;

import com.hedera.services.bdd.junit.extensions.MultiNetworkExtension;
import com.hedera.services.bdd.junit.extensions.SpecNamingExtension;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * Marks a HAPI test factory that provisions multiple isolated subprocess networks and injects them
 * as {@code HederaNetwork} parameters in declaration order.
 *
 * <p>This annotation replaces {@link HapiTest} for multi-network scenarios; do not combine them.
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@TestFactory
@ExtendWith({MultiNetworkExtension.class, SpecNamingExtension.class})
@ResourceLock(value = "NETWORK", mode = READ)
public @interface MultiNetworkHapiTest {
    Network[] networks() default {
        @Network(name = "PRIMARY"), @Network(name = "PEER"),
    };

    @interface Network {
        String name();

        int size() default 4;

        long shard() default -1;

        long realm() default -1;

        int firstGrpcPort() default -1;

        ConfigOverride[] setupOverrides() default {};
    }
}
