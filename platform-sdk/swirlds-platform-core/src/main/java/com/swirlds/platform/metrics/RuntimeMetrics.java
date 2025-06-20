// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.metrics;

import static com.swirlds.metrics.api.FloatFormats.FORMAT_16_0;
import static com.swirlds.metrics.api.FloatFormats.FORMAT_16_2;
import static com.swirlds.metrics.api.FloatFormats.FORMAT_1_4;
import static com.swirlds.metrics.api.FloatFormats.FORMAT_6_0;
import static com.swirlds.metrics.api.FloatFormats.FORMAT_8_0;
import static com.swirlds.metrics.api.Metrics.INFO_CATEGORY;
import static com.swirlds.metrics.api.Metrics.INTERNAL_CATEGORY;
import static com.swirlds.metrics.api.Metrics.PLATFORM_CATEGORY;

import com.sun.management.HotSpotDiagnosticMXBean;
import com.sun.management.OperatingSystemMXBean;
import com.swirlds.base.units.UnitConstants;
import com.swirlds.common.metrics.FunctionGauge;
import com.swirlds.common.metrics.RunningAverageMetric;
import com.swirlds.common.utility.RuntimeObjectRegistry;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.state.signed.SignedState;
import java.io.File;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Collection of metrics related to runtime statistics
 */
public final class RuntimeMetrics {

    private static final double WHOLE_PERCENT = 100.0; // all of something is to be reported as 100.0%
    private static final File ROOT_DIRECTORY = new File("/");

    public static final ZoneId UTC = ZoneId.of("UTC");
    public static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z");
    private static final FunctionGauge.Config<String> TIMESTAMP_CONFIG = new FunctionGauge.Config<>(
                    INFO_CATEGORY,
                    "time",
                    String.class,
                    () -> DATE_TIME_FORMATTER.format(Instant.now().atZone(UTC)))
            .withDescription("the current time")
            .withFormat("%25s");

    private static final RunningAverageMetric.Config MEM_FREE_CONFIG = new RunningAverageMetric.Config(
                    PLATFORM_CATEGORY, "memFree")
            .withDescription("bytes of free memory (which can increase after a garbage collection)")
            .withFormat(FORMAT_16_0)
            .withHalfLife(0.0);
    private final RunningAverageMetric memFree;

    private static final RunningAverageMetric.Config MEM_TOT_CONFIG = new RunningAverageMetric.Config(
                    PLATFORM_CATEGORY, "memTot")
            .withDescription("total bytes in the Java Virtual Machine")
            .withFormat(FORMAT_16_0)
            .withHalfLife(0.0);
    private final RunningAverageMetric memTot;

    private static final RunningAverageMetric.Config MEM_MAX_CONFIG = new RunningAverageMetric.Config(
                    PLATFORM_CATEGORY, "memMax")
            .withDescription("maximum bytes that the JVM might use")
            .withFormat(FORMAT_16_0)
            .withHalfLife(0.0);
    private final RunningAverageMetric memMax;

    private static final RunningAverageMetric.Config DIRECT_MEM_IN_MB_CONFIG = new RunningAverageMetric.Config(
                    PLATFORM_CATEGORY, "directMemInMB")
            .withDescription("megabytes of off-heap (direct) memory being used by the JVM")
            .withFormat(FORMAT_16_2)
            .withHalfLife(0.0);
    private final RunningAverageMetric directMemInMB;

    private static final RunningAverageMetric.Config DIRECT_MEM_PERCENT_CONFIG = new RunningAverageMetric.Config(
                    PLATFORM_CATEGORY, "directMemPercent")
            .withDescription("off-heap (direct) memory used, as a percent of MaxDirectMemorySize")
            .withFormat(FORMAT_16_2)
            .withHalfLife(0.0);
    private final RunningAverageMetric directMemPercent;

    private static final RunningAverageMetric.Config AVG_NUM_PROC_CONFIG = new RunningAverageMetric.Config(
                    PLATFORM_CATEGORY, "proc")
            .withDescription("number of processors (cores) available to the JVM")
            .withFormat(FORMAT_8_0);
    private final RunningAverageMetric avgNumProc;

    private static final RunningAverageMetric.Config CPU_LOAD_SYS_CONFIG = new RunningAverageMetric.Config(
                    PLATFORM_CATEGORY, "cpuLoadSys")
            .withDescription("the CPU load of the whole system")
            .withFormat(FORMAT_1_4);
    private final RunningAverageMetric cpuLoadSys;

    private static final RunningAverageMetric.Config THREADS_CONFIG = new RunningAverageMetric.Config(
                    PLATFORM_CATEGORY, "threads")
            .withDescription("the current number of live threads")
            .withFormat(FORMAT_6_0);
    private final RunningAverageMetric threads;

    private static final FunctionGauge.Config<Long> DISKSPACE_FREE_CONFIG = new FunctionGauge.Config<>(
                    INTERNAL_CATEGORY, "DiskspaceFree", Long.class, ROOT_DIRECTORY::getFreeSpace)
            .withDescription("disk space being used right now")
            .withFormat("%d");
    private static final FunctionGauge.Config<Long> DISKSPACE_WHOLE_CONFIG = new FunctionGauge.Config<>(
                    INTERNAL_CATEGORY, "DiskspaceWhole", Long.class, ROOT_DIRECTORY::getTotalSpace)
            .withDescription("total disk space available on node")
            .withFormat("%d");
    private static final FunctionGauge.Config<Long> DISKSPACE_USED_CONFIG = new FunctionGauge.Config<>(
                    INTERNAL_CATEGORY,
                    "DiskspaceUsed",
                    Long.class,
                    () -> ROOT_DIRECTORY.getTotalSpace() - ROOT_DIRECTORY.getFreeSpace())
            .withDescription("disk space free for use by the node")
            .withFormat("%d");

