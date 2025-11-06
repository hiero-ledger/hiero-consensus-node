// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.export;

import com.swirlds.base.ArgumentUtils;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Base abstract implementation of {@link MetricsExporter} that allows to specify exporter name.
 */
public abstract non-sealed class AbstractMetricsExporter implements MetricsExporter {

    private final String name;

    /**
     * Create exporter with provided name.
     *
     * @param name exporter name, must not be null or blank
     */
    protected AbstractMetricsExporter(@NonNull String name) {
        this.name = ArgumentUtils.throwArgBlank(name, "name");
    }

    @NonNull
    @Override
    public String name() {
        return name;
    }
}
