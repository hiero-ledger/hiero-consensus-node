// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd;

import com.hedera.services.bdd.junit.TestTags;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.Tag;

/**
 * Starts a per-method subprocess network at genesis with the given per-node application property
 * overrides, for tests that need a real multi-node network (e.g. to complete a TSS ceremony) but no
 * block nodes. Mirrors the network setup of {@link HapiBlockNode} without any block node wiring.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Tag(TestTags.GENESIS_SUBPROCESS)
public @interface GenesisSubProcessTest {

    int networkSize() default 4;

    SubProcessNodeConfig[] subProcessNodeConfigs() default {};

    /**
     * Per-node configuration for the subprocess network.
     */
    @interface SubProcessNodeConfig {
        /**
         * The node ID, starting at 0.
         */
        long nodeId();

        String[] applicationPropertiesOverrides() default {};
    }
}
