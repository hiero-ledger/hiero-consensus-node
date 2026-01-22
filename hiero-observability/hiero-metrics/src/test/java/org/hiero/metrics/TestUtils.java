// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.assertj.core.api.AbstractListAssert;
import org.assertj.core.api.ObjectAssert;
import org.hiero.metrics.core.MetricRegistrySnapshot;
import org.hiero.metrics.core.MetricSnapshot;

public final class TestUtils {

    private TestUtils() {}

    public static String[] invalidMetricNames() {
        return new String[] {
            "", // empty
            " ", // blank
            "_", // only underscore
            ":", // only colon
            "5", // only digit
            "123name", // starts with digits
            "9name", // starts with digit
            "0name", // starts with digit
            ":name", // starts with colon
            "_name", // starts with underscore
            " name", // starts with space
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

    public static String[] invalidUnitNames() {
        return new String[] {
            "", // empty
            " ", // blank
            "_", // only underscore
            ":", // only colon
            "5", // only digit
            "123name", // starts with digits
            "9name", // starts with digit
            "0name", // starts with digit
            ":name", // starts with colon
            "_name", // starts with underscore
            " name", // starts with space
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

    public static String[] invalidLabelNames() {
        return new String[] {
            "", // empty
            " ", // blank
            "_", // only underscore
            ":", // only colon
            "5", // only digit
            "123name", // starts with digits
            "9name", // starts with digit
            "0name", // starts with digit
            ":name", // starts with colon
            "_name", // starts with underscore
            " name", // starts with space
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

    public static String[] validMetricNames() {
        return new String[] {
            "a",
            "A",
            "z",
            "Z",
            "a0",
            "A9",
            "valid_name",
            "valid_name_5",
            "valid_name0",
            "valid__name__",
            "ValidName",
            "Valid5Name",
            "validName123",
            "VALID_NAME_456",
            "valid:name",
            "valid::name",
            "valid_name::",
            "valid_name:123",
        };
    }

    public static String[] validUnitNames() {
        return new String[] {
            "a",
            "A",
            "z",
            "Z",
            "a0",
            "A9",
            "valid_name",
            "valid_name_5",
            "valid_name0",
            "valid__name__",
            "ValidName",
            "Valid5Name",
            "validName123",
            "VALID_NAME_456"
        };
    }

    public static String[] validLabelNames() {
        return new String[] {
            "a",
            "A",
            "z",
            "Z",
            "a0",
            "A9",
            "valid_name",
            "valid_name_5",
            "valid_name0",
            "valid__name__",
            "ValidName",
            "Valid5Name",
            "validName123",
            "VALID_NAME_456"
        };
    }

    public static String[] validDescriptions() {
        return new String[] {
            "", // empty
            " ", // blank / whitespace
            "a", // single character
            "This is a description.", // simple sentence with punctuation
            "Line1\nLine2", // multi-line
            "Tab\tseparated", // contains a tab
            "Emoji 😊", // contains emoji / Unicode
            "長い説明テキスト" // non-Latin script
        };
    }

    public static void verifySnapshotHasMetricsInOrder(MetricRegistrySnapshot snapshot, String... metrics) {
        snapshotHasMetricsAssertion(snapshot).containsExactly(metrics);
    }

    private static AbstractListAssert<?, List<? extends String>, String, ObjectAssert<String>>
            snapshotHasMetricsAssertion(MetricRegistrySnapshot snapshot) {
        return assertThat(snapshot).map(MetricSnapshot::name);
    }
}
