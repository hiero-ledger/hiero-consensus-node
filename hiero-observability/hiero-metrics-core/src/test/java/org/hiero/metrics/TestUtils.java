// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.assertj.core.api.AbstractListAssert;
import org.assertj.core.api.ObjectAssert;
import org.hiero.metrics.api.core.MetricMetadata;
import org.hiero.metrics.api.export.snapshot.MetricSnapshot;
import org.hiero.metrics.api.export.snapshot.MetricsSnapshot;

public final class TestUtils {

    private TestUtils() {}

    public static String[] invalidNames() {
        return new String[] {
            "", // empty
            " ", // blank
            "123invalid", // starts with digits
            "9test", // starts with digit
            "0metric", // starts with digit
            "invalid-name", // contains hyphen
            "invalid.name", // contains dot
            "invalid name", // contains space
            "invalid@name", // contains @
            "invalid#name", // contains #
            "invalid$name", // contains $
            "invalid%name", // contains %
            "invalid&name", // contains &
            "invalid*name", // contains *
            "invalid+name", // contains +
            "invalid=name", // contains =
            "invalid!name", // contains !
            "invalid?name", // contains ?
            "invalid/name", // contains /
            "invalid\\name", // contains backslash
            "invalid|name", // contains pipe
            "invalid<name", // contains <
            "invalid>name", // contains >
            "invalid,name", // contains comma
            "invalid;name", // contains semicolon
            "invalid:name", // contains colon
            "invalid\"name", // contains quote
            "invalid'name", // contains apostrophe
            "invalid[name", // contains [
            "invalid]name", // contains ]
            "invalid{name", // contains {
            "invalid}name", // contains }
            "invalid(name", // contains (
            "invalid)name", // contains )
            "invalid~name", // contains ~
            "invalid`name", // contains backtick
        };
    }

    public static String[] validNames() {
        return new String[] {
            "a",
            "A",
            "z",
            "Z",
            "_",
            "_0",
            "a0",
            "_1",
            "_9",
            "A9",
            "valid_name",
            "__valid__name__",
            "ValidName",
            "validName123",
            "VALID_NAME_456",
        };
    }

    public static void verifySnapshotHasMetrics(MetricsSnapshot snapshot, String... metrics) {
        snapshotHasMetricsAssertion(snapshot).containsExactly(metrics);
    }

    public static void verifySnapshotHasMetricsAnyOrder(MetricsSnapshot snapshot, String... metrics) {
        snapshotHasMetricsAssertion(snapshot).containsExactlyInAnyOrder(metrics);
    }

    private static AbstractListAssert<?, List<? extends String>, String, ObjectAssert<String>>
            snapshotHasMetricsAssertion(MetricsSnapshot snapshot) {
        return assertThat(snapshot).map(MetricSnapshot::metadata).map(MetricMetadata::name);
    }
}
