package com.swirlds.platform.event.metrics;

import static com.swirlds.metrics.api.Metrics.PLATFORM_CATEGORY;

import com.swirlds.metrics.api.LongAccumulator;
import com.swirlds.metrics.api.Metrics;

public class EventPipelineTracker {
    private final Metrics metrics;

    public EventPipelineTracker(final Metrics metrics) {
        this.metrics = metrics;

    }
}
