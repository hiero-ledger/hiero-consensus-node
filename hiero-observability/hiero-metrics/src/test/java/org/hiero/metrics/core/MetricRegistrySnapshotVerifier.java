// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.core;

import java.util.HashMap;
import java.util.Map;

public class MetricRegistrySnapshotVerifier {

    private final Map<String, MetricSnapshotVerifier> expectations = new HashMap<>();

    public MetricRegistrySnapshotVerifier add(MetricSnapshotVerifier verifier) {
        MetricSnapshotVerifier old = expectations.put(verifier.metric.name(), verifier);
        if (old != null) {
            throw new IllegalArgumentException(
                    "MetricSnapshotVerifier for metric " + verifier.metric.name() + " already exists");
        }
        return this;
    }

    public void verify(MetricRegistrySnapshot registrySnapshot) {
        for (MetricSnapshot metricSnapshot : registrySnapshot) {
            MetricSnapshotVerifier verifier = expectations.remove(metricSnapshot.name());

            if (verifier == null) {
                throw new AssertionError("Unexpected metric snapshot: " + metricSnapshot.name());
            }

            verifier.verify(metricSnapshot);
        }

        if (!expectations.isEmpty()) {
            throw new AssertionError("Missing metric snapshots: " + expectations.keySet());
        }
    }
}
