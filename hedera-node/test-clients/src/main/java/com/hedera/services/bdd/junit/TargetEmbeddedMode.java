// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit;

import com.hedera.services.bdd.junit.hedera.embedded.EmbeddedMode;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation for a test class whose {@link HapiTest}s expect to be run against an embedded network with the
 * given {@link EmbeddedMode}.
 */
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface TargetEmbeddedMode {
    /**
     * The {@link EmbeddedMode} the test class must run in.
     */
    EmbeddedMode value();
}
