// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.wires.input;

import com.swirlds.component.framework.schedulers.TaskScheduler;
import com.swirlds.component.framework.schedulers.builders.TaskSchedulerType;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * An object that can insert work to be handled by a {@link TaskScheduler}.
 *
 * @param <IN> the type of data that passes into the wire
 */
public interface InputWire<IN> {

    /**
     * Get the name of this input wire.
     *
     * @return the name of this input wire
     */
    @NonNull
    String getName();

    /**
     * Get the name of the task scheduler this input channel is bound to.
     *
     * @return the name of the wire this input channel is bound to
     */
    @NonNull
    String getTaskSchedulerName();

    /**
     * Get the type of the task scheduler that this input channel is bound to.
     *
     * @return the type of the task scheduler that this input channel is bound to
     */
    @NonNull
    TaskSchedulerType getTaskSchedulerType();

    /**
     * Add a task to the task scheduler. May block if back pressure is enabled.
     *
     * @param data the data to be processed by the task scheduler
     */
    void put(@NonNull final IN data);

    /**
     * Add a task to the task scheduler. If backpressure is enabled and there is not immediately capacity available,
     * this method will not accept the data.
     *
     * @param data the data to be processed by the task scheduler
     * @return true if the data was accepted, false otherwise
     */
    boolean offer(@NonNull final IN data);

    /**
     * Inject data into the task scheduler, doing so even if it causes the number of unprocessed tasks to exceed the
     * capacity specified by configured back pressure. If backpressure is disabled, this operation is logically
     * equivalent to {@link #put(Object)}.
     *
     * @param data the data to be processed by the task scheduler
     */
    void inject(@NonNull final IN data);
}
