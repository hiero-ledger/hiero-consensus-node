// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.processor;

import com.swirlds.config.processor.antlr.AntlrConfigRecordParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class DocumentationFactoryTest {

    @TempDir
    Path tempDir;

    private final String RECORD_DEFINITION =
            MarkdownSyntax.RECORD + MarkdownSyntax.asCode("com.swirlds.config.processor.TestConfig");

    private final String JAVA_FILE = "TestConfig.java";

    @Test
    void testDocumentationCreation() throws Exception {
        // given
        final String docFileName = "test-config.md";
        final String javaFilePath =
                DocumentationFactoryTest.class.getResource(JAVA_FILE).getPath();
        final String content = Files.readString(Path.of(javaFilePath));
        final ConfigDataRecordDefinition definition =
                AntlrConfigRecordParser.parse(content).get(0);
        final Path docFilePath = Path.of(tempDir.toString(), docFileName);

        // when
        DocumentationFactory.doWork(definition, docFilePath);

        // then
        Assertions.assertTrue(Files.exists(docFilePath));
        final List<String> lines = Files.readAllLines(docFilePath);
        final List<String> nonEmptyLines =
                lines.stream().filter(l -> !l.isEmpty()).collect(Collectors.toList());
        Assertions.assertEquals(MarkdownSyntax.H2_PREFIX + "test.saveStatePeriod", nonEmptyLines.get(0));
        Assertions.assertEquals(RECORD_DEFINITION, nonEmptyLines.get(1));
        Assertions.assertEquals(MarkdownSyntax.TYPE + MarkdownSyntax.asCode("int"), nonEmptyLines.get(2));
        Assertions.assertEquals(MarkdownSyntax.DEFAULT_VALUE + MarkdownSyntax.asCode("900"), nonEmptyLines.get(3));
        Assertions.assertEquals(
                MarkdownSyntax.DESCRIPTION
                        + "The frequency of writes of a state to disk every this many seconds (0 to never write).",
                nonEmptyLines.get(4));
    }
}
