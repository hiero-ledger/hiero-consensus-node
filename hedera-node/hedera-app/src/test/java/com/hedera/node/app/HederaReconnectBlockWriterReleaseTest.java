// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app;

import static com.swirlds.platform.system.InitTrigger.RECONNECT;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

import com.hedera.node.app.blocks.BlockStreamManager;
import com.hedera.node.app.blocks.impl.streaming.BlockNodeConnectionManager;
import com.swirlds.common.notification.NotificationEngine;
import com.swirlds.platform.listeners.ReconnectCompleteListener;
import com.swirlds.platform.system.InitTrigger;
import com.swirlds.platform.system.Platform;
import com.swirlds.state.State;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Pins that a reconnect releases the block writers of the replaced block stream manager before the new object graph
 * is built. The deliberate exception from {@link NotificationEngine#register} stops the partial {@link Hedera} mock
 * at the first operation after the old graph is torn down.
 */
@ExtendWith(MockitoExtension.class)
class HederaReconnectBlockWriterReleaseTest {
    @Mock
    private State state;

    @Mock
    private Platform platform;

    @Mock
    private NotificationEngine notifications;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private HederaInjectionComponent replacedApp;

    @Mock
    private BlockStreamManager blockStreamManager;

    @Mock
    private BlockNodeConnectionManager blockNodeConnectionManager;

    @Test
    void abandonsBlockWritersOfReplacedManagerBeforeBuildingNewGraph() throws Exception {
        final var hedera = mock(Hedera.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
        final var teardownBoundary = new IllegalStateException("Stop after the old graph is torn down");
        given(platform.getNotificationEngine()).willReturn(notifications);
        given(notifications.register(eq(ReconnectCompleteListener.class), any()))
                .willThrow(teardownBoundary);
        given(replacedApp.blockStreamManager()).willReturn(blockStreamManager);
        given(replacedApp.blockNodeConnectionManager()).willReturn(blockNodeConnectionManager);
        doReturn(true).when(hedera).isBlockStreamEnabled();
        doNothing().when(hedera).shutdownGrpcServer();
        setField(hedera, "platform", platform);
        setField(hedera, "daggerApp", replacedApp);
        final var initializeDagger = Hedera.class.getDeclaredMethod("initializeDagger", State.class, InitTrigger.class);
        initializeDagger.setAccessible(true);

        final var thrown =
                assertThrows(InvocationTargetException.class, () -> initializeDagger.invoke(hedera, state, RECONNECT));

        assertSame(teardownBoundary, thrown.getCause());
        final InOrder inOrder = inOrder(blockNodeConnectionManager, blockStreamManager, notifications);
        inOrder.verify(blockNodeConnectionManager).shutdown();
        inOrder.verify(blockStreamManager).abandonOpenBlocks();
        inOrder.verify(notifications).register(eq(ReconnectCompleteListener.class), any());
    }

    private static void setField(final Object target, final String name, final Object value) throws Exception {
        final Field field = Hedera.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
