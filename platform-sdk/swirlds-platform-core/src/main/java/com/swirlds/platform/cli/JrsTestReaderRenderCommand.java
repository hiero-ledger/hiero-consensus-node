// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.cli;

import static com.swirlds.common.io.utility.FileUtils.getAbsolutePath;
import static com.swirlds.platform.testreader.JrsTestReader.loadTestResults;
import static com.swirlds.platform.testreader.JrsTestReportGenerator.generateReport;

import com.swirlds.cli.utility.AbstractCommand;
import com.swirlds.cli.utility.SubcommandOf;
import com.swirlds.platform.cli.utils.JtrUtils;
import com.swirlds.platform.testreader.JrsReportData;
import com.swirlds.platform.testreader.JrsTestIdentifier;
import com.swirlds.platform.testreader.JrsTestMetadata;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import picocli.CommandLine;

@CommandLine.Command(
        name = "render",
        mixinStandardHelpOptions = true,
        description = "Render test data into an HTML report.")
@SubcommandOf(JrsTestReaderCommand.class)
public class JrsTestReaderRenderCommand extends AbstractCommand {

    private Path testData;
    private String bucketPrefix;
    private String bucketPrefixReplacement;
    private Path metadataFile;
    private Path output = Path.of("report.html");

    private JrsTestReaderRenderCommand() {}

    @CommandLine.Parameters(description = "The csv file where test data can be found.", index = "0")
    private void setTestData(@NonNull final Path testData) {
        this.testData = pathMustExist(testData);
    }

    @CommandLine.Parameters(
            description = "The bucket prefix to replace in order to convert bucket URLs to web links.",
            index = "1")
    private void setBucketPrefix(@NonNull final String bucketPrefix) {
        this.bucketPrefix = bucketPrefix;
    }

    @CommandLine.Parameters(
            description = "The replacement for bucket prefix in order to convert bucket URLs to web links.",
            index = "2")
    private void setBucketPrefixReplacement(@NonNull final String bucketPrefix) {
        this.bucketPrefixReplacement = bucketPrefix;
    }

    @CommandLine.Option(
            names = {"-o", "--output"},
            description = "Specify the path to the output html file. Defaults to 'report.html'.")
    private void setOutput(@NonNull final Path output) {
        this.output = Objects.requireNonNull(getAbsolutePath(output));
    }

    @CommandLine.Option(
            names = {"-m", "--metadata"},
            description = "Specify the path to the test metadata csv file.")
    private void setMetadataFile(@NonNull final Path metadataFile) {
        this.metadataFile = getAbsolutePath(metadataFile);
    }

    @Override
    @NonNull
    public Integer call() {
        if (Files.exists(output)) {
            try {
                Files.delete(output);
            } catch (final IOException e) {
                throw new RuntimeException(e);
            }
        }

        final JrsReportData data = loadTestResults(testData);
        final Map<JrsTestIdentifier, JrsTestMetadata> metadata = JtrUtils.getTestMetadata(metadataFile);

        generateReport(data, metadata, Instant.now(), bucketPrefix, bucketPrefixReplacement, output);

        return 0;
    }
}
