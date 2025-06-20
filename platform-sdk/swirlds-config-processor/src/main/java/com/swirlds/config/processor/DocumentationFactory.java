// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.processor;

import static com.swirlds.config.processor.MarkdownSyntax.NEWLINE;
import static com.swirlds.config.processor.MarkdownSyntax.asCode;

import com.swirlds.config.api.ConfigProperty;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Utilities for generating documentation based on a given {@link ConfigDataRecordDefinition}.
 * Its methods should be accessed statically, and it should not be instantiated.
 */
public class DocumentationFactory {

    /**
     * Prevents instantiation.
     */
    private DocumentationFactory() {}

    /**
     * Writes the documentation of the given {@link ConfigDataRecordDefinition} to a file.
     * <p>
     * The documentation includes the name, type, default value, and description of each property of the given record.
     * The file to write to is determined by the {@code configDocumentationFile} parameter.
     * Existing contents of the file are not overwritten; new contents are appended.
     *
     * @param configDataRecordDefinition The record definition to document. Must not be {@code null}.
     * @param configDocumentationFile The file to which the documentation should be written. Must not be {@code null}.
     *
     * @throws IOException If an I/O error occurs while writing to the file.
     * @throws RuntimeException If an error occurs while writing a property's documentation.
     */
    public static void doWork(
            @NonNull final ConfigDataRecordDefinition configDataRecordDefinition,
            @NonNull final Path configDocumentationFile)
            throws IOException {
        Objects.requireNonNull(configDataRecordDefinition, "configDataRecordDefinition must not be null");
        Objects.requireNonNull(configDocumentationFile, "configDocumentationFile must not be null");
        try (final FileWriter writer = new FileWriter(configDocumentationFile.toString(), true)) {
            configDataRecordDefinition.propertyDefinitions().forEach(propertyDefinition -> {
                try {
                    writer.write(MarkdownSyntax.H2_PREFIX + propertyDefinition.name() + NEWLINE);
                    final String fullRecordName = Optional.ofNullable(configDataRecordDefinition.packageName())
                            .map(packageName -> packageName + "." + configDataRecordDefinition.simpleClassName())
                            .orElse(configDataRecordDefinition.simpleClassName());
                    writer.write(MarkdownSyntax.RECORD + asCode(fullRecordName) + NEWLINE);
                    writer.write(MarkdownSyntax.TYPE + asCode(propertyDefinition.type()) + NEWLINE);
                    if (Objects.equals(propertyDefinition.defaultValue(), ConfigProperty.UNDEFINED_DEFAULT_VALUE)) {
                        writer.write(MarkdownSyntax.NO_DEFAULT_VALUE + NEWLINE);
                    } else if (Objects.equals(propertyDefinition.defaultValue(), ConfigProperty.NULL_DEFAULT_VALUE)) {
                        writer.write(MarkdownSyntax.DEFAULT_VALUE_IS_NULL + NEWLINE);
                    } else {
                        writer.write(
                                MarkdownSyntax.DEFAULT_VALUE + asCode(propertyDefinition.defaultValue()) + NEWLINE);
                    }
                    writer.write(MarkdownSyntax.DESCRIPTION + propertyDefinition.description() + NEWLINE);
                    writer.write("\n");
                } catch (IOException e) {
                    throw new RuntimeException("Error while writing doc", e);
                }
            });
        } catch (IOException e) {
            throw new RuntimeException("Error while writing doc", e);
        }
    }
}
