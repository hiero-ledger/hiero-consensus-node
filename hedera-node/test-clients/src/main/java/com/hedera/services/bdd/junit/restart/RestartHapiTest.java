// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.restart;

import static com.hedera.services.bdd.junit.TestTags.ONLY_REPEATABLE;
import static org.junit.jupiter.api.parallel.ExecutionMode.SAME_THREAD;

import com.hedera.node.app.fixtures.state.FakeState;
import com.hedera.services.bdd.junit.ConfigOverride;
import com.hedera.services.bdd.junit.HapiTest;
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

/**
 * Annotation for a repeatable {@link HapiTest} that exercises the restart phase by creating a new embedded network
 * separate from the shared network. The type of restart scenario is distinguished by the presence or absence of two
 * on-disk assets: A saved state and an override network roster.
 * <p>
 * If a saved state is present, it may be from the previous software version or the current version; and as of release
 * {@code 0.57}, it may or may not have a TSS ledger id already in state. (But note that after the production deployment
 * of TSS, every saved state must already have a TSS ledger id.)
 * <p>
 * If an override network roster is present, it similarly may or may not come have a pre-generated TSS ledger id. (Even
 * after all production networks have TSS enabled, it may be occasionally useful to be able to transplant just roster
 * information and have the target network generate a new ledger id.)
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@TestFactory
@ExtendWith({NetworkTargetingExtension.class, SpecNamingExtension.class})
@Execution(SAME_THREAD)
@Tag(ONLY_REPEATABLE)
public @interface RestartHapiTest {
    /**
     * The type of restart being tested.
     */
    RestartType restartType() default RestartType.GENESIS;

    /**
     * Any overrides that should be present when creating the setup state before restart.
     */
    ConfigOverride[] setupOverrides() default {};

    /**
     * Any overrides that should be present at restart.
     */
    ConfigOverride[] restartOverrides() default {};

    /**
     * The type of startup assets that should be present on disk when creating the setup state before restart.
     */
    StartupAssets setupAssets() default StartupAssets.NONE;

    /**
     * The type of startup assets that should be present on disk for the test.
     */
    StartupAssets restartAssets() default StartupAssets.NONE;

    /**
     * The type of saved state spec that should be used to customize the state of the {@link FakeState} when
     * using {@link RestartType#SAME_VERSION} or {@link RestartType#UPGRADE_BOUNDARY}.
     */
    Class<? extends SavedStateSpec> savedStateSpec() default NoopSavedStateSpec.class;
}
