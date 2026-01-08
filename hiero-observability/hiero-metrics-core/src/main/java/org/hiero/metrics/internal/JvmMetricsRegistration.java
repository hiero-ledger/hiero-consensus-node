// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal;

import com.google.auto.service.AutoService;
import com.sun.management.UnixOperatingSystemMXBean;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.hiero.metrics.api.ObservableGauge;
import org.hiero.metrics.api.core.Metric;
import org.hiero.metrics.api.core.MetricsRegistrationProvider;

/**
 * A {@link MetricsRegistrationProvider} that registers standard JVM metrics such as memory usage,
 * CPU load, and open file descriptors.
 */
@AutoService(MetricsRegistrationProvider.class)
public final class JvmMetricsRegistration implements MetricsRegistrationProvider {

    @NonNull
    @Override
    public Collection<Metric.Builder<?, ?>> getMetricsToRegister(@NonNull Configuration configuration) {
        Collection<Metric.Builder<?, ?>> builders = new ArrayList<>();

        final OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
        final BufferPoolMXBean directMemMxBean = getDirectMemMxBean();
        final String category = "jvm";

        builders.add(ObservableGauge.builder(ObservableGauge.key("memory").withCategory(category))
                .withDynamicLabelNames("type")
                .withDescription("JVM memory usage")
                .withUnit("bytes")
                .observeValue(() -> Runtime.getRuntime().maxMemory(), "type", "max")
                .observeValue(() -> Runtime.getRuntime().totalMemory(), "type", "total")
                .observeValue(() -> Runtime.getRuntime().freeMemory(), "type", "free")
                .observeValue(() -> directMemMxBean != null ? directMemMxBean.getMemoryUsed() : -1, "type", "direct"));

        if (osBean instanceof UnixOperatingSystemMXBean mBean) {
            builders.add(ObservableGauge.builder(
                            ObservableGauge.key("open_file_descriptors").withCategory(category))
                    .withDescription("Number of open file descriptors")
                    .withUnit("count")
                    .observeValue(mBean::getOpenFileDescriptorCount));
        }
        if (osBean instanceof com.sun.management.OperatingSystemMXBean mBean) {
            builders.add(ObservableGauge.builder(ObservableGauge.key("cpu_load").withCategory(category))
                    .withDescription("CPU load of the JVM process")
                    .withUnit("percent")
                    .observeValue(mBean::getProcessCpuLoad));
        }

        builders.add(ObservableGauge.builder(
                        ObservableGauge.key("available_processors").withCategory(category))
                .withDescription("Available processors")
                .observeValue(() -> Runtime.getRuntime().availableProcessors()));

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
