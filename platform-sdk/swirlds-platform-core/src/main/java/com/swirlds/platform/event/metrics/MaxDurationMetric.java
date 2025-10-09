package com.swirlds.platform.event.metrics;

import static com.swirlds.metrics.api.Metrics.PLATFORM_CATEGORY;

import com.swirlds.base.time.Time;
import com.swirlds.metrics.api.LongAccumulator;
import com.swirlds.metrics.api.LongAccumulator.Config;
import com.swirlds.metrics.api.Metrics;
import java.time.Instant;

public class MaxDurationMetric {
    private final Time time;
    private final LongAccumulator metric;

    public MaxDurationMetric(final Metrics metrics, final Time time) {
        final Config config = new Config(PLATFORM_CATEGORY, "anme")
                .withUnit("ms")
                //.withInitializer(()->0)
                .withAccumulator(Math::max)
                .withInitialValue(0);
        metric = metrics.getOrCreate(config);
        this.time = time;
    }

    public void update(final Instant start) {
        final long duration = time.currentTimeMillis() - start.toEpochMilli();
        metric.update(duration);
    }
}
