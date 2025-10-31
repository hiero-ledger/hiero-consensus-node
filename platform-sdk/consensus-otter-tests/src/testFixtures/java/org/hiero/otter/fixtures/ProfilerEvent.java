// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures;

import java.util.List;

/**
 * Profiling event types supported by Java Flight Recorder (JFR).
 * <p>
 * Each enum value represents a logical grouping of related JFR events that can be enabled
 * during profiling. Multiple events can be combined to create comprehensive profiling sessions.
 *
 * @see <a href="https://docs.oracle.com/en/java/javase/21/jfapi/creating-and-recording-your-first-event.html">JFR Documentation</a>
 */
public enum ProfilerEvent {
    /**
     * CPU execution profiling through method sampling.
     * <p>
     * Captures which methods (Java and native) are executing on CPU. This is the most common
     * profiling mode for identifying performance hotspots.
     * <p>
     * Includes:
     * <ul>
     *   <li>jdk.ExecutionSample - Java method sampling</li>
     *   <li>jdk.NativeMethodSample - Native/JNI method sampling</li>
     * </ul>
     */
    CPU(List.of("jdk.ExecutionSample", "jdk.NativeMethodSample")),

    /**
     * Memory allocation profiling.
     * <p>
     * Captures object allocation events to identify memory hotspots and reduce GC pressure.
     * <p>
     * Includes:
     * <ul>
     *   <li>jdk.ObjectAllocationInNewTLAB - Normal allocations in Thread Local Area Buffer</li>
     *   <li>jdk.ObjectAllocationOutsideTLAB - Large allocations outside TLAB</li>
     * </ul>
     */
    ALLOCATION(List.of("jdk.ObjectAllocationInNewTLAB", "jdk.ObjectAllocationOutsideTLAB")),

    /**
     * Lock contention and synchronization profiling.
     * <p>
     * Captures lock acquisition, waiting, and parking events to identify concurrency bottlenecks.
     * <p>
     * Includes:
     * <ul>
     *   <li>jdk.JavaMonitorEnter - Lock acquisition attempts and contention</li>
     *   <li>jdk.JavaMonitorWait - Object.wait() calls</li>
     *   <li>jdk.ThreadPark - LockSupport.park() calls (used by java.util.concurrent)</li>
     * </ul>
     */
    LOCK(List.of("jdk.JavaMonitorEnter", "jdk.JavaMonitorWait", "jdk.ThreadPark")),

    /**
     * I/O operation profiling.
     * <p>
     * Captures file and network I/O operations to identify I/O bottlenecks.
     * <p>
     * Includes:
     * <ul>
     *   <li>jdk.FileRead - File read operations</li>
     *   <li>jdk.FileWrite - File write operations</li>
     *   <li>jdk.SocketRead - Network socket reads</li>
     *   <li>jdk.SocketWrite - Network socket writes</li>
     * </ul>
     */
    IO(List.of("jdk.FileRead", "jdk.FileWrite", "jdk.SocketRead", "jdk.SocketWrite")),

    /**
     * Garbage collection profiling.
     * <p>
     * Captures GC pauses and statistics to understand garbage collection impact on performance.
     * <p>
     * Includes:
     * <ul>
     *   <li>jdk.GarbageCollection - GC events and pauses</li>
     * </ul>
     */
    GC(List.of("jdk.GarbageCollection")),

    /**
     * Exception throw profiling.
     * <p>
     * Captures exception throw events to understand exception patterns and their performance impact.
     * <p>
     * Includes:
     * <ul>
     *   <li>jdk.JavaExceptionThrow - Exception throw events</li>
     * </ul>
     */
    EXCEPTION(List.of("jdk.JavaExceptionThrow"));

    private final List<String> jfrEventNames;

    ProfilerEvent(final List<String> jfrEventNames) {
        this.jfrEventNames = jfrEventNames;
    }

    /**
     * Gets the JFR event names used in configuration files.
     *
     * @return an immutable list of JFR event names (e.g., ["jdk.ExecutionSample", "jdk.NativeMethodSample"])
     */
    public List<String> getJfrEventNames() {
        return jfrEventNames;
    }
}