// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.schedulers.builders;

/**
 * Various configuration options for a task scheduler. Note that the task scheduler type uses values from
 * {@link TaskSchedulerType}, and that the unhandled task capacity is represented as an integer value.
 */
public enum TaskSchedulerConfigOption {
    /**
     * If present, a metric will be created to show the number of unhandled tasks.
     */
    UNHANDLED_TASK_METRIC,
    /**
     * If present, a metric will be created to show the fraction of time the scheduler is busy.
     */
    BUSY_FRACTION_METRIC,
    /**
     * If present, the scheduler will be capable of being flushed.
     */
    FLUSHABLE,
    /**
     * If present, the scheduler will be capable of squelching.
     */
    SQUELCHABLE;

    /**
     * This is not defined as an enum constant because it is used in a special way. To specify the capacity,
     * use a string in the form "CAPACITY(1234)" where 1234 is the desired capacity.
     */
    public static final String CAPACITY = "CAPACITY";
}
