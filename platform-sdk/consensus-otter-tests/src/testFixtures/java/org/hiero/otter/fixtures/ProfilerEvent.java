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
     * profiling mode for identifying performance hotspots. Uses sampling-based profiling with
     * configurable period (default 10ms = 100Hz).
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
     * Captures sampled object allocation events to identify memory hotspots and reduce GC pressure.
     * Uses sampling with throttling for low overhead (default 150 samples/second).
     * <p>
     * Includes:
     * <ul>
     *   <li>jdk.ObjectAllocationSample - Sampled allocation events (low overhead)</li>
     * </ul>
     */
    ALLOCATION(List.of("jdk.ObjectAllocationSample")),

    /**
     * Lock contention and synchronization profiling.
     * <p>
     * Captures lock acquisition, waiting, and parking events to identify concurrency bottlenecks.
     * Only records operations exceeding threshold (default 10ms).
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
     * Only records operations exceeding threshold (default 10ms).
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
     * Exception throw profiling.
     * <p>
     * Captures exception throw events to understand exception patterns and their performance impact.
     * Records all exceptions with stack traces. Can have high overhead if exceptions are frequent.
     * <p>
     * Includes:
     * <ul>
     *   <li>jdk.JavaExceptionThrow - Exception throw events</li>
     * </ul>
     */
    EXCEPTION(List.of("jdk.JavaExceptionThrow")),

    /**
     * Garbage collection profiling.
     * <p>
     * Captures GC pauses, heap statistics, and memory pool information to understand garbage
     * collection impact on performance.
     * <p>
     * Includes:
     * <ul>
     *   <li>jdk.GarbageCollection - GC events and pauses</li>
     *   <li>jdk.GCHeapSummary - Heap statistics before/after GC</li>
     * </ul>
     */
    GC(List.of("jdk.GarbageCollection", "jdk.GCHeapSummary")),

    /**
     * Thread lifecycle and activity profiling.
     * <p>
     * Captures thread creation, termination, and sleep events to understand thread behavior
     * and identify threading issues.
     * <p>
     * Includes:
     * <ul>
     *   <li>jdk.ThreadStart - Thread creation</li>
     *   <li>jdk.ThreadEnd - Thread termination</li>
     *   <li>jdk.ThreadSleep - Thread.sleep() calls</li>
     * </ul>
     */
    THREAD(List.of("jdk.ThreadStart", "jdk.ThreadEnd", "jdk.ThreadSleep")),

    /**
     * JIT compiler profiling.
     * <p>
     * Captures JIT compilation events to understand compilation patterns and identify
     * compilation bottlenecks.
     * <p>
     * Includes:
     * <ul>
     *   <li>jdk.Compilation - JIT compilation events</li>
     *   <li>jdk.CompilerPhase - Detailed compilation phases</li>
     * </ul>
     */
    COMPILER(List.of("jdk.Compilation", "jdk.CompilerPhase")),

    /**
     * Class loading profiling.
     * <p>
     * Captures class loading events to understand class loading patterns and startup performance.
     * <p>
     * Includes:
     * <ul>
     *   <li>jdk.ClassLoad - Class loading events</li>
     *   <li>jdk.ClassDefine - Class definition events</li>
     * </ul>
     */
    CLASS_LOADING(List.of("jdk.ClassLoad", "jdk.ClassDefine")),

    /**
     * SafePoint profiling.
     * <p>
     * Captures safepoint events to understand JVM pause behavior. Safepoints are when all threads
     * stop for VM operations (GC, deoptimization, bias revocation, etc.). Critical for understanding
     * application pauses.
     * <p>
     * Includes:
     * <ul>
     *   <li>jdk.SafepointBegin - Safepoint start</li>
     *   <li>jdk.SafepointEnd - Safepoint completion</li>
     *   <li>jdk.SafepointStateSynchronization - Time getting threads to safepoint</li>
     *   <li>jdk.ExecuteVMOperation - What operation executed at safepoint</li>
     * </ul>
     */
    SAFEPOINT(List.of(
            "jdk.SafepointBegin", "jdk.SafepointEnd", "jdk.SafepointStateSynchronization", "jdk.ExecuteVMOperation")),

    /**
     * Detailed GC profiling.
     * <p>
     * Captures detailed garbage collection phase information beyond basic GC events.
     * Useful for deep GC analysis and tuning.
     * <p>
     * Includes:
     * <ul>
     *   <li>jdk.GCPhasePause - Individual GC pause phases</li>
     *   <li>jdk.GCPhaseParallel - Parallel GC work phases</li>
     *   <li>jdk.GCHeapConfiguration - Heap configuration</li>
     *   <li>jdk.GCConfiguration - GC algorithm configuration</li>
     *   <li>jdk.YoungGarbageCollection - Young generation collections</li>
     *   <li>jdk.OldGarbageCollection - Old generation collections</li>
     * </ul>
     */
    GC_DETAILED(List.of(
            "jdk.GCPhasePause",
            "jdk.GCPhaseParallel",
            "jdk.GCHeapConfiguration",
            "jdk.GCConfiguration",
            "jdk.YoungGarbageCollection",
            "jdk.OldGarbageCollection")),

    /**
     * Detailed memory profiling.
     * <p>
     * Captures detailed memory allocation and metaspace events beyond sampled allocations.
     * Can have high overhead - use for targeted analysis.
     * <p>
     * Includes:
     * <ul>
     *   <li>jdk.ObjectAllocationInNewTLAB - Normal TLAB allocations (all, not sampled)</li>
     *   <li>jdk.ObjectAllocationOutsideTLAB - Large object allocations outside TLAB</li>
     *   <li>jdk.MetaspaceGCThreshold - Metaspace GC triggers</li>
     *   <li>jdk.MetaspaceAllocationFailure - Metaspace allocation failures</li>
     *   <li>jdk.MetaspaceOOM - Metaspace out of memory</li>
     *   <li>jdk.PhysicalMemory - Physical memory information</li>
     * </ul>
     */
    MEMORY_DETAILED(List.of(
            "jdk.ObjectAllocationInNewTLAB", "jdk.ObjectAllocationOutsideTLAB",
            "jdk.MetaspaceGCThreshold", "jdk.MetaspaceAllocationFailure",
            "jdk.MetaspaceOOM", "jdk.PhysicalMemory")),

    /**
     * Detailed JIT compiler profiling.
     * <p>
     * Captures JIT compilation decisions, failures, and deoptimizations. Useful for understanding
     * why code isn't being optimized or why performance degrades over time.
     * <p>
     * Includes:
     * <ul>
     *   <li>jdk.CompilerInlining - Method inlining decisions</li>
     *   <li>jdk.CompilerFailure - Compilation failures</li>
     *   <li>jdk.Deoptimization - When compiled code is thrown away</li>
     *   <li>jdk.CodeCacheFull - Code cache exhaustion events</li>
     *   <li>jdk.CodeCacheStatistics - Code cache metrics</li>
     *   <li>jdk.CodeSweeperStatistics - Code cache cleanup stats</li>
     *   <li>jdk.SweepCodeCache - Code cache sweeping events</li>
     * </ul>
     */
    COMPILER_DETAILED(List.of(
            "jdk.CompilerInlining",
            "jdk.CompilerFailure",
            "jdk.Deoptimization",
            "jdk.CodeCacheFull",
            "jdk.CodeCacheStatistics",
            "jdk.CodeSweeperStatistics",
            "jdk.SweepCodeCache")),

    /**
     * Security and TLS profiling.
     * <p>
     * Captures security-related events including TLS handshakes and certificate validation.
     * Useful for security audits and diagnosing TLS performance issues.
     * <p>
     * Includes:
     * <ul>
     *   <li>jdk.SecurityProperty - Security property modifications</li>
     *   <li>jdk.TLSHandshake - TLS handshake events</li>
     *   <li>jdk.X509Certificate - Certificate events</li>
     *   <li>jdk.X509Validation - Certificate validation events</li>
     * </ul>
     */
    SECURITY(List.of("jdk.SecurityProperty", "jdk.TLSHandshake", "jdk.X509Certificate", "jdk.X509Validation")),

    /**
     * Biased locking profiling.
     * <p>
     * Captures biased lock revocation events. Only relevant if biased locking is enabled
     * (deprecated in Java 15+, disabled by default in Java 18+).
     * <p>
     * Includes:
     * <ul>
     *   <li>jdk.BiasedLockRevocation - Single object bias revocations</li>
     *   <li>jdk.BiasedLockClassRevocation - Class-wide bias revocations</li>
     *   <li>jdk.JavaMonitorInflate - When thin locks become heavyweight locks</li>
     * </ul>
     */
    BIASED_LOCKING(List.of("jdk.BiasedLockRevocation", "jdk.BiasedLockClassRevocation", "jdk.JavaMonitorInflate"));

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
