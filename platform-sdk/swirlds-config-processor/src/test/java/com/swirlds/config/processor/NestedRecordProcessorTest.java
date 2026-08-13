// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.processor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs {@link ConfigDataAnnotationProcessor} over sources that use a nested config data object and checks the generated
 * constants. The processor parses the source of the annotated record on its own, so the only way to see that a nested
 * record declared in another file is resolved is to actually run a compilation.
 */
class NestedRecordProcessorTest {

    @TempDir
    Path tempDir;

    private static final String ROOT = """
            package test.cfg;

            import com.swirlds.config.api.ConfigData;
            import com.swirlds.config.api.ConfigDefault;

            @ConfigData("root")
            public record RootConfig(
                    @ConfigDefault(property = "capacity", defaultValue = "500")
                    LeafConfig prehandler,
                    LeafConfig handler) {}
            """;

    private static final String LEAF = """
            package test.cfg;

            import com.swirlds.config.api.ConfigProperty;
            import com.swirlds.config.api.NestedConfig;

            /**
             * A scheduler.
             *
             * @param type     the type of the scheduler
             * @param capacity the maximum number of unhandled tasks
             */
            @NestedConfig
            public record LeafConfig(
                    @ConfigProperty(defaultValue = "SEQUENTIAL") String type,
                    @ConfigProperty(defaultValue = "100") long capacity) {}
            """;

    @Test
    void nestedRecordIsExpandedIntoItsProperties() throws IOException {
        final String generated = compileAndReadConstants(ROOT, LEAF);

        // the nested record is expanded into the properties that can really be set
        assertTrue(generated.contains("\"root.prehandler.type\""), generated);
        assertTrue(generated.contains("\"root.prehandler.capacity\""), generated);
        assertTrue(generated.contains("\"root.handler.type\""), generated);
        assertTrue(generated.contains("\"root.handler.capacity\""), generated);

        // the component holding the nested record is not a settable property and must not be reported
        assertFalse(generated.contains("\"root.prehandler\";"), generated);
        assertFalse(generated.contains("\"root.handler\";"), generated);
    }

    @Test
    void nestedConstantNamesUseUnderscoresInsteadOfDots() throws IOException {
        final String generated = compileAndReadConstants(ROOT, LEAF);

        // a dot is not valid in a constant name, and javapoet accepts it as a qualified name, so the generated file
        // would not compile if the separator were kept
        assertTrue(generated.contains("PREHANDLER_TYPE"), generated);
        assertTrue(generated.contains("PREHANDLER_CAPACITY"), generated);
        assertFalse(generated.contains("PREHANDLER.TYPE"), generated);
    }

    @Test
    void documentedDefaultComesFromConfigDefaultOfTheUsageSite() throws IOException {
        compileAndReadConstants(ROOT, LEAF);

        final String generated = Files.readString(documentationFile(), StandardCharsets.UTF_8);

        // the same nested record is used twice, and each usage site documents its own default
        assertTrue(generated.contains("## root.prehandler.capacity"), generated);
        assertTrue(
                generated.split("## root\\.prehandler\\.capacity")[1].contains("`500`"),
                "the ConfigDefault of the usage site has to win: " + generated);
        assertTrue(
                generated.split("## root\\.handler\\.capacity")[1].contains("`100`"),
                "the default of the nested record has to be used where the usage site defines none: " + generated);
    }

    @Test
    void nestedOnlyRecordGetsNeitherConstantsNorDocumentation() throws IOException {
        compileAndReadConstants(ROOT, LEAF);

        // a nested config data object is never registered on its own, so a constants class for it would only hold
        // property names that do not exist
        assertFalse(
                Files.exists(tempDir.resolve("out/test/cfg/LeafConfig_.java")),
                "a nested config data object must not get its own constants class");

        // and it must not add entries under the bare names of its own components either
        final String documentation = Files.readString(documentationFile(), StandardCharsets.UTF_8);
        assertFalse(documentation.contains("## type"), documentation);
        assertFalse(documentation.contains("## capacity"), documentation);
    }

    @Test
    void nestedPropertyIsDocumentedWithTheParamDescriptionOfItsRecord() throws IOException {
        compileAndReadConstants(ROOT, LEAF);

        // a record component carries no javadoc of its own, so the description has to come from the @param tag of the
        // nested record
        final String documentation = Files.readString(documentationFile(), StandardCharsets.UTF_8);
        assertTrue(
                documentation.split("## root\\.prehandler\\.capacity")[1].contains("the maximum number of unhandled"),
                "the @param description of the nested record has to be documented: " + documentation);
        assertTrue(
                documentation.split("## root\\.handler\\.type")[1].contains("the type of the scheduler"),
                "the @param description of the nested record has to be documented: " + documentation);
    }

    /**
     * The processor writes the documentation relative to the working directory.
     */
    private static Path documentationFile() {
        final Path doc = Path.of(System.getProperty("user.dir"), "build/docs/config.md");
        assertTrue(Files.exists(doc), "no documentation was generated at " + doc);
        return doc;
    }

    /**
     * Compiles the given sources with the config annotation processor and returns the generated constants class.
     */
    private String compileAndReadConstants(final String... sources) throws IOException {
        final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        final Path sourceRoot = Files.createDirectories(tempDir.resolve("src/test/cfg"));
        final Path output = Files.createDirectories(tempDir.resolve("out"));

        // the processor reads the source of the annotated record from the source path, so the sources have to be real
        // files rather than in memory ones
        final List<Path> paths = new ArrayList<>();
        for (final String source : sources) {
            final String name = source.replaceAll("(?s).*public record (\\w+).*", "$1");
            paths.add(Files.writeString(sourceRoot.resolve(name + ".java"), source, StandardCharsets.UTF_8));
        }

        // the test itself runs on the module path, so the class path of this JVM alone does not resolve the config api
        final String path = Stream.of(System.getProperty("java.class.path"), System.getProperty("jdk.module.path"))
                .filter(entry -> entry != null && !entry.isBlank())
                .reduce((a, b) -> a + File.pathSeparator + b)
                .orElseThrow();

        try (final StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null)) {
            final JavaCompiler.CompilationTask task = compiler.getTask(
                    null,
                    fileManager,
                    null,
                    List.of(
                            "-d",
                            output.toString(),
                            "-s",
                            output.toString(),
                            "-sourcepath",
                            tempDir.resolve("src").toString(),
                            "-classpath",
                            path),
                    null,
                    fileManager.getJavaFileObjectsFromPaths(paths));
            // the processor is handed over as an instance so that it is not loaded a second time from the path above
            task.setProcessors(List.of(new ConfigDataAnnotationProcessor()));
            assertTrue(task.call(), "compilation of the test sources failed");
        }

        final Path constants = output.resolve("test/cfg/RootConfig_.java");
        assertTrue(Files.exists(constants), "no constants class was generated at " + constants);
        return Files.readString(constants, StandardCharsets.UTF_8);
    }
}
