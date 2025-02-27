// SPDX-License-Identifier: Apache-2.0
package com.swirlds.base.test.internal.observe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

import com.swirlds.base.internal.BaseExecutorFactory;
import com.swirlds.base.internal.BaseTask;
import com.swirlds.base.internal.observe.BaseExecutorObserver;
import com.swirlds.base.internal.observe.BaseTaskDefinition;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class BaseExecutorObserverTest {

    public static final String TEST_TASK = "test-task";

    private static class DummyBaseExecutorObserver implements BaseExecutorObserver {

        private final AtomicReference<BaseTaskDefinition> taskSubmittedDefinition = new AtomicReference<>();

        private final AtomicReference<BaseTaskDefinition> taskStartedTaskDefinition = new AtomicReference<>();

        private final AtomicReference<BaseTaskDefinition> taskDoneTaskDefinition = new AtomicReference<>();

        private final AtomicReference<BaseTaskDefinition> taskFailedTaskDefinition = new AtomicReference<>();

        private final AtomicReference<Duration> taskDoneDuration = new AtomicReference<>();

        private final AtomicReference<Duration> taskFailedDuration = new AtomicReference<>();

        private final CountDownLatch taskSubmittedCountDownLatch = new CountDownLatch(1);

        private final CountDownLatch taskStartedCountDownLatch = new CountDownLatch(1);

        private final CountDownLatch taskDoneCountDownLatch = new CountDownLatch(1);

        private final CountDownLatch taskFailedCountDownLatch = new CountDownLatch(1);

        @Override
        public void onTaskSubmitted(BaseTaskDefinition taskDefinition) {
            taskSubmittedDefinition.set(taskDefinition);
            taskSubmittedCountDownLatch.countDown();
        }

        @Override
        public void onTaskStarted(BaseTaskDefinition taskDefinition) {
            taskStartedTaskDefinition.set(taskDefinition);
            taskStartedCountDownLatch.countDown();
        }

        @Override
        public void onTaskDone(BaseTaskDefinition taskDefinition, Duration duration) {
            taskDoneTaskDefinition.set(taskDefinition);
            taskDoneDuration.set(duration);
            taskDoneCountDownLatch.countDown();
        }

        @Override
        public void onTaskFailed(BaseTaskDefinition taskDefinition, Duration duration) {
            taskFailedTaskDefinition.set(taskDefinition);
            taskFailedDuration.set(duration);
            taskFailedCountDownLatch.countDown();
        }

        public void awaitTaskSubmitted(long timeout, TimeUnit unit) throws InterruptedException {
            taskSubmittedCountDownLatch.await(timeout, unit);
        }

        public void awaitTaskStarted(long timeout, TimeUnit unit) throws InterruptedException {
            taskStartedCountDownLatch.await(timeout, unit);
        }

        public void awaitTaskDone(long timeout, TimeUnit unit) throws InterruptedException {
            taskDoneCountDownLatch.await(timeout, unit);
        }

        public void awaitTaskFailed(long timeout, TimeUnit unit) throws InterruptedException {
            taskFailedCountDownLatch.await(timeout, unit);
        }

        public void checkDoneNotCalled() {
            assertThat(taskDoneCountDownLatch.getCount()).isNotEqualTo(0);
        }

        public void checkFailedNotCalled() {
            assertThat(taskFailedCountDownLatch.getCount()).isNotEqualTo(0);
        }

        public void checkSubmittedNotCalled() {
            assertThat(taskSubmittedCountDownLatch.getCount()).isNotEqualTo(0);
        }

        public void checkStartedNotCalled() {
            assertThat(taskStartedCountDownLatch.getCount()).isNotEqualTo(0);
        }
    }

    private abstract static class DummyBaseTask implements Runnable, BaseTask {
        @Override
        public String getType() {
            return TEST_TASK;
        }
    }

    private abstract static class DummyBaseCallableTask implements Callable<Void>, BaseTask {
        @Override
        public String getType() {
            return TEST_TASK;
        }
    }

    private DummyBaseExecutorObserver observer;

    @BeforeEach
    void setUp() {
        observer = new DummyBaseExecutorObserver();
        BaseExecutorFactory.addObserver(observer);
    }

    @AfterEach
    void tearDown() {
        BaseExecutorFactory.removeObserver(observer);
    }

    @Test
    void testObserveSubmitStartAndDone() {
        // given
        final BaseExecutorFactory baseExecutorFactory = BaseExecutorFactory.getInstance();
        final CountDownLatch countDownLatch = new CountDownLatch(1);

        // when
        final Future<Void> future = baseExecutorFactory.submit(() -> countDownLatch.countDown());

        // then
        assertThatNoException().isThrownBy(() -> countDownLatch.await(5, TimeUnit.SECONDS));
        assertThatNoException().isThrownBy(() -> future.get(1, TimeUnit.SECONDS));
        assertThatNoException().isThrownBy(() -> observer.awaitTaskSubmitted(5, TimeUnit.MILLISECONDS));
        assertThatNoException().isThrownBy(() -> observer.awaitTaskStarted(5, TimeUnit.MILLISECONDS));
        assertThatNoException().isThrownBy(() -> observer.awaitTaskDone(5, TimeUnit.MILLISECONDS));
        observer.checkFailedNotCalled();
        assertThat(observer.taskSubmittedDefinition.get()).isNotNull();
        assertThat(observer.taskSubmittedDefinition.get().id()).isNotNull();
        assertThat(observer.taskSubmittedDefinition.get().type()).isNotNull();
        assertThat(observer.taskStartedTaskDefinition.get()).isNotNull();
        assertThat(observer.taskStartedTaskDefinition.get().id()).isNotNull();
        assertThat(observer.taskStartedTaskDefinition.get().type()).isNotNull();
        assertThat(observer.taskDoneTaskDefinition.get()).isNotNull();
        assertThat(observer.taskDoneTaskDefinition.get().id()).isNotNull();
        assertThat(observer.taskDoneTaskDefinition.get().type()).isNotNull();
        assertThat(observer.taskDoneDuration.get()).isNotNull();
    }

    @Test
    void testObserveForFailedTask() {
        // given
        final BaseExecutorFactory baseExecutorFactory = BaseExecutorFactory.getInstance();

        // when
        final Future<Void> future = baseExecutorFactory.submit(() -> {
            throw new RuntimeException("test");
        });

        // then
        assertThatThrownBy(() -> future.get(1, TimeUnit.SECONDS)).isNotNull();
        assertThatNoException().isThrownBy(() -> observer.awaitTaskSubmitted(5, TimeUnit.MILLISECONDS));
        assertThatNoException().isThrownBy(() -> observer.awaitTaskStarted(5, TimeUnit.MILLISECONDS));
        assertThatNoException().isThrownBy(() -> observer.awaitTaskFailed(5, TimeUnit.MILLISECONDS));
        observer.checkDoneNotCalled();
        assertThat(observer.taskSubmittedDefinition.get()).isNotNull();
        assertThat(observer.taskSubmittedDefinition.get().id()).isNotNull();
        assertThat(observer.taskSubmittedDefinition.get().type()).isNotNull();
        assertThat(observer.taskStartedTaskDefinition.get()).isNotNull();
        assertThat(observer.taskStartedTaskDefinition.get().id()).isNotNull();
        assertThat(observer.taskStartedTaskDefinition.get().type()).isNotNull();
        assertThat(observer.taskFailedTaskDefinition.get()).isNotNull();
        assertThat(observer.taskFailedTaskDefinition.get().id()).isNotNull();
        assertThat(observer.taskFailedTaskDefinition.get().type()).isNotNull();
        assertThat(observer.taskFailedDuration.get()).isNotNull();
    }

    @Test
    void testAllEventsSendAtCorrectState() {
        // given
        final BaseExecutorFactory baseExecutorFactory = BaseExecutorFactory.getInstance();
        final Lock lock = new ReentrantLock();
        final Condition condition = lock.newCondition();
        final DummyBaseExecutorObserver observer2 = new DummyBaseExecutorObserver();

        // when first blocking task is submitted
        final Future<Void> future = baseExecutorFactory.submit(() -> {
            lock.lock();
            try {
                condition.await();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            } finally {
                lock.unlock();
            }
        });

        // then first task should not be finished
        assertThatNoException().isThrownBy(() -> observer.awaitTaskSubmitted(5, TimeUnit.MILLISECONDS));
        assertThatNoException().isThrownBy(() -> observer.awaitTaskStarted(5, TimeUnit.MILLISECONDS));
        observer.checkDoneNotCalled();
        observer.checkFailedNotCalled();

        // then add a second observer and submit a task to check that submit event is fired but no start event is fired
        try {
            observer2.checkSubmittedNotCalled();
            BaseExecutorFactory.addObserver(observer2);
            baseExecutorFactory.submit(() -> {});
            assertThatNoException().isThrownBy(() -> observer2.awaitTaskSubmitted(5, TimeUnit.MILLISECONDS));
            assertThat(observer.taskSubmittedDefinition.get()).isNotNull();
            assertThat(observer.taskSubmittedDefinition.get().id()).isNotNull();
            assertThat(observer.taskSubmittedDefinition.get().type()).isNotNull();
            observer2.checkStartedNotCalled();
        } catch (Exception e) {
            fail("Exception should not be thrown", e);
        } finally {
            BaseExecutorFactory.removeObserver(observer2);
        }

        // then finish the first task
        lock.lock();
        try {
            condition.signalAll();
        } finally {
            lock.unlock();
        }
        assertThatNoException().isThrownBy(() -> future.get(1, TimeUnit.SECONDS));
        assertThatNoException().isThrownBy(() -> observer.awaitTaskDone(5, TimeUnit.MILLISECONDS));
    }

    @Test
    void testBaseTaskDefinition() {
        // given
        final BaseExecutorFactory baseExecutorFactory = BaseExecutorFactory.getInstance();

        // when
        final Future<Void> future1 = baseExecutorFactory.submit(() -> {});

        // then
        assertThatNoException().isThrownBy(() -> future1.get(1, TimeUnit.SECONDS));
        assertThat(observer.taskSubmittedDefinition.get()).isNotNull();
        assertThat(observer.taskStartedTaskDefinition.get()).isNotNull();
        assertThat(observer.taskDoneTaskDefinition.get()).isNotNull();

        final UUID id = observer.taskSubmittedDefinition.get().id();
        assertThat(id).isNotNull();
        assertThat(id).isEqualTo(observer.taskStartedTaskDefinition.get().id());
        assertThat(id).isEqualTo(observer.taskDoneTaskDefinition.get().id());
        final String type = observer.taskSubmittedDefinition.get().type();
        assertThat(type).isNotNull();
        assertThat(type).isEqualTo(observer.taskStartedTaskDefinition.get().type());
        assertThat(type).isEqualTo(observer.taskDoneTaskDefinition.get().type());

        // let's check that a second task has a different id
        final Future<Void> future2 = baseExecutorFactory.submit(() -> {});
        assertThatNoException().isThrownBy(() -> future2.get(1, TimeUnit.SECONDS));
        assertThat(observer.taskSubmittedDefinition.get()).isNotNull();
        final UUID id2 = observer.taskSubmittedDefinition.get().id();
        assertThat(id).isNotEqualTo(id2);
    }

    @Test
    void testBaseTaskSupportForRunnable() {
        // given
        final BaseExecutorFactory baseExecutorFactory = BaseExecutorFactory.getInstance();
        final Runnable task = new DummyBaseTask() {
            @Override
            public void run() {}
        };

        // when
        final Future<Void> future1 = baseExecutorFactory.submit(task);

        // then
        assertThatNoException().isThrownBy(() -> future1.get(1, TimeUnit.SECONDS));
        assertThat(observer.taskSubmittedDefinition.get()).isNotNull();
        assertThat(observer.taskStartedTaskDefinition.get()).isNotNull();
        assertThat(observer.taskDoneTaskDefinition.get()).isNotNull();

        final UUID id = observer.taskSubmittedDefinition.get().id();
        assertThat(id).isNotNull();
        assertThat(id).isEqualTo(observer.taskStartedTaskDefinition.get().id());
        assertThat(id).isEqualTo(observer.taskDoneTaskDefinition.get().id());
        final String type = observer.taskSubmittedDefinition.get().type();
        assertThat(type).isNotNull();
        assertThat(type).isEqualTo(TEST_TASK);
        assertThat(type).isEqualTo(observer.taskStartedTaskDefinition.get().type());
        assertThat(type).isEqualTo(observer.taskDoneTaskDefinition.get().type());
    }

    @Test
    void testBaseTaskSupportForCallable() {
        // given
        final BaseExecutorFactory baseExecutorFactory = BaseExecutorFactory.getInstance();
        final Callable<Void> task = new DummyBaseCallableTask() {
            @Override
            public Void call() {
                return null;
            }
        };

        // when
        final Future<Void> future1 = baseExecutorFactory.submit(task);

        // then
        assertThatNoException().isThrownBy(() -> future1.get(1, TimeUnit.SECONDS));
        assertThat(observer.taskSubmittedDefinition.get()).isNotNull();
        assertThat(observer.taskStartedTaskDefinition.get()).isNotNull();
        assertThat(observer.taskDoneTaskDefinition.get()).isNotNull();

        final UUID id = observer.taskSubmittedDefinition.get().id();
        assertThat(id).isNotNull();
        assertThat(id).isEqualTo(observer.taskStartedTaskDefinition.get().id());
        assertThat(id).isEqualTo(observer.taskDoneTaskDefinition.get().id());
        final String type = observer.taskSubmittedDefinition.get().type();
        assertThat(type).isNotNull();
        assertThat(type).isEqualTo(TEST_TASK);
        assertThat(type).isEqualTo(observer.taskStartedTaskDefinition.get().type());
        assertThat(type).isEqualTo(observer.taskDoneTaskDefinition.get().type());
    }
}
