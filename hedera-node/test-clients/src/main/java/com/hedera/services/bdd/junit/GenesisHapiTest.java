// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit;

import static com.hedera.services.bdd.junit.TestTags.ONLY_EMBEDDED;
import static org.junit.jupiter.api.parallel.ResourceAccessMode.READ_WRITE;

import com.hedera.services.bdd.junit.extensions.NetworkTargetingExtension;
import com.hedera.services.bdd.junit.extensions.SpecNamingExtension;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * A variant of {@link HapiTest} that signals the {@link NetworkTargetingExtension} to create a separate
 * embedded network for the test to ensure the test sees the genesis transaction. Even though the embedded
 * network is not shared with any other test, it must run with exclusive access: it takes the {@code NETWORK}
 * resource lock in {@code READ_WRITE} mode so that no other embedded test runs concurrently (they all hold at
 * least a {@code READ} lock on {@code NETWORK}, which a writer excludes). This protects both the per-method
 * network and the process-global JVM system properties we use to configure each embedded network.
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@TestFactory
@Tag(ONLY_EMBEDDED)
@ExtendWith({NetworkTargetingExtension.class, SpecNamingExtension.class})
@ResourceLock(value = "NETWORK", mode = READ_WRITE)
public @interface GenesisHapiTest {
    ConfigOverride[] bootstrapOverrides() default {};
}
