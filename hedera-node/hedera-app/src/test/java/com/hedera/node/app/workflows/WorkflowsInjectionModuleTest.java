// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows;

import static com.swirlds.platform.system.InitTrigger.GENESIS;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.swirlds.platform.system.InitTrigger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class WorkflowsInjectionModuleTest {
    @Test
    void genesisBootYieldsUnsetSystemEntitiesFlag() {
        final var flag = WorkflowsInjectionModule.provideMaybeSystemEntitiesCreatedFlag(GENESIS);
        assertNotNull(flag, "a genesis boot must have a system-entities flag so the genesis waiver can run");
        assertFalse(flag.get(), "the flag starts unset until system entities are created");
    }

    /**
     * Contract that keeps {@code DispatchValidator}'s genesis waiver ({@code systemEntitiesCreatedFlag != null &&
     * !flag.get()}) from firing on a live node that did not boot at genesis. If a non-genesis boot ever returned a
     * non-null flag, that flag would stay unset (system entities are not recreated on restart/reconnect), the waiver
     * would fire, and the NODE foreign-payer guard would be skipped — silently reopening the vulnerability. The guard
     * itself is boot-independent (it keys off the block record manager), but this waiver still reads the flag, so the
     * "non-genesis ⇒ null" contract is load-bearing.
     */
    @ParameterizedTest
    @EnumSource(
            value = InitTrigger.class,
            mode = EnumSource.Mode.EXCLUDE,
            names = {"GENESIS"})
    void nonGenesisBootYieldsNullSystemEntitiesFlag(final InitTrigger trigger) {
        assertNull(WorkflowsInjectionModule.provideMaybeSystemEntitiesCreatedFlag(trigger));
    }
}
