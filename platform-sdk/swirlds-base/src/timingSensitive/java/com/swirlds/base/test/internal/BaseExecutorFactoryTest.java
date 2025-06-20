// SPDX-License-Identifier: Apache-2.0
package com.swirlds.base.test.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.swirlds.base.internal.BaseExecutorFactory;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

public class BaseExecutorFactoryTest {

    @Test
    void testSubmitRunnable() {
        // given
        final BaseExecutorFactory baseExecutorFactory = BaseExecutorFactory.getInstance();
        final CountDownLatch countDownLatch = new CountDownLatch(1);

        // when
        final Future<Void> future = baseExecutorFactory.submit(() -> countDownLatch.countDown());

        // then
        assertThatNoException().isThrownBy(() -> countDownLatch.await(5, TimeUnit.SECONDS));
        assertThatNoException().isThrownBy(() -> future.get(1, TimeUnit.SECONDS));
    }

    @Test
    void testSubmitCallable() {
        // given
        final BaseExecutorFactory baseExecutorFactory = BaseExecutorFactory.getInstance();
        final CountDownLatch countDownLatch = new CountDownLatch(1);

        // when
        final Future<Void> future = baseExecutorFactory.submit(() -> {
            countDownLatch.countDown();
            return null;
        });

        // then
        assertThatNoException().isThrownBy(() -> countDownLatch.await(5, TimeUnit.SECONDS));
        assertThatNoException().isThrownBy(() -> future.get(1, TimeUnit.SECONDS));
    }

    @Test
    void testSchedule() {
        // given
        final BaseExecutorFactory baseExecutorFactory = BaseExecutorFactory.getInstance();
        final CountDownLatch countDownLatch = new CountDownLatch(1);

        // when
        final ScheduledFuture<Void> future =
                baseExecutorFactory.schedule(() -> countDownLatch.countDown(), 10, TimeUnit.MILLISECONDS);

        // then
        assertThatNoException().isThrownBy(() -> countDownLatch.await(5, TimeUnit.SECONDS));
        assertThatNoException().isThrownBy(() -> future.get(1, TimeUnit.SECONDS));
    }

    @Test
    void testScheduleAtFixedRate() {
        // given
        final BaseExecutorFactory baseExecutorFactory = BaseExecutorFactory.getInstance();
        final CountDownLatch countDownLatch = new CountDownLatch(10);

        // when
        final ScheduledFuture<Void> future = baseExecutorFactory.scheduleAtFixedRate(
                () -> countDownLatch.countDown(), 10, 10, TimeUnit.MILLISECONDS);

        // then
        assertThatNoException().isThrownBy(() -> countDownLatch.await(5, TimeUnit.SECONDS));
        assertThatNoException().isThrownBy(() -> future.cancel(true));
        assertThat(future.isCancelled()).isTrue();
    }

    @Test
    void testMultipleTasks() {
        // given
        final BaseExecutorFactory baseExecutorFactory = BaseExecutorFactory.getInstance();
        final CountDownLatch countDownLatchRunnable = new CountDownLatch(1);
        final CountDownLatch countDownLatchCallable = new CountDownLatch(1);
        final CountDownLatch countDownLatchScheduled = new CountDownLatch(1);
        final CountDownLatch countDownLatchScheduledAtFixedRate = new CountDownLatch(10);

        // when
        final Future<Void> futureRunnable = baseExecutorFactory.submit(() -> countDownLatchRunnable.countDown());
        final Future<Void> futureCallable = baseExecutorFactory.submit(() -> {
            countDownLatchCallable.countDown();
            return null;
        });
        final ScheduledFuture<Void> futureScheduled =
                baseExecutorFactory.schedule(() -> countDownLatchScheduled.countDown(), 10, TimeUnit.MILLISECONDS);
        final ScheduledFuture<Void> futureScheduledAtFixedRate = baseExecutorFactory.scheduleAtFixedRate(
                () -> countDownLatchScheduledAtFixedRate.countDown(), 10, 10, TimeUnit.MILLISECONDS);

        // then
        assertThatNoException().isThrownBy(() -> countDownLatchRunnable.await(5, TimeUnit.SECONDS));
        assertThatNoException().isThrownBy(() -> countDownLatchCallable.await(5, TimeUnit.SECONDS));
        assertThatNoException().isThrownBy(() -> countDownLatchScheduled.await(5, TimeUnit.SECONDS));
        assertThatNoException().isThrownBy(() -> countDownLatchScheduledAtFixedRate.await(5, TimeUnit.SECONDS));
        assertThatNoException().isThrownBy(() -> futureRunnable.get(1, TimeUnit.SECONDS));
        assertThatNoException().isThrownBy(() -> futureCallable.get(1, TimeUnit.SECONDS));
        assertThatNoException().isThrownBy(() -> futureScheduled.get(1, TimeUnit.SECONDS));
        assertThatNoException().isThrownBy(() -> futureScheduledAtFixedRate.cancel(true));
        assertThat(futureScheduledAtFixedRate.isCancelled()).isTrue();
    }
}
