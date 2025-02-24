// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.components.appcomm;

import static com.swirlds.common.test.fixtures.AssertionUtils.assertEventuallyEquals;
import static com.swirlds.common.threading.manager.AdHocThreadManager.getStaticThreadManager;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.swirlds.common.notification.NotificationEngine;
import com.swirlds.platform.state.signed.SignedState;
import com.swirlds.platform.system.state.notifications.NewSignedStateListener;
import com.swirlds.platform.test.fixtures.state.RandomSignedStateGenerator;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Basic sanity check tests for the {@link DefaultLatestCompleteStateNotifier} class
 */
public class LatestCompleteStateNotifierTests {

    @Test
    @DisplayName("NewLatestCompleteStateEventNotification")
    void testNewLatestCompleteStateEventNotification() throws InterruptedException {
        final NotificationEngine notificationEngine = NotificationEngine.buildEngine(getStaticThreadManager());
        final SignedState signedState = new RandomSignedStateGenerator().build();

        final CountDownLatch senderLatch = new CountDownLatch(1);
        final CountDownLatch listenerLatch = new CountDownLatch(1);
        final AtomicInteger numInvocations = new AtomicInteger();

        AtomicReference<Throwable> notificationProcessingErrorRef = new AtomicReference<>();
        notificationEngine.register(NewSignedStateListener.class, n -> {
            numInvocations.incrementAndGet();
            try {
                assertThat(senderLatch.await(1, TimeUnit.SECONDS)).isTrue();
                assertFalse(
                        n.getState().isDestroyed(), "State should not be destroyed until the callback has completed");
                assertEquals(signedState.getState(), n.getState(), "Unexpected State");
            } catch (Throwable e) {
                notificationProcessingErrorRef.set(e);
                throw new RuntimeException(e);
            } finally {
                listenerLatch.countDown();
            }
        });

        final LatestCompleteStateNotifier component = new DefaultLatestCompleteStateNotifier();
        final CompleteStateNotificationWithCleanup notificationWithCleanup = component.latestCompleteStateHandler(
                signedState.reserve("testNewLatestCompleteStateEventNotification"));

        assertNotEquals(null, notificationWithCleanup, "component output should not be null");

        notificationEngine.dispatch(
                NewSignedStateListener.class,
                notificationWithCleanup.notification(),
                notificationWithCleanup.cleanup());

        // Allow the notification callback to execute
        senderLatch.countDown();

        // Wait for the notification callback to complete
        assertThat(listenerLatch.await(5, TimeUnit.SECONDS)).isTrue();
        assertNull(notificationProcessingErrorRef.get(), () -> {
            StringWriter out = new StringWriter();
            PrintWriter writer = new PrintWriter(out);
            notificationProcessingErrorRef.get().printStackTrace(writer);
            return "Unexpected error: " + out;
        });

        // The notification listener has completed, but the post-listener callback may not have yet
        assertEventuallyEquals(
                -1, signedState::getReservationCount, Duration.ofSeconds(1), "Signed state should be fully released");
        assertEquals(1, numInvocations.get(), "Unexpected number of notification callbacks");
    }
}
