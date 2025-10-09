// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal;

import com.sun.management.UnixOperatingSystemMXBean;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.hiero.metrics.api.StatelessMetric;
import org.hiero.metrics.api.core.Metric;
import org.hiero.metrics.api.core.MetricsRegistrationProvider;

/**
 * A {@link MetricsRegistrationProvider} that registers standard JVM metrics such as memory usage,
 * CPU load, and open file descriptors.
 */
public class JvmMetricsRegistration implements MetricsRegistrationProvider {

    @NonNull
    @Override
    public Collection<Metric.Builder<?, ?>> getMetricsToRegister() {
        Collection<Metric.Builder<?, ?>> builders = new ArrayList<>();

        final OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
        final BufferPoolMXBean directMemMxBean = getDirectMemMxBean();
        final String category = "jvm";

        builders.add(StatelessMetric.builder(StatelessMetric.key("memory").withCategory(category))
                .withDynamicLabelNames("type")
                .withDescription("JVM memory usage")
                .withUnit("bytes")
                .registerDataPoint(() -> Runtime.getRuntime().maxMemory(), "type", "max")
                .registerDataPoint(() -> Runtime.getRuntime().totalMemory(), "type", "total")
                .registerDataPoint(() -> Runtime.getRuntime().freeMemory(), "type", "free")
                .registerDataPoint(
                        () -> directMemMxBean != null ? directMemMxBean.getMemoryUsed() : -1, "type", "direct"));

        if (osBean instanceof UnixOperatingSystemMXBean mBean) {
            builders.add(StatelessMetric.builder(
                            StatelessMetric.key("open_file_descriptors").withCategory(category))
                    .withDescription("Number of open file descriptors")
                    .withUnit("count")
                    .registerDataPoint(mBean::getOpenFileDescriptorCount));
        }
        if (osBean instanceof com.sun.management.OperatingSystemMXBean mBean) {
            builders.add(StatelessMetric.builder(StatelessMetric.key("cpu_load").withCategory(category))
                    .withDescription("CPU load of the JVM process")
                    .withUnit("percent")
                    .registerDataPoint(mBean::getProcessCpuLoad));
        }

        builders.add(StatelessMetric.builder(
                        StatelessMetric.key("available_processors").withCategory(category))
                .withDescription("Available processors")
                .registerDataPoint(() -> Runtime.getRuntime().availableProcessors()));

        return builders;
    }

    private static @Nullable BufferPoolMXBean getDirectMemMxBean() {
        final List<BufferPoolMXBean> pools = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class);
        for (final BufferPoolMXBean pool : pools) {
            if (pool.getName().equals("direct")) {
                return pool;
            }
        }
        return null;
    }
}
