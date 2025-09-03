// SPDX-License-Identifier: Apache-2.0
package com.swirlds.cli.utility;

import static com.swirlds.cli.testreader.JrsTestReader.findTestResults;
import static com.swirlds.cli.testreader.JrsTestReader.parseMetadataFile;

import com.swirlds.cli.testreader.JrsReportData;
import com.swirlds.cli.testreader.JrsTestIdentifier;
import com.swirlds.cli.testreader.JrsTestMetadata;
import com.swirlds.platform.util.VirtualTerminal;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Utility methods for the JRS test reader.
 */
public class JtrUtils {
    /**
     * Hidden constructor
     */
    private JtrUtils() {}

    /**
     * Get the test metadata from the specified metadata file.
     *
     * @param metadataFile the metadata file
     * @return the test metadata, or an empty map if the metadata file is null or doesn't exist
     */
    @NonNull
    public static Map<JrsTestIdentifier, JrsTestMetadata> getTestMetadata(@Nullable final Path metadataFile) {
        if (metadataFile == null) {
            System.out.println("No metadata file specified.");
            return new HashMap<>();
        } else if (!Files.exists(metadataFile)) {
            System.out.println("No metadata file found at " + metadataFile + ". ");
            return new HashMap<>();
        } else {
            return parseMetadataFile(metadataFile);
        }
    }

    /**
     * Scrape test data from the specified gs bucket and return the results
     *
     * @param bucketPrefix  the gs bucket to scrape data from
     * @param testDirectory the test directory relative to the bucket prefix
     * @param days          the number of days in the past to begin scraping from
     * @param threads       the number of threads to use
     * @return the test data
     */
    @NonNull
    public static JrsReportData scrapeTestData(
            @NonNull final String bucketPrefix,
            @NonNull final String testDirectory,
            final int days,
            final int threads) {

        final VirtualTerminal terminal =
                new VirtualTerminal().setProgressIndicatorEnabled(true).setThrowOnError(true);
        final Instant now = Instant.now();

        // make sure that gsutil is installed. This will throw if it isn't
        terminal.run("gsutil");

        final boolean hasSlash = bucketPrefix.endsWith("/") || testDirectory.startsWith("/");
        final String rootDirectory = bucketPrefix + (hasSlash ? "" : "/") + testDirectory;

        return findTestResults(
                terminal, Executors.newFixedThreadPool(threads), rootDirectory, now, Duration.ofDays(days));
    }
}
