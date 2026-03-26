package com.swirlds.platform.metrics;

import static com.swirlds.metrics.api.Metrics.INTERNAL_CATEGORY;

import com.swirlds.metrics.api.Metrics;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;
import org.hiero.consensus.metrics.FunctionGauge;

public class GCMetrics {
    private int lastCheckListLength = 0;

    public GCMetrics(final Metrics metrics) {
        final FunctionGauge.Config<Long> GC_PAUSE_TIME = new FunctionGauge.Config<>(
                INTERNAL_CATEGORY, "GcPauseTime", Long.class, this::gcPauseTimeSinceLastCall)
                .withDescription("disk space being used right now")
                .withFormat("%d");
        metrics.getOrCreate(GC_PAUSE_TIME);
    }

    private long gcPauseTimeSinceLastCall() {
        final List<GarbageCollectorMXBean> gcMxBeans = ManagementFactory.getGarbageCollectorMXBeans();
        if (gcMxBeans.size() < lastCheckListLength) {
            return 10000;
        }
        final long gcTime = gcMxBeans.subList(lastCheckListLength, gcMxBeans.size())
                .stream()
                .mapToLong(GarbageCollectorMXBean::getCollectionTime)
                .sum();
        lastCheckListLength = gcMxBeans.size();
        return gcTime;
    }
}
