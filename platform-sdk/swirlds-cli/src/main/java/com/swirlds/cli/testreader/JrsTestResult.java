// SPDX-License-Identifier: Apache-2.0
package com.swirlds.cli.testreader;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * The result of a JRS test run.
 *
 * @param id            the test ID
 * @param status        true if the test passed, false if the test failed
 * @param timestamp     the timestamp of the test run
 * @param testDirectory the directory where the test was run
 */
public record JrsTestResult(
        @NonNull JrsTestIdentifier id,
        @NonNull TestStatus status,
        @NonNull Instant timestamp,
        @NonNull String testDirectory)
        implements Comparable<JrsTestResult> {

    /**
     * Parse a JrsTestResult from a CSV line. The inverse of {@link #toCsvLine()}.
     *
     * @param line the CSV line
     * @return the parsed JrsTestResult, or null if the line could not be parsed
     */
    @Nullable
    public static JrsTestResult parseFromCsvLine(@NonNull final String line) {
        final String[] parts = line.split(",");

        if (parts.length != 5) {
            return null;
        }

        try {
            return new JrsTestResult(
                    new JrsTestIdentifier(parts[0], parts[1]),
                    TestStatus.valueOf(parts[2]),
                    Instant.parse(parts[3]),
                    parts[4]);
        } catch (final DateTimeParseException | IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Write this JrsTestResult to a CSV line. The inverse of {@link #parseFromCsvLine(String)}.
     *
     * @return the CSV line
     */
    @NonNull
    public String toCsvLine() {
        return String.join(",", id.panel(), id.name(), status.name(), timestamp.toString(), testDirectory);
    }

    /**
     * Compare this JrsTestResult to another JrsTestResult. The comparison is based on the timestamp
     *
     * @param that the object to be compared.
     * @return a negative integer, zero, or a positive integer as this object is less than, equal to,
     */
    @Override
    public int compareTo(@NonNull final JrsTestResult that) {
        return that.timestamp.compareTo(this.timestamp);
    }
}
