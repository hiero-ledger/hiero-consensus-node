// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.base.example.metricsample;

import com.swirlds.metrics.api.DoubleGauge;
import com.swirlds.platform.base.example.ext.BaseContext;
import com.swirlds.platform.base.example.server.HttpHandlerDefinition;
import com.swirlds.platform.base.example.server.HttpHandlerRegistry;
import com.swirlds.platform.base.example.server.PostTriggerHandler;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Creates a simpler handler that updates the value of a metric
 */
public class MetricsSampleHandlerRegistry implements HttpHandlerRegistry {

    record UpdateGauge(double value) {}

    @Override
    @NonNull
    public Set<HttpHandlerDefinition> handlers(final @NonNull BaseContext context) {
        DoubleGauge gauge = context.metrics().getOrCreate(new DoubleGauge.Config("sample", "test_gauge"));

        Consumer<UpdateGauge> updateGauge = body -> {
            gauge.set(body.value);
        };

        return Set.of(new PostTriggerHandler<>("metrics/updateGauge", context, UpdateGauge.class, updateGauge));
    }
}
