// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.components;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.swirlds.common.notification.NotificationEngine;
import com.swirlds.common.notification.NotificationResult;
import com.swirlds.platform.components.appcomm.CompleteStateNotificationWithCleanup;
import com.swirlds.platform.listeners.PlatformStatusChangeListener;
import com.swirlds.platform.listeners.PlatformStatusChangeNotification;
import com.swirlds.platform.listeners.ReconnectCompleteListener;
import com.swirlds.platform.listeners.ReconnectCompleteNotification;
import com.swirlds.platform.listeners.StateWriteToDiskCompleteListener;
import com.swirlds.platform.listeners.StateWriteToDiskCompleteNotification;
import com.swirlds.platform.system.state.notifications.AsyncFatalIssListener;
import com.swirlds.platform.system.state.notifications.IssListener;
import com.swirlds.platform.system.state.notifications.NewSignedStateListener;
import com.swirlds.platform.system.state.notifications.NewSignedStateNotification;
import com.swirlds.platform.system.state.notifications.StateHashedListener;
import com.swirlds.platform.system.state.notifications.StateHashedNotification;
import com.swirlds.state.MerkleNodeState;
import java.time.Instant;
import java.util.List;
import org.hiero.base.concurrent.futures.StandardFuture.CompletionCallback;
import org.hiero.base.crypto.DigestType;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.model.notification.IssNotification;
import org.hiero.consensus.model.notification.IssNotification.IssType;
import org.hiero.consensus.model.status.PlatformStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

public class DefaultAppNotifierTest {

    NotificationEngine notificationEngine;
    AppNotifier notifier;

    @BeforeEach
    void beforeEach() {
        notificationEngine = mock(NotificationEngine.class);
        notifier = new DefaultAppNotifier(notificationEngine);
    }

    @Test
    void testStateWrittenToDiskNotificationSent() {
        final StateWriteToDiskCompleteNotification notification =
                new StateWriteToDiskCompleteNotification(100, Instant.now(), false);

        assertDoesNotThrow(() -> notifier.sendStateWrittenToDiskNotification(notification));
        verify(notificationEngine, times(1)).dispatch(StateWriteToDiskCompleteListener.class, notification);
        verifyNoMoreInteractions(notificationEngine);
    }

    @Test
    void testStateHashNotificationSent() {
        final StateHashedNotification notification = new StateHashedNotification(100L, new Hash(DigestType.SHA_384));

        assertDoesNotThrow(() -> notifier.sendStateHashedNotification(notification));
        verify(notificationEngine, times(1)).dispatch(StateHashedListener.class, notification);
        verifyNoMoreInteractions(notificationEngine);
    }

    @Test
    void testReconnectCompleteNotificationSent() {
        final MerkleNodeState state = mock(MerkleNodeState.class);
        final ReconnectCompleteNotification notification =
                new ReconnectCompleteNotification(100L, Instant.now(), state);

        assertDoesNotThrow(() -> notifier.sendReconnectCompleteNotification(notification));
        verify(notificationEngine, times(1)).dispatch(ReconnectCompleteListener.class, notification);
        verifyNoMoreInteractions(notificationEngine);
    }

    @Test
    void testPlatformStatusChangeNotificationSent() {
        final PlatformStatus status = PlatformStatus.ACTIVE;
        final ArgumentCaptor<PlatformStatusChangeNotification> captor =
                ArgumentCaptor.forClass(PlatformStatusChangeNotification.class);

        assertDoesNotThrow(() -> notifier.sendPlatformStatusChangeNotification(status));
        verify(notificationEngine, times(1)).dispatch(eq(PlatformStatusChangeListener.class), captor.capture());
        verifyNoMoreInteractions(notificationEngine);

        final PlatformStatusChangeNotification notification = captor.getValue();
        assertNotNull(notification);
        assertEquals(status, notification.getNewStatus());
    }

    @Test
    void testLatestCompleteStateNotificationSent() {
        final MerkleNodeState state = mock(MerkleNodeState.class);
        final CompletionCallback<NotificationResult<NewSignedStateNotification>> cleanup =
                mock(CompletionCallback.class);
        final NewSignedStateNotification signedStateNotification =
                new NewSignedStateNotification(state, 100L, Instant.now());
        final CompleteStateNotificationWithCleanup notificationWithCleanup =
                new CompleteStateNotificationWithCleanup(signedStateNotification, cleanup);

        assertDoesNotThrow(() -> notifier.sendLatestCompleteStateNotification(notificationWithCleanup));
        verify(notificationEngine, times(1)).dispatch(NewSignedStateListener.class, signedStateNotification, cleanup);
        verifyNoMoreInteractions(notificationEngine);
    }

    public static List<Arguments> issTypes() {
        return List.of(
                Arguments.of(IssType.CATASTROPHIC_ISS, true),
                Arguments.of(IssType.SELF_ISS, true),
                Arguments.of(IssType.OTHER_ISS, false));
    }

    @ParameterizedTest
    @MethodSource("issTypes")
    void testIssNotificationSent(final IssType type, final boolean isFatal) {
        final IssNotification notification = new IssNotification(100L, type);

        assertDoesNotThrow(() -> notifier.sendIssNotification(notification));

        // verify the ISS notification is always sent to the IssListener
        verify(notificationEngine, times(1)).dispatch(IssListener.class, notification);

        if (isFatal) {
            // if the ISS event is considered fatal to the local node, verify the event is also sent to the
            // FatalIssListener
            verify(notificationEngine, times(1)).dispatch(AsyncFatalIssListener.class, notification);
        }

        verifyNoMoreInteractions(notificationEngine);
    }
}
