// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.hedera;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation to configure block node mode for test suites.
 * Can be applied to test classes to enable block nodes for all tests in the suite.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE})
public @interface WithBlockNodes {
    /** The block node mode to use for this test suite */
    BlockNodeMode value() default BlockNodeMode.NONE;
}
