// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.notification;

import static com.swirlds.common.threading.manager.AdHocThreadManager.getStaticThreadManager;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.swirlds.common.notification.internal.Dispatcher;
import com.swirlds.common.test.fixtures.junit.tags.TestComponentTags;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.hiero.consensus.model.utility.StandardFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class DispatcherTests {

    @Test
    @Tag(TestComponentTags.NOTIFICATION)
    @DisplayName("Notification Engine: Dispatcher Sync Exception Handling")
    void validateSyncExceptionHandling() throws InterruptedException, TimeoutException {
        final Dispatcher<SyncOrderedIntegerListener> syncDispatcher =
                new Dispatcher<>(getStaticThreadManager(), SyncOrderedIntegerListener.class);

        syncDispatcher.addListener((n) -> {
            throw new RuntimeException(n.toString());
        });

        final IntegerNotification notification = new IntegerNotification(1);

        final StandardFuture<NotificationResult<IntegerNotification>> future = new StandardFuture<>();
        syncDispatcher.notifySync(notification, future::complete);

        final NotificationResult<IntegerNotification> result = future.getAndRethrow(5, TimeUnit.SECONDS);

        assertNotNull(result, "The notification result was null but expected not null");
        assertEquals(
                1,
                result.getFailureCount(),
                String.format(
                        "The result should contain 1 failure but instead contained %d failures",
                        result.getFailureCount()));
        assertEquals(
                1,
                result.getExceptions().size(),
                String.format(
                        "The result should contain 1 exception but instead contained %d exceptions",
                        result.getExceptions().size()));
        assertEquals(
                RuntimeException.class,
                result.getExceptions().get(0).getClass(),
                String.format(
                        "The result should contain a single RuntimeException but instead contained a %s",
                        result.getExceptions().get(0).getClass().getSimpleName()));
    }

    @Test
    @Tag(TestComponentTags.NOTIFICATION)
    @DisplayName("Notification Engine: Dispatcher ASync Exception Handling")
    void validateASyncExceptionHandling() throws InterruptedException, TimeoutException {
        final Dispatcher<AsyncOrderedIntegerListener> asyncDispatcher =
                new Dispatcher<>(getStaticThreadManager(), AsyncOrderedIntegerListener.class);

        asyncDispatcher.addListener((n) -> {
            throw new RuntimeException(n.toString());
        });

        final StandardFuture<NotificationResult<IntegerNotification>> future = new StandardFuture<>();
        asyncDispatcher.notifyAsync(new IntegerNotification(1), future::complete);

        final NotificationResult<IntegerNotification> result = future.getAndRethrow(5, TimeUnit.SECONDS);

        assertNotNull(result, "The notification result was null but expected not null");
        assertEquals(
                1,
                result.getFailureCount(),
                String.format(
                        "The result should contain 1 failure but instead contained %d failures",
                        result.getFailureCount()));
        assertEquals(
                1,
                result.getExceptions().size(),
                String.format(
                        "The result should contain 1 exception but instead contained %d exceptions",
                        result.getExceptions().size()));
        assertEquals(
                RuntimeException.class,
                result.getExceptions().get(0).getClass(),
                String.format(
                        "The result should contain a single RuntimeException but instead contained a %s",
                        result.getExceptions().get(0).getClass().getSimpleName()));
    }
}
