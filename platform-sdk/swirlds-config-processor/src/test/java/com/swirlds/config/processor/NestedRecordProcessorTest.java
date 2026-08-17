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
     * The runtime decides whether a component holds a group of properties from
     * {@code RecordComponent#getType()}, which is the erasure, so the processor has to erase as well. A type variable
     * would otherwise be taken for a single property here while the runtime reads the properties of its bound.
     */
    @Test
    void componentWhoseTypeIsATypeVariableIsExpanded() throws IOException {
        final String root = """
                package test.cfg;

                import com.swirlds.config.api.ConfigData;

                @ConfigData("root")
                public record RootConfig<T extends LeafConfig>(T leaf) {}
                """;

        final String generated = compileAndReadConstants(root, LEAF);

        // the erasure of T is LeafConfig, which is what the runtime expands
        assertTrue(generated.contains("\"root.leaf.type\""), generated);
        assertTrue(generated.contains("\"root.leaf.capacity\""), generated);

        // and the component itself is not a settable property, so a constant naming it would name one nothing reads
        assertFalse(generated.contains("\"root.leaf\";"), generated);
    }

    /**
     * The components of a nested config data object are read from its declaration, so a generic one would document a
     * bare type variable. The erasure is what the runtime converts the value to and is therefore documented instead.
     */
    @Test
    void typeVariableOfAGenericNestedRecordIsDocumentedAsItsErasure() throws IOException {
        final String root = """
                package test.cfg;

                import com.swirlds.config.api.ConfigData;

                @ConfigData("root")
                public record RootConfig(WrapperConfig timeout) {}
                """;
        final String wrapper = """
                package test.cfg;

                import com.swirlds.config.api.ConfigProperty;
                import com.swirlds.config.api.NestedConfig;
                import java.time.Duration;

                @NestedConfig
                public record WrapperConfig<T extends Duration>(
                        @ConfigProperty(defaultValue = "1s") T value) {}
                """;

        compileAndReadConstants(root, wrapper);

        final String documentation = Files.readString(documentationFile(), StandardCharsets.UTF_8);
        assertTrue(
                documentation.split("## root\\.timeout\\.value")[1].contains("java.time.Duration"),
                "the erasure of the type variable has to be documented: " + documentation);
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
     * A dot both separates the segments of the path to a more deeply nested property and can be part of a single name
     * that {@code @ConfigProperty} defines. The processor documents these defaults, so it has to resolve the path the
     * same way the runtime does.
     */
    @Test
    void defaultIsAppliedToTheLeafWhoseNameContainsADot() throws IOException {
        final String root = """
                package test.cfg;

                import com.swirlds.config.api.ConfigData;
                import com.swirlds.config.api.ConfigDefault;

                @ConfigData("root")
                public record RootConfig(
                        @ConfigDefault(property = "foo.bar", defaultValue = "fromSite")
                        DottedLeafConfig leaf) {}
                """;

        compileAndReadConstants(root, DOTTED_LEAF);

        final String documentation = Files.readString(documentationFile(), StandardCharsets.UTF_8);
        assertTrue(
                documentation.split("## root\\.leaf\\.foo\\.bar")[1].contains("`fromSite`"),
                "the ConfigDefault has to be resolved against the name containing the dot: " + documentation);
    }

    @Test
    void ambiguousDefaultPathIsRejected() throws IOException {
        final String root = """
                package test.cfg;

                import com.swirlds.config.api.ConfigData;
                import com.swirlds.config.api.ConfigDefault;

                @ConfigData("root")
                public record RootConfig(
                        @ConfigDefault(property = "foo.bar", defaultValue = "fromSite")
                        AmbiguousConfig ambiguous) {}
                """;

        final String ambiguous = """
                package test.cfg;

                import com.swirlds.config.api.ConfigProperty;
                import com.swirlds.config.api.NestedConfig;

                @NestedConfig
                public record AmbiguousConfig(
                        @ConfigProperty(value = "foo.bar") String flat,
                        @ConfigProperty(value = "foo") DottedInnerConfig nested) {}
                """;

        final String inner = """
                package test.cfg;

                import com.swirlds.config.api.ConfigProperty;
                import com.swirlds.config.api.NestedConfig;

                @NestedConfig
                public record DottedInnerConfig(@ConfigProperty(value = "bar") String bar) {}
                """;

        final String messages = compileExpectingFailure(root, ambiguous, inner);

        assertTrue(messages.contains("matches more than one property"), messages);
    }

    @Test
    void duplicateDefaultForOnePropertyIsRejected() throws IOException {
        final String root = """
                package test.cfg;

                import com.swirlds.config.api.ConfigData;
                import com.swirlds.config.api.ConfigDefault;

                @ConfigData("root")
                public record RootConfig(
                        @ConfigDefault(property = "capacity", defaultValue = "1")
                        @ConfigDefault(property = "capacity", defaultValue = "2")
                        LeafConfig leaf) {}
                """;

        // one of the two values is simply dropped, and which one it is must not be something the runtime and the
        // generated documentation can disagree about
        final String messages = compileExpectingFailure(root, LEAF);

        assertTrue(messages.contains("more than one ConfigDefault for the property 'root.leaf.capacity'"), messages);
    }

    @Test
    void undefinedDefaultValueMarkerIsRejected() throws IOException {
        final String root = """
                package test.cfg;

                import com.swirlds.config.api.ConfigData;
                import com.swirlds.config.api.ConfigDefault;
                import com.swirlds.config.api.ConfigProperty;

                @ConfigData("root")
                public record RootConfig(
                        @ConfigDefault(property = "capacity", defaultValue = ConfigProperty.UNDEFINED_DEFAULT_VALUE)
                        LeafConfig leaf) {}
                """;

        // the marker means "no default is defined", so documenting it as the default of the property would be wrong and
        // the runtime rejects it as well
        final String messages = compileExpectingFailure(root, LEAF);

        assertTrue(messages.contains("root.leaf.capacity"), messages);
        assertTrue(messages.contains("UNDEFINED_DEFAULT_VALUE"), messages);
    }

    @Test
    void defaultOnAComponentThatIsNotANestedRecordIsRejected() throws IOException {
        final String root = """
                package test.cfg;

                import com.swirlds.config.api.ConfigData;
                import com.swirlds.config.api.ConfigDefault;
                import com.swirlds.config.api.ConfigProperty;

                @ConfigData("root")
                public record RootConfig(
                        @ConfigDefault(property = "value", defaultValue = "1")
                        @ConfigProperty(defaultValue = "x")
                        String value) {}
                """;

        final String messages = compileExpectingFailure(root);

        assertTrue(messages.contains("is not a nested config data object"), messages);
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
