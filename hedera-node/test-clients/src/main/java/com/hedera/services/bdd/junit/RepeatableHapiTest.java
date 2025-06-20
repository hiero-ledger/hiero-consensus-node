// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit;

import static com.hedera.services.bdd.junit.TestTags.ONLY_REPEATABLE;
import static org.junit.jupiter.api.parallel.ExecutionMode.SAME_THREAD;

import com.hedera.services.bdd.junit.extensions.NetworkTargetingExtension;
import com.hedera.services.bdd.junit.extensions.SpecNamingExtension;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * Annotation for a {@link HapiTest} that can only be run in repeatable mode. The {@link RepeatableReason} annotation
 * enumerates common reasons a test has to run in repeatable mode. Execution mode is always
 * {@link ExecutionMode#SAME_THREAD} because repeatable tests cannot be run concurrently.
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@TestFactory
@ExtendWith({NetworkTargetingExtension.class, SpecNamingExtension.class})
@Execution(SAME_THREAD)
@Tag(ONLY_REPEATABLE)
public @interface RepeatableHapiTest {
    /**
     * The reasons the test has to run in repeatable mode.
     * @return the reasons the test has to run in repeatable mode
     */
    RepeatableReason[] value();
}
