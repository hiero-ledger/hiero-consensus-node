// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app;

import static com.swirlds.platform.system.InitTrigger.RECONNECT;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

import com.hedera.node.app.hints.HintsService;
import com.hedera.node.app.history.HistoryService;
import com.swirlds.platform.system.Platform;
import com.swirlds.state.State;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Pins the reconnect lifecycle boundary that clears process-local TSS controllers before a learned state is
 * initialized. The deliberate exception from {@link Platform#getContext()} stops the partial {@link Hedera} mock at
 * the first operation after both controllers must have been reset.
 */
@ExtendWith(MockitoExtension.class)
class HederaReconnectTssControllerResetTest {
    @Mock
    private State state;

    @Mock
    private Platform platform;

    @Mock
    private HintsService hintsService;

    @Mock
    private HistoryService historyService;

    @Test
    void stopsTssControllersBeforeInitializingReconnectState() throws Exception {
        final var hedera = mock(Hedera.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
        final var initializationBoundary = new IllegalStateException("Stop after verifying TSS reset order");
        given(platform.getContext()).willThrow(initializationBoundary);
        setField(hedera, "hintsService", hintsService);
        setField(hedera, "historyService", historyService);

        final var thrown = assertThrows(
                IllegalStateException.class, () -> hedera.onStateInitialized(state, platform, RECONNECT, null));

        assertSame(initializationBoundary, thrown);
        final InOrder inOrder = inOrder(hintsService, historyService, platform);
        inOrder.verify(hintsService).stop();
        inOrder.verify(historyService).stop();
        inOrder.verify(platform).getContext();
    }

    private static void setField(final Object target, final String name, final Object value) throws Exception {
        final Field field = Hedera.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
