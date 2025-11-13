// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.StreamSupport;
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

    public static void verifySnapshotHasMetrics(Optional<MetricsSnapshot> optionalSnapshot, String... metrics) {
        assertThat(optionalSnapshot).isNotEmpty();

        MetricsSnapshot snapshot = optionalSnapshot.get();

        List<String> actualMetrics = StreamSupport.stream(
                        Spliterators.spliteratorUnknownSize(snapshot.iterator(), Spliterator.ORDERED), false)
                .map(MetricSnapshot::metadata)
                .map(MetricMetadata::name)
                .toList();

        assertThat(actualMetrics).containsExactlyInAnyOrder(metrics);
    }
}
