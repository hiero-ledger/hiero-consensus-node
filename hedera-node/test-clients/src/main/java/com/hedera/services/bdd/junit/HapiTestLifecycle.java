// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit;

import com.hedera.services.bdd.junit.extensions.TestLifecycleExtension;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Marks a test class whose {@link org.junit.jupiter.api.BeforeAll} and {@link org.junit.jupiter.api.AfterAll}
 * lifecycle methods want to have an injected {@link TestLifecycle}.
 */
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(TestLifecycleExtension.class)
public @interface HapiTestLifecycle {}
