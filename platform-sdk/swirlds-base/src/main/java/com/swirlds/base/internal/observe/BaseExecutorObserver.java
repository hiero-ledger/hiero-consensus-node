// SPDX-License-Identifier: Apache-2.0
package com.swirlds.base.internal.observe;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;

/**
 * An observer for the base executor (see {@link com.swirlds.base.internal.BaseExecutorFactory}). The observer is
 * notified when a task is submitted, started, done, or failed.
 */
public interface BaseExecutorObserver {

    /**
     * Called when a task is submitted.
     *
     * @param taskDefinition the task definition
     */
    void onTaskSubmitted(@NonNull BaseTaskDefinition taskDefinition);

    /**
     * Called when a task is started.
     * @param taskDefinition the task definition
     */
    void onTaskStarted(@NonNull BaseTaskDefinition taskDefinition);

    /**
     * Called when a task is done.
     * @param taskDefinition the task definition
     * @param duration the duration of the task
     */
    void onTaskDone(@NonNull BaseTaskDefinition taskDefinition, @NonNull Duration duration);

    /**
     * Called when a task is failed.
     * @param taskDefinition the task definition
     * @param duration the duration of the task
     */
    void onTaskFailed(@NonNull BaseTaskDefinition taskDefinition, @NonNull Duration duration);
}
