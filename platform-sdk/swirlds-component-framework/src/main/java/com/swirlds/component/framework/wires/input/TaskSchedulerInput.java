// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.wires.input;

import com.swirlds.component.framework.schedulers.TaskScheduler;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.Consumer;

/**
 * An object that knows how to add data to a {@link TaskScheduler} for processing, and how to forward data to a task
 * scheduler's output. This class is defined inside the input wire package to prevent anything that isn't an input wire
 * from accessing its methods.
 */
public abstract class TaskSchedulerInput<OUT> {

    /**
     * Add a task to the scheduler. May block if back pressure is enabled.
     *
     * @param handler handles the provided data
     * @param data    the data to be processed by the task scheduler
     */
    protected abstract void put(@NonNull Consumer<Object> handler, @NonNull Object data);

    /**
     * Add a task to the scheduler. If backpressure is enabled and there is not immediately capacity available, this
     * method will not accept the data.
     *
     * @param handler handles the provided data
     * @param data    the data to be processed by the scheduler
     * @return true if the data was accepted, false otherwise
     */
    protected abstract boolean offer(@NonNull Consumer<Object> handler, @NonNull Object data);

    /**
     * Inject data into the scheduler, doing so even if it causes the number of unprocessed tasks to exceed the capacity
     * specified by configured back pressure. If backpressure is disabled, this operation is logically equivalent to
     * {@link #put(Consumer, Object)}.
     *
     * @param handler handles the provided data
     * @param data    the data to be processed by the scheduler
     */
    protected abstract void inject(@NonNull Consumer<Object> handler, @NonNull Object data);

    /**
     * Pass data to this scheduler's primary output wire.
     * <p>
     * This method is implemented here to allow classes in this package to call forward(), which otherwise would not be
     * visible.
     */
    protected abstract void forward(@NonNull final OUT data);
}
