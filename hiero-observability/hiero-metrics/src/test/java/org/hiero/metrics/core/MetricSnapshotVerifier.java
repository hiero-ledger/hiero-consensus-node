// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.assertj.core.api.IterableAssert;

public class MetricSnapshotVerifier {

    private static final Comparator<LabelValues> LABEL_VALUES_COMPARATOR = (lv1, lv2) -> {
        int sizeComparison = Integer.compare(lv1.size(), lv2.size());
        if (sizeComparison != 0) {
            return sizeComparison;
        }
        for (int i = 0; i < lv1.size(); i++) {
            int labelComparison = lv1.get(i).compareTo(lv2.get(i));
            if (labelComparison != 0) {
                return labelComparison;
            }
        }
        return 0;
    };

    private static final Comparator<MeasurementSnapshot> MEASUREMENT_SNAPSHOT_COMPARATOR = (s1, s2) -> {
        int compare = LABEL_VALUES_COMPARATOR.compare(s1.getDynamicLabelValues(), s2.getDynamicLabelValues());
        if (compare != 0) {
            return compare;
        }

        if (s1 instanceof DoubleMeasurementSnapshot s1d && s2 instanceof DoubleMeasurementSnapshot s2d) {
            return Double.compare(s1d.get(), s2d.get());
        } else if (s1 instanceof LongMeasurementSnapshot s1l && s2 instanceof LongMeasurementSnapshot s2l) {
            return Long.compare(s1l.get(), s2l.get());
        }
        return -1;
    };

    final Metric metric;
    private final List<MeasurementSnapshot> expectations = new ArrayList<>();
    private boolean snapshotsAnyOrder = false;

    public MetricSnapshotVerifier(Metric metric) {
        this.metric = metric;
    }

    public MetricSnapshotVerifier add(double value, String... labelNamesAndValues) {
        expectations.add(new DoubleMeasurementSnapshot(new LabelValues(labelNamesAndValues), () -> value));
        return this;
    }

    public MetricSnapshotVerifier add(long value, String... labelNamesAndValues) {
        expectations.add(new LongMeasurementSnapshot(new LabelValues(labelNamesAndValues), () -> value));
        return this;
    }

    public MetricSnapshotVerifier snapshotsAnyOrder() {
        this.snapshotsAnyOrder = true;
        return this;
    }

    public void verify(MetricSnapshot snapshot) {
        assertThat(snapshot.name()).isEqualTo(metric.name());
        assertThat(snapshot.type())
                .as("Metric type equality for %metric" + metric.name())
                .isEqualTo(metric.type());
        assertThat(snapshot.description())
                .as("Description equality for metric " + metric.name())
                .isEqualTo(metric.description());
        assertThat(snapshot.unit()).as("Unit equality for " + metric.name()).isEqualTo(metric.unit());
        assertThat(snapshot.dynamicLabelNames())
                .as("Dynamic labels equality for metric " + metric.name())
                .isEqualTo(metric.dynamicLabelNames());
        assertThat(snapshot.staticLabels())
                .as("Static labels equality for metric " + metric.name())
                .isEqualTo(metric.staticLabels());

        IterableAssert<MeasurementSnapshot> assertion = assertThat(snapshot)
                .as("Measurement snapshots for metric " + metric.name())
                .usingElementComparator(MEASUREMENT_SNAPSHOT_COMPARATOR);

        if (snapshotsAnyOrder) {
            assertion.containsExactlyInAnyOrderElementsOf(expectations);
        } else {
            assertion.containsExactlyElementsOf(expectations);
        }
    }

    public static void verifMetricHasNoSnapshot(Metric metric) {
        assertThat(metric.snapshot())
                .as("Snapshot should be null for metric " + metric.name())
                .isNull();
    }

    public void verify() {
        MetricSnapshot snapshot = metric.snapshot();
        assertThat(snapshot).isNotNull();

        snapshot.update();

        verify(snapshot);
    }
}
