// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd;

import com.hedera.services.bdd.junit.hedera.BlockNodeMode;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface HapiBlockNode {

    int networkSize() default 4;

    BlockNodeConfig[] blockNodeConfigs() default {};

    /**
     * Specific configurations for individual nodes.
     * If this is specified, blockNodeMode and connectionInfo are ignored.
     */
    SubProcessNodeConfig[] subProcessNodeConfigs() default {};

    /**
     * Configuration for a specific block node (simulator or real).
     */
    @interface BlockNodeConfig {
        /**
         * The block node ID, starting at 0.
         */
        long nodeId();

        /**
         * The block node mode for this node.
         */
        BlockNodeMode mode();
    }

    /**
     * Configuration for a specific SubProcessNode.
     */
    @interface SubProcessNodeConfig {
        /**
         * The node ID, starting at 0.
         */
        long nodeId();

        long[] blockNodeIds() default {};

        long[] blockNodePriorities() default {};

        String[] applicationPropertiesOverrides() default {};
    }
}