    private final OperatingSystemMXBean osBean;
    private final ThreadMXBean thbean;
    private final BufferPoolMXBean directMemMXBean;
    private final double maximumDirectMemSizeInMB;

    private static final AtomicBoolean SETUP_STARTED = new AtomicBoolean();

    /**
     * Setup all metrics related to the runtime
     *
     * @param metrics
     * 		a reference to the metrics-system
     */
    public static void setup(final Metrics metrics) {
        if (SETUP_STARTED.compareAndSet(false, true)) {
            final RuntimeMetrics runtimeMetrics = new RuntimeMetrics(metrics);
            metrics.addUpdater(runtimeMetrics::update);
        }
    }

    /**
     * @throws NullPointerException in case {@code metrics} parameter is {@code null}
     */
    private RuntimeMetrics(final Metrics metrics) {
        Objects.requireNonNull(metrics, "metrics must not be null");
        this.osBean = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        this.thbean = ManagementFactory.getThreadMXBean();

        this.directMemMXBean = getDirectMemMXBean();
        this.maximumDirectMemSizeInMB = getMaximumDirectMemSizeInMB();

        metrics.getOrCreate(TIMESTAMP_CONFIG);
        memFree = metrics.getOrCreate(MEM_FREE_CONFIG);
        memTot = metrics.getOrCreate(MEM_TOT_CONFIG);
        memMax = metrics.getOrCreate(MEM_MAX_CONFIG);
        directMemInMB = metrics.getOrCreate(DIRECT_MEM_IN_MB_CONFIG);
        directMemPercent = metrics.getOrCreate(DIRECT_MEM_PERCENT_CONFIG);
        avgNumProc = metrics.getOrCreate(AVG_NUM_PROC_CONFIG);
        cpuLoadSys = metrics.getOrCreate(CPU_LOAD_SYS_CONFIG);
        threads = metrics.getOrCreate(THREADS_CONFIG);
        metrics.getOrCreate(DISKSPACE_FREE_CONFIG);
        metrics.getOrCreate(DISKSPACE_WHOLE_CONFIG);
        metrics.getOrCreate(DISKSPACE_USED_CONFIG);

        // Ensure that the runtime object registry is tracking signed states when we create metrics.
        // When this code was added, the first SignedState is created AFTER this point in time.
        // In the future when state loading happens outside the platform constructor, this will
        // no longer be necessary.
        RuntimeObjectRegistry.createRecord(SignedState.class).release();

        for (final Class<?> cls : RuntimeObjectRegistry.getTrackedClasses()) {
            final String className = cls.getSimpleName();
            metrics.getOrCreate(new FunctionGauge.Config<>(
                            INTERNAL_CATEGORY,
                            "countInMemory" + className,
                            Integer.class,
                            () -> RuntimeObjectRegistry.getActiveObjectsCount(cls))
                    .withDescription("the number of " + className + " objects in memory")
                    .withFormat("%d"));
            metrics.getOrCreate(new FunctionGauge.Config<>(
                            INTERNAL_CATEGORY,
                            "oldest" + className + "Seconds",
                            Long.class,
                            () -> RuntimeObjectRegistry.getOldestActiveObjectAge(cls, Instant.now())
                                    .toSeconds())
                    .withDescription("the age of the oldest " + className + " object in memory")
                    .withFormat("%d"));
        }
    }

    private static BufferPoolMXBean getDirectMemMXBean() {
        // scan through PlatformMXBeans to find the one responsible for direct memory used
        final List<BufferPoolMXBean> pools = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class);
        for (final BufferPoolMXBean pool : pools) {
            if (pool.getName().equals("direct")) {
                return pool;
            }
        }
        return null;
    }

    private static double getMaximumDirectMemSizeInMB() {
        final HotSpotDiagnosticMXBean hsdiag = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
        long maxDirectMemoryInBytes = Runtime.getRuntime().maxMemory();
        if (hsdiag != null) {
            try {
                final long value =
                        Long.parseLong(hsdiag.getVMOption("MaxDirectMemorySize").getValue());
                if (value > 0) {
                    maxDirectMemoryInBytes = value;
                }
            } catch (final NumberFormatException ex) {
                // just use the present value, namely Runtime.getRuntime().maxMemory().
            }
        }
        return maxDirectMemoryInBytes * UnitConstants.BYTES_TO_MEBIBYTES;
    }

    private void update() {
        memFree.update(Runtime.getRuntime().freeMemory());
        memTot.update(Runtime.getRuntime().totalMemory());
        memMax.update(Runtime.getRuntime().maxMemory());
        avgNumProc.update(Runtime.getRuntime().availableProcessors());
        cpuLoadSys.update(osBean.getCpuLoad());
        threads.update(thbean.getThreadCount());

        if (directMemMXBean == null) {
            return;
        }

        final long bytesUsed = directMemMXBean.getMemoryUsed();
        // recording the value of -1 as (-1) / (1024 * 1024) makes it too close to 0; treat it as -1 megabytes
        // for visibility
        if (bytesUsed == -1) {
            directMemInMB.update(bytesUsed);
            if (maximumDirectMemSizeInMB > 0) {
                directMemPercent.update(bytesUsed);
            }
            return;
        }
        final double megabytesUsed = bytesUsed * UnitConstants.BYTES_TO_MEBIBYTES;
        directMemInMB.update(megabytesUsed);
        if (maximumDirectMemSizeInMB > 0) {
            directMemPercent.update(megabytesUsed * WHOLE_PERCENT / maximumDirectMemSizeInMB);
        }
    }
}
