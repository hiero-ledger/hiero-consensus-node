// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.merkle;

import com.swirlds.base.time.Time;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;

public interface VirtualMapStateSupplier<T extends VirtualMapState<T>> {
    T supply(@NonNull final VirtualMap virtualMap, @NonNull final Metrics metrics, @NonNull final Time time);
}
