// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hiero.consensus.platformstate.V0540PlatformStateSchema.PLATFORM_STATE_STATE_ID;
import static org.hiero.consensus.system.SystemExitCode.UPGRADE_FROM_NON_FREEZE_STATE;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.platform.state.PlatformState;
import com.hedera.node.app.fixtures.state.FakeState;
import com.swirlds.platform.system.InitTrigger;
import com.swirlds.state.State;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.hiero.consensus.platformstate.PlatformStateService;
import org.hiero.consensus.system.SystemExitUtils;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/**
 * Tests the guard that refuses to upgrade a node from a saved state that is not a freeze state. Migrating from a
 * non-freeze state on upgrade produces a root hash that diverges from the rest of the network (an ISS), so the guard
 * instead logs a fatal error and cancels node startup via {@link SystemExitUtils#exitSystem}.
 */
class HederaFreezeStateUpgradeGuardTest {
    private static final SemanticVersion PREVIOUS_VERSION =
            SemanticVersion.newBuilder().major(0).minor(67).patch(0).build();
    private static final SemanticVersion CURRENT_VERSION =
            SemanticVersion.newBuilder().major(0).minor(68).patch(0).build();
    private static final Timestamp FREEZE_TIME =
            Timestamp.newBuilder().seconds(1_700_000_000L).build();

    @Test
    void cancelsStartupOnUpgradeRestartFromNonFreezeState() {
        assertStartupCancelled(stateWith(null, null), InitTrigger.RESTART, PREVIOUS_VERSION, CURRENT_VERSION);
    }

    @Test
    void cancelsStartupWhenFreezeScheduledButNotReached() {
        // freezeTime is set but lastFrozenTime never caught up to it, so the network did not freeze in this state
        assertStartupCancelled(stateWith(FREEZE_TIME, null), InitTrigger.RESTART, PREVIOUS_VERSION, CURRENT_VERSION);
    }

    @Test
    void allowsUpgradeRestartFromFreezeState() {
        assertStartupAllowed(
                stateWith(FREEZE_TIME, FREEZE_TIME), InitTrigger.RESTART, PREVIOUS_VERSION, CURRENT_VERSION);
    }

    @Test
    void allowsReconnectFromNonFreezeState() {
        assertStartupAllowed(stateWith(null, null), InitTrigger.RECONNECT, PREVIOUS_VERSION, CURRENT_VERSION);
    }

    @Test
    void allowsSameVersionRestartFromNonFreezeState() {
        assertStartupAllowed(stateWith(null, null), InitTrigger.RESTART, CURRENT_VERSION, CURRENT_VERSION);
    }

    @Test
    void allowsGenesisWithNoDeserializedVersion() {
        assertStartupAllowed(stateWith(null, null), InitTrigger.GENESIS, null, CURRENT_VERSION);
    }

    private static void assertStartupCancelled(
            final State state,
            final InitTrigger trigger,
            @Nullable final SemanticVersion deserializedVersion,
            final SemanticVersion currentVersion) {
        try (MockedStatic<SystemExitUtils> systemExit = mockStatic(SystemExitUtils.class)) {
            // exitSystem is mocked so the JVM does not exit; the subsequent throw provides the assertable control flow
            assertThatThrownBy(() ->
                            Hedera.assertFreezeStateOnUpgrade(state, trigger, deserializedVersion, currentVersion))
                    .isInstanceOf(IllegalStateException.class);
            systemExit.verify(() -> SystemExitUtils.exitSystem(eq(UPGRADE_FROM_NON_FREEZE_STATE), anyString()));
        }
    }

    private static void assertStartupAllowed(
            final State state,
            final InitTrigger trigger,
            @Nullable final SemanticVersion deserializedVersion,
            final SemanticVersion currentVersion) {
        try (MockedStatic<SystemExitUtils> systemExit = mockStatic(SystemExitUtils.class)) {
            assertThatCode(() -> Hedera.assertFreezeStateOnUpgrade(state, trigger, deserializedVersion, currentVersion))
                    .doesNotThrowAnyException();
            systemExit.verifyNoInteractions();
        }
    }

    private static State stateWith(@Nullable final Timestamp freezeTime, @Nullable final Timestamp lastFrozenTime) {
        final var platformState = PlatformState.newBuilder()
                .freezeTime(freezeTime)
                .lastFrozenTime(lastFrozenTime)
                .build();
        return new FakeState()
                .addService(
                        PlatformStateService.NAME,
                        Map.of(PLATFORM_STATE_STATE_ID, new AtomicReference<>(platformState)));
    }
}
