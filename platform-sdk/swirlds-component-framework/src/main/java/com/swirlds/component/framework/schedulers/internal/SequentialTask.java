/*
 * Copyright (C) 2023-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.swirlds.component.framework.schedulers.internal;

import com.swirlds.common.concurrent.AbstractTask;
import com.swirlds.common.metrics.extensions.FractionalTimer;
import com.swirlds.component.framework.counters.ObjectCounter;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.lang.Thread.UncaughtExceptionHandler;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Consumer;

/**
 * A task in a {@link SequentialTaskScheduler}.
 */
class SequentialTask extends AbstractTask {
    private Consumer<Object> handler;
    private Object data;
    private SequentialTask nextTask;
    private final ObjectCounter offRamp;
    private final FractionalTimer busyTimer;
    private final UncaughtExceptionHandler uncaughtExceptionHandler;

    /**
     * Constructor.
     *
     * @param pool                     the fork join pool that will execute tasks on this task scheduler
     * @param offRamp                  an object counter that is decremented when data is removed from the task
     *                                 scheduler
     * @param busyTimer                a timer that tracks the amount of time the task scheduler is busy
     * @param uncaughtExceptionHandler the uncaught exception handler
     * @param firstTask                true if this is the first task in the scheduler, false otherwise
     */
    SequentialTask(
            @NonNull final ForkJoinPool pool,
            @NonNull final ObjectCounter offRamp,
            @NonNull final FractionalTimer busyTimer,
            @NonNull final UncaughtExceptionHandler uncaughtExceptionHandler,
            final boolean firstTask) {
        super(pool, firstTask ? 1 : 2);
        this.offRamp = offRamp;
        this.busyTimer = busyTimer;
        this.uncaughtExceptionHandler = uncaughtExceptionHandler;
    }

    /**
     * Provide a reference to the next task and the data that will be processed during the handling of this task.
     *
     * @param nextTask the task that will execute after this task
     * @param handler  the method that will be called when this task is executed
     * @param data     the data to be passed to the consumer for this task
     */
    void send(
            @NonNull final SequentialTask nextTask,
            @NonNull final Consumer<Object> handler,
            @Nullable final Object data) {
        this.nextTask = nextTask;
        this.handler = handler;
        this.data = data;

        // This method provides us with the data we intend to send to the consumer
        // when this task is executed, thus resolving one of the two dependencies
        // required for the task to be executed. This call will decrement the
        // dependency count. If this causes the dependency count to reach 0
        // (i.e. if the previous task has already been executed), then this call
        // will cause this task to be immediately eligible for execution.
        send();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean onExecute() {
        busyTimer.activate();
        try {
            handler.accept(data);
            return true;
        } finally {
            offRamp.offRamp();
            busyTimer.deactivate();

            // Reduce the dependency count of the next task. If the next task already has its data, then this
            // method will cause the next task to be immediately eligible for execution.
            nextTask.send();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void onException(final Throwable t) {
        uncaughtExceptionHandler.uncaughtException(Thread.currentThread(), t);
    }
}
