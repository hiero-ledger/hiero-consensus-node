// SPDX-License-Identifier: Apache-2.0
package com.swirlds.cli.platform;

import static com.swirlds.cli.testreader.JrsTestReader.saveTestResults;
import static com.swirlds.cli.utility.JtrUtils.scrapeTestData;
import static com.swirlds.common.io.utility.FileUtils.getAbsolutePath;

import com.swirlds.cli.testreader.JrsReportData;
import com.swirlds.cli.utility.AbstractCommand;
import com.swirlds.cli.utility.SubcommandOf;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import picocli.CommandLine;

@CommandLine.Command(
        name = "scrape",
        mixinStandardHelpOptions = true,
        description = "Scrape test data from gcp buckets and generate a csv containing test data.")
@SubcommandOf(JrsTestReaderCommand.class)
public class JrsTestReaderScapeCommand extends AbstractCommand {

    private JrsTestReaderScapeCommand() {}

    private String bucketPrefix;
    private String testDirectory;
    private int days = 7;
    private Path output = Path.of("testData.csv");
    private int threads = 48;

    @CommandLine.Parameters(description = "The gs bucket to scrape data from", index = "0")
    private void setBucketPrefix(@NonNull final String bucketPrefix) {
        this.bucketPrefix = bucketPrefix;
    }

    @CommandLine.Parameters(description = "The test directory relative to the bucket prefix", index = "1")
    private void setTestDirectory(@NonNull final String testDirectory) {
        this.testDirectory = testDirectory;
    }

    @CommandLine.Option(
            names = {"-d", "--days"},
            description = "Specify the number of days in the past to begin scraping from. Defaults to 7.")
    private void setDays(final int days) {
        this.days = days;
    }

    @CommandLine.Option(
            names = {"-o", "--output"},
            description = "Specify the path to the output csv file. Defaults to 'testData.csv'.")
    private void setOutput(@NonNull final Path output) {
        this.output = Objects.requireNonNull(getAbsolutePath(output));
    }

    @CommandLine.Option(
            names = {"-t", "--threads"},
            description = "Specify the number of threads to use. Defaults to 48.")
    private void setThreads(final int threads) {
        this.threads = threads;
    }

    @Override
    public Integer call() {
        if (Files.exists(output)) {
            try {
                Files.delete(output);
            } catch (final IOException e) {
                throw new RuntimeException(e);
            }
        }

        final JrsReportData data = scrapeTestData(bucketPrefix, testDirectory, days, threads);

        saveTestResults(data, output);

        return 0;
    }
}
