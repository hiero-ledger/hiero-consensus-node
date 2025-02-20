// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.wires.input;

import com.swirlds.component.framework.schedulers.TaskScheduler;
import com.swirlds.component.framework.schedulers.builders.TaskSchedulerType;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * An object that can insert work to be handled by a {@link TaskScheduler}.
 *
 * @param <IN> the type of data that passes into the wire
 */
public abstract class InputWire<IN> {

    private final TaskSchedulerInput<?> taskSchedulerInput;
    private Consumer<Object> handler;
    private final String name;
    private final String taskSchedulerName;
    private final TaskSchedulerType taskSchedulerType;

    /**
     * Constructor.
     *
     * @param taskScheduler the scheduler to insert data into
     * @param name          the name of the input wire
     */
    protected InputWire(@NonNull final TaskScheduler<?> taskScheduler, @NonNull final String name) {
        this.taskSchedulerInput = Objects.requireNonNull(taskScheduler);
        this.name = Objects.requireNonNull(name);
        this.taskSchedulerName = taskScheduler.getName();
        this.taskSchedulerType = taskScheduler.getType();
    }

    /**
     * Get the name of this input wire.
     *
     * @return the name of this input wire
     */
    @NonNull
    public String getName() {
        return name;
    }

    /**
     * Get the name of the task scheduler this input channel is bound to.
     *
     * @return the name of the wire this input channel is bound to
     */
    @NonNull
    public String getTaskSchedulerName() {
        return taskSchedulerName;
    }

    /**
     * Get the type of the task scheduler that this input channel is bound to.
     *
     * @return the type of the task scheduler that this input channel is bound to
     */
    @NonNull
    public TaskSchedulerType getTaskSchedulerType() {
        return taskSchedulerType;
    }

    /**
     * Add a task to the task scheduler. May block if back pressure is enabled.
     *
     * @param data the data to be processed by the task scheduler
     */
    public void put(@NonNull final IN data) {
        taskSchedulerInput.put(handler, data);
    }

    /**
     * Add a task to the task scheduler. If backpressure is enabled and there is not immediately capacity available,
     * this method will not accept the data.
     *
     * @param data the data to be processed by the task scheduler
     * @return true if the data was accepted, false otherwise
     */
    public boolean offer(@NonNull final IN data) {
        return taskSchedulerInput.offer(handler, data);
    }

    /**
     * Inject data into the task scheduler, doing so even if it causes the number of unprocessed tasks to exceed the
     * capacity specified by configured back pressure. If backpressure is disabled, this operation is logically
     * equivalent to {@link #put(Object)}.
     *
     * @param data the data to be processed by the task scheduler
     */
    public void inject(@NonNull final IN data) {
        taskSchedulerInput.inject(handler, data);
    }

    /**
     * Set the method that will handle data traveling over this wire.
     *
     * @param handler the method that will handle data traveling over this wire
     */
    protected void setHandler(@NonNull final Consumer<Object> handler) {
        if (this.handler != null) {
            throw new IllegalStateException("Handler already bound");
        }
        this.handler = Objects.requireNonNull(handler);
    }
}
