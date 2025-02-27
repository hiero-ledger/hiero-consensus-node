// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.schedulers.builders;

/**
 * Various types of task schedulers. Pass one of these types to {@link TaskSchedulerBuilder#withType(TaskSchedulerType)}
 * to create a task scheduler of the desired type. If unspecified, the default scheduler type is {@link #SEQUENTIAL}.
 */
public enum TaskSchedulerType {
    /**
     * Tasks are executed in a fork join pool one at a time in the order they were enqueued. There is a happens before
     * relationship between each task.
     */
    SEQUENTIAL,
    /**
     * Tasks are executed on a dedicated thread one at a time in the order they were enqueued. There is a happens before
     * relationship between each task. This scheduler type has very similar semantics as {@link #SEQUENTIAL}, although
     * the implementation and performance characteristics are not identical.
     */
    SEQUENTIAL_THREAD,
    /**
     * Tasks are executed on a fork join pool. Tasks may be executed in parallel with each other. Ordering is not
     * guaranteed.
     */
    CONCURRENT,
    /**
     * Tasks are executed immediately on the caller's thread. There is no queue for tasks waiting to be handled (logical
     * or otherwise). Useful for scenarios where tasks are extremely small and not worth the scheduling overhead.
     * <p>
     * Only a single logical thread of execution is permitted to send data to a direct task scheduler.
     * {@link #SEQUENTIAL} and {@link #SEQUENTIAL_THREAD} schedulers are permitted to send data to a direct task
     * scheduler, but it is illegal for more than one of these schedulers to send data to the same direct task
     * scheduler. {@link #CONCURRENT} task schedulers are forbidden from sending data to a direct task scheduler. It is
     * legal for operations that are executed on the calling thread (e.g. filters, transformers, stateless/stateful
     * direct schedulers) to call into a direct scheduler as long as the calling thread is not in a concurrent scheduler
     * or originating from more than one sequential scheduler.
     * <p>
     * To decide if a direct scheduler is wired in a legal way, the following algorithm is used:
     * <ul>
     * <li>Create a directed graph where vertices are schedulers and edges are wires between schedulers</li>
     * <li>Starting from each vertex, walk over the graph in depth first order. Follow edges that lead to
     * DIRECT or DIRECT_THREADSAFE vertices, but do not follow edges that lead into SEQUENTIAL, SEQUENTIAL_THREAD,
     * or CONCURRENT vertices.</li>
     * <li>If a DIRECT vertex is reachable starting from a CONCURRENT vertex, the wiring is illegal.</li>
     * <li>For each vertex with type DIRECT, count the number of unique SEQUENTIAL or SEQUENTIAL_THREAD vertexes that
     * it can be reached by. If that number exceeds 1, then the wiring is illegal.</li>
     * </ul>
     *
     * <p>
     * These constraints are enforced by the framework.
     */
    DIRECT,
    /**
     * Similar to {@link #DIRECT} except that work performed by this scheduler is required to be threadsafe. This means
     * that it is safe to concurrently execute multiple instances of the same task, freeing it from the restrictions of
     * {@link #DIRECT}.
     *
     * <p>
     * There is no enforcement mechanism in the framework to ensure that the task is actually threadsafe. It is advised
     * that this scheduler type be used with caution, as improper use can lead to can lead to nasty race conditions.
     */
    DIRECT_THREADSAFE,
    /**
     * A scheduler that does nothing. All wires into and out of this scheduler are effectively non-existent at runtime.
     * Useful for testing and debugging, or for when the ability to toggle a scheduler on/off via configuration is
     * desired. For a deeper dive into why this is a useful concept, see
     * <a href='https://www.youtube.com/watch?v=6h58uT_BGV4'>this explanation</a>.
     */
    NO_OP
}
