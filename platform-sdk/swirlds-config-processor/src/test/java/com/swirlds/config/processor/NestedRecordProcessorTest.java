// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.processor;

import static com.swirlds.config.processor.ConfigDataAnnotationProcessor.DOCUMENTATION_FILE_OPTION;
import static com.swirlds.config.processor.ConfigProcessorConstants.CONSTANTS_CLASS_SUFFIX;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
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

            @ConfigData("root")
            public record RootConfig(LeafConfig prehandler, LeafConfig handler) {}
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

    private static final String DOTTED_LEAF = """
            package test.cfg;

            import com.swirlds.config.api.ConfigProperty;
            import com.swirlds.config.api.NestedConfig;

            @NestedConfig
            public record DottedLeafConfig(@ConfigProperty(value = "foo.bar") String value) {}
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
    void constantNamesKeepTheSeparatorWhenTheRecordDefinesNoPrefix() throws IOException {
        final String root = """
                package test.cfg;

                import com.swirlds.config.api.ConfigData;

                @ConfigData
                public record RootConfig(LeafConfig leaf) {}
                """;

        final String generated = compileAndReadConstants(root, LEAF);

        // the prefix of a record without one is empty, so there is nothing to remove. Removing "." from every position
        // would run the segments together into LEAFTYPE
        assertTrue(generated.contains("LEAF_TYPE = \"leaf.type\""), generated);
        assertTrue(generated.contains("LEAF_CAPACITY = \"leaf.capacity\""), generated);
    }

    @Test
    void constantNamesRemoveOnlyTheLeadingPrefix() throws IOException {
        final String root = """
                package test.cfg;

                import com.swirlds.config.api.ConfigData;

                @ConfigData("root")
                public record RootConfig(LeafConfig root, LeafConfig rootish) {}
                """;

        final String generated = compileAndReadConstants(root, LEAF);

        // the prefix occurs again as the name of the component, where it is part of the property and has to be kept
        assertTrue(generated.contains("ROOT_TYPE = \"root.root.type\""), generated);
        assertTrue(generated.contains("ROOT_CAPACITY = \"root.root.capacity\""), generated);

        // and it occurs a third time as the start of another component, which is not a segment of its own at all
        assertTrue(generated.contains("ROOTISH_TYPE = \"root.rootish.type\""), generated);
        assertTrue(generated.contains("ROOTISH_CAPACITY = \"root.rootish.capacity\""), generated);
    }

    @Test
    void constantNameClashIsReported() throws IOException {
        final String root = """
                package test.cfg;

                import com.swirlds.config.api.ConfigData;
                import com.swirlds.config.api.ConfigProperty;

                @ConfigData("root")
                public record RootConfig(
                        LeafConfig leaf,
                        @ConfigProperty(defaultValue = "0") long leafType) {}
                """;

        // "leaf.type" and "leafType" both become LEAF_TYPE, and adding the field twice would generate a class that
        // does not compile
        final String messages = compileExpectingFailure(root, LEAF);

        assertTrue(messages.contains("both map onto the constant name \"LEAF_TYPE\""), messages);
        assertTrue(messages.contains("Error processing record: RootConfig"), messages);
    }

    @Test
    void propertyNameThatIsNoValidConstantNameIsReported() throws IOException {
        final String root = """
                package test.cfg;

                import com.swirlds.config.api.ConfigData;
                import com.swirlds.config.api.ConfigProperty;

                @ConfigData("root")
                public record RootConfig(@ConfigProperty(value = "1st", defaultValue = "0") long first) {}
                """;

        final String messages = compileExpectingFailure(root);

        assertTrue(messages.contains("cannot be used as a valid constant name"), messages);
        assertTrue(messages.contains("Error processing record: RootConfig"), messages);
    }

    /**
     * A blank {@code ConfigProperty} value is indistinguishable from an absent one, since the default of the member is
     * the empty string, so the runtime falls back to the name of the component. The processor has to do the same, for a
     * component that holds a group as well as for a scalar one, or the generated constants and documentation name
     * properties the runtime never reads.
     */
    @Test
    void blankAnnotationValueFallsBackToTheComponentName() throws IOException {
        final String root = """
                package test.cfg;

                import com.swirlds.config.api.ConfigData;
                import com.swirlds.config.api.ConfigProperty;

                @ConfigData("root")
                public record RootConfig(
                        @ConfigProperty(value = "") LeafConfig plain,
                        @ConfigProperty(value = "") long retries) {}
                """;

        final String generated = compileAndReadConstants(root, LEAF);

        // without the fallback the prefix of the group is "root." and the segments run into "root..type"
        assertTrue(generated.contains("PLAIN_TYPE = \"root.plain.type\""), generated);
        assertTrue(generated.contains("PLAIN_CAPACITY = \"root.plain.capacity\""), generated);
        // the scalar case has no constant name at all without the fallback, which fails the whole compilation
        assertTrue(generated.contains("RETRIES = \"root.retries\""), generated);
    }

    /**
     * The value of a {@code ConfigProperty} is what the compiler evaluated, which is what the runtime reads through
     * reflection. Taking the text of the annotation from the parsed source instead would name the property after the
     * expression that defines it.
     */
    @Test
    void annotationValueIsTheEvaluatedConstantAndNotItsSource() throws IOException {
        final String root = """
                package test.cfg;

                import com.swirlds.config.api.ConfigData;
                import com.swirlds.config.api.ConfigProperty;

                interface Names {
                    String GROUP = "group";
                    String PORT_NUMBER = "portNumber";
                }

                @ConfigData("root")
                public record RootConfig(
                        @ConfigProperty(value = Names.GROUP) LeafConfig group,
                        @ConfigProperty(value = Names.PORT_NUMBER) long port) {}
                """;

        final String generated = compileAndReadConstants(root, LEAF);

        assertTrue(generated.contains("GROUP_TYPE = \"root.group.type\""), generated);
        assertTrue(generated.contains("GROUP_CAPACITY = \"root.group.capacity\""), generated);
        assertTrue(generated.contains("PORT_NUMBER = \"root.portNumber\""), generated);

        // the name of the constant that defines the value must not leak into the property name
        assertFalse(generated.contains("Names"), generated);
    }

    /**
     * A property of a nested config data object is declared by that nested record. Referring to the config data record
     * that is being processed would name the component holding the group instead, which is not a property at all, and
     * every property of one group would be documented as that very same member.
     */
    @Test
    void nestedConstantRefersToTheComponentOfTheNestedRecord() throws IOException {
        final String generated = compileAndReadConstants(ROOT, LEAF);

        assertTrue(generated.contains("{@link test.cfg.LeafConfig#type}"), generated);
        assertTrue(generated.contains("{@link test.cfg.LeafConfig#capacity}"), generated);
        assertTrue(generated.contains("@see test.cfg.LeafConfig#capacity"), generated);

        // the component holding the group is not a property, so no constant may be documented as it
        assertFalse(generated.contains("#prehandler}"), generated);
        assertFalse(generated.contains("#handler}"), generated);
    }

    /**
     * A property that the config data record declares itself is still referred to on that record, so the reference is
     * built the same way whether or not a nested record is involved.
     */
    @Test
    void constantOfAPlainPropertyStillRefersToTheConfigDataRecord() throws IOException {
        final String root = """
                package test.cfg;

                import com.swirlds.config.api.ConfigData;
                import com.swirlds.config.api.ConfigProperty;

                @ConfigData("root")
                public record RootConfig(@ConfigProperty(defaultValue = "0") long value) {}
                """;

        final String generated = compileAndReadConstants(root);

        assertTrue(generated.contains("{@link test.cfg.RootConfig#value}"), generated);
        assertTrue(generated.contains("@see test.cfg.RootConfig#value"), generated);
    }

    /**
     * A nested config data object takes its name from the single component holding it, so an element of a collection has
     * no property name a config source could use. Documenting it as a settable property and generating a constant for it
     * would advertise a property the configuration can never populate.
     */
    @Test
    void listOfNestedRecordsIsReported() throws IOException {
        final String root = """
                package test.cfg;

                import com.swirlds.config.api.ConfigData;
                import java.util.List;

                @ConfigData("root")
                public record RootConfig(List<LeafConfig> leaves) {}
                """;

        final String messages = compileExpectingFailure(root, LEAF);

        assertTrue(messages.contains("java.util.List"), messages);
        assertTrue(messages.contains("test.cfg.LeafConfig"), messages);
        assertTrue(messages.contains("element of a collection"), messages);
    }

    @Test
    void setOfNestedRecordsInsideANestedRecordIsReported() throws IOException {
        final String root = """
                package test.cfg;

                import com.swirlds.config.api.ConfigData;

                @ConfigData("root")
                public record RootConfig(GroupConfig group) {}
                """;
        final String group = """
                package test.cfg;

                import com.swirlds.config.api.NestedConfig;
                import java.util.Set;

                @NestedConfig
                public record GroupConfig(Set<LeafConfig> leaves) {}
                """;

        final String messages = compileExpectingFailure(root, group, LEAF);

        assertTrue(messages.contains("java.util.Set"), messages);
        assertTrue(messages.contains("element of a collection"), messages);
    }

    /**
     * Only a collection of a nested config data object is rejected. A collection of a type that a converter creates is a
     * single property that is read as a list of values.
     */
    @Test
    void collectionOfAConvertedTypeIsStillExpanded() throws IOException {
        final String root = """
                package test.cfg;

                import com.swirlds.config.api.ConfigData;
                import com.swirlds.config.api.ConfigProperty;
                import java.util.List;

                @ConfigData("root")
                public record RootConfig(@ConfigProperty(defaultValue = "a,b") List<String> values) {}
                """;

        final String generated = compileAndReadConstants(root);

        assertTrue(generated.contains("VALUES = \"root.values\""), generated);
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
     * The properties of a group follow from its type, and this processor reads the declared type while the runtime
     * reads the erasure. Requiring the record type to be named is what keeps the two from disagreeing, so a component
     * that hides the type behind a type variable is rejected rather than silently expanded.
     */
    @Test
    void componentWhoseTypeIsATypeVariableIsRejected() throws IOException {
        final String root = """
                package test.cfg;

                import com.swirlds.config.api.ConfigData;

                @ConfigData("root")
                public record RootConfig<T extends LeafConfig>(T leaf) {}
                """;

        final String messages = compileExpectingFailure(root, LEAF);

        assertTrue(messages.contains("instead of naming the record type"), messages);
        assertTrue(messages.contains("leaf"), messages);
    }

    @Test
    void genericNestedRecordComponentIsRejected() throws IOException {
        final String root = """
                package test.cfg;

                import com.swirlds.config.api.ConfigData;
                import java.time.Duration;

                @ConfigData("root")
                public record RootConfig(WrapperConfig<Duration> timeout) {}
                """;
        final String wrapper = """
                package test.cfg;

                import com.swirlds.config.api.ConfigProperty;
                import com.swirlds.config.api.NestedConfig;

                @NestedConfig
                public record WrapperConfig<T>(@ConfigProperty(defaultValue = "1s") T value) {}
                """;

        final String messages = compileExpectingFailure(root, wrapper);

        assertTrue(messages.contains("instead of naming the record type"), messages);
    }

    /**
     * A group has no value of its own that a config source could define, so a default value on the component holding it
     * means nothing. Without this the value would silently be dropped here while the runtime rejects the same
     * declaration.
     */
    @Test
    void defaultValueOnAComponentHoldingANestedRecordIsRejected() throws IOException {
        final String root = """
                package test.cfg;

                import com.swirlds.config.api.ConfigData;
                import com.swirlds.config.api.ConfigProperty;

                @ConfigData("root")
                public record RootConfig(@ConfigProperty(defaultValue = "nonsense") LeafConfig leaf) {}
                """;

        final String messages = compileExpectingFailure(root, LEAF);

        assertTrue(messages.contains("group of properties rather than a value"), messages);
        assertTrue(messages.contains("leaf"), messages);
    }

    /**
     * Erasing the component type must not throw away the type arguments of an ordinary generic type, which are what
     * tells the runtime what to convert the elements of a collection to.
     */
    @Test
    void typeArgumentsOfAGenericPropertyAreKept() throws IOException {
        final String root = """
                package test.cfg;

                import com.swirlds.config.api.ConfigData;

                @ConfigData("root")
                public record RootConfig(GenericLeafConfig leaf) {}
                """;
        final String leaf = """
                package test.cfg;

                import com.swirlds.config.api.ConfigProperty;
                import com.swirlds.config.api.NestedConfig;
                import java.time.Duration;
                import java.util.Set;

                @NestedConfig
                public record GenericLeafConfig(
                        @ConfigProperty(defaultValue = "1s") Set<Duration> values) {}
                """;

        compileAndReadConstants(root, leaf);

        final String documentation = Files.readString(documentationFile(), StandardCharsets.UTF_8);
        assertTrue(
                documentation.split("## root\\.leaf\\.values")[1].contains("java.util.Set<java.time.Duration>"),
                "the type arguments have to be kept: " + documentation);
    }

    /**
     * A dot may be part of a single property name that {@code @ConfigProperty} defines, exactly as it may on a config
     * data record. Grouping does not change how a name is spelled, it only prefixes it.
     */
    @Test
    void aPropertyNameContainingADotIsFlattenedAsItIsSpelled() throws IOException {
        final String root = """
                package test.cfg;

                import com.swirlds.config.api.ConfigData;

                @ConfigData("root")
                public record RootConfig(DottedLeafConfig leaf) {}
                """;

        final String generated = compileAndReadConstants(root, DOTTED_LEAF);

        assertTrue(generated.contains("\"root.leaf.foo.bar\""), generated);
    }

    @Test
    void recordWithBothAnnotationsIsRejectedAsANestedComponent() throws IOException {
        final String root = """
                package test.cfg;

                import com.swirlds.config.api.ConfigData;

                @ConfigData("root")
                public record RootConfig(BothConfig both) {}
                """;

        final String both = """
                package test.cfg;

                import com.swirlds.config.api.ConfigData;
                import com.swirlds.config.api.NestedConfig;

                @ConfigData("both")
                @NestedConfig
                public record BothConfig(String value) {}
                """;

        // the record would otherwise get its own constants under the "both" prefix and be expanded inline under
        // "root.both" at the same time, which are two conflicting sets of property names
        final String messages = compileExpectingFailure(root, both);

        assertTrue(messages.contains("mutually exclusive"), messages);
    }

    /**
     * The two annotations are a mistake wherever the record turns up, so the record being processed has to be checked
     * as well and not only the records it holds. The runtime refuses to register such a record, so generating constants
     * and documentation for it describes a config data object that can never be created.
     */
    @Test
    void recordWithBothAnnotationsIsRejectedAsARoot() throws IOException {
        final String root = """
                package test.cfg;

                import com.swirlds.config.api.ConfigData;
                import com.swirlds.config.api.NestedConfig;

                @ConfigData("root")
                @NestedConfig
                public record RootConfig(String value) {}
                """;

        final String messages = compileExpectingFailure(root);

        assertTrue(messages.contains("mutually exclusive"), messages);
        assertTrue(messages.contains("test.cfg.RootConfig"), messages);
    }

    /**
     * The documentation the last compilation generated. The processor writes to {@code build/docs/config.md} of the
     * working directory by default, which is the real build directory of this module and is shared by every test, so
     * the compilations below direct it into the temporary directory instead.
     */
    private Path documentationFile() {
        final Path doc = tempDir.resolve("config.md");
        assertTrue(Files.exists(doc), "no documentation was generated at " + doc);
        return doc;
    }

    /**
     * Compiles the given sources with the config annotation processor and returns the generated constants class of
     * {@code RootConfig}.
     */
    private String compileAndReadConstants(final String... sources) throws IOException {
        return compileAndReadConstantsOf("RootConfig", sources);
    }

    /**
     * Compiles the given sources with the config annotation processor and returns the generated constants class of the
     * given record.
     */
    private String compileAndReadConstantsOf(final String recordName, final String... sources) throws IOException {
        final CompilationResult result = compile(sources);
        assertTrue(result.success(), "compilation of the test sources failed: " + result.messages());

        final Path constants = tempDir.resolve("out/test/cfg/" + recordName + CONSTANTS_CLASS_SUFFIX + ".java");
        assertTrue(Files.exists(constants), "no constants class was generated at " + constants);
        return Files.readString(constants, StandardCharsets.UTF_8);
    }

    /**
     * Compiles the given sources with the config annotation processor, expecting the processor to reject them, and
     * returns the messages the compilation reported.
     */
    private String compileExpectingFailure(final String... sources) throws IOException {
        final CompilationResult result = compile(sources);
        assertFalse(result.success(), "the compilation was expected to fail but succeeded");
        return result.messages();
    }

    /**
     * The outcome of one compilation.
     *
     * @param success  whether the compilation succeeded
     * @param messages everything the compilation reported, so that a rejection can be checked by its message
     */
    private record CompilationResult(boolean success, String messages) {}

    /**
     * Compiles the given sources with the config annotation processor.
     */
    private CompilationResult compile(final String... sources) throws IOException {
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

        final DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        final boolean success;
        try (final StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null)) {
            final JavaCompiler.CompilationTask task = compiler.getTask(
                    null,
                    fileManager,
                    diagnostics,
                    List.of(
                            "-d",
                            output.toString(),
                            "-s",
                            output.toString(),
                            "-sourcepath",
                            tempDir.resolve("src").toString(),
                            "-classpath",
                            path,
                            // the default is build/docs/config.md of the working directory, which is the real build
                            // directory of this module and would be shared by every test
                            "-A" + DOCUMENTATION_FILE_OPTION + "=" + tempDir.resolve("config.md")),
                    null,
                    fileManager.getJavaFileObjectsFromPaths(paths));
            // the processor is handed over as an instance so that it is not loaded a second time from the path above
            task.setProcessors(List.of(new ConfigDataAnnotationProcessor()));
            success = task.call();
        }

        final String messages = diagnostics.getDiagnostics().stream()
                .map(diagnostic -> diagnostic.getMessage(null))
                .collect(Collectors.joining(System.lineSeparator()));
        return new CompilationResult(success, messages);
    }
}
