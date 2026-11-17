// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.processor;

import static com.swirlds.config.processor.ConfigProcessorConstants.CONSTANTS_CLASS_SUFFIX;

import com.swirlds.config.processor.antlr.AntlrConfigRecordParser;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedOptions;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic.Kind;
import javax.tools.FileObject;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;

/**
 * An annotation processor that creates documentation and constants for config data records.
 */
@SupportedAnnotationTypes(ConfigProcessorConstants.CONFIG_DATA_ANNOTATION)
@SupportedOptions(ConfigDataAnnotationProcessor.DOCUMENTATION_FILE_OPTION)
@SupportedSourceVersion(SourceVersion.RELEASE_25)
public final class ConfigDataAnnotationProcessor extends AbstractProcessor {

    /**
     * Option that defines where the documentation is written, as an absolute path. The documentation goes to
     * {@code build/docs/config.md} below the working directory of the compiler when the option is not given, which is
     * the right place for a Gradle build but not for a compilation that is driven by something else.
     */
    static final String DOCUMENTATION_FILE_OPTION = "com.swirlds.config.processor.documentationFile";

    @Override
    public boolean process(
            final @NonNull Set<? extends TypeElement> annotations, final @NonNull RoundEnvironment roundEnv) {
        Objects.requireNonNull(roundEnv, "annotations must not be null");
        Objects.requireNonNull(roundEnv, "roundEnv must not be null");

        if (annotations.isEmpty()) {
            return false;
        }
        final Path configDocumentationFile = getDocumentationFile();
        try {
            Files.deleteIfExists(configDocumentationFile);
        } catch (final IOException e) {
            throw new RuntimeException("Error while deleting " + configDocumentationFile, e);
        }
        configDocumentationFile.toFile().getParentFile().mkdirs();

        log("Config Data Annotation Processor started...");
        try {
            annotations.stream()
                    .map(annotation -> (TypeElement) annotation)
                    .flatMap(annotation -> roundEnv.getElementsAnnotatedWith(annotation).stream())
                    .filter(element -> element.getKind() == ElementKind.RECORD)
                    .filter(element -> element instanceof TypeElement)
                    .map(TypeElement.class::cast)
                    .forEach(typeElement -> handleTypeElement(typeElement, configDocumentationFile));
            return true;
        } catch (final Exception e) {
            log(Kind.ERROR, "Error while processing annotations: " + describe(e));
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Describes the given failure by its whole cause chain. The failure that a rejection really reports is wrapped by
     * the handling of the type it was found on, so the message of the outermost exception alone names the record but
     * never says what is wrong with it.
     *
     * @param failure the failure
     * @return the description of the failure
     */
    @NonNull
    private static String describe(@NonNull final Throwable failure) {
        final StringBuilder builder = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (!builder.isEmpty()) {
                builder.append(": ");
            }
            builder.append(
                    current.getMessage() != null
                            ? current.getMessage()
                            : current.getClass().getName());
        }
        return builder.toString();
    }

    /**
     * Returns the file the documentation is written to, which is {@code build/docs/config.md} below the working
     * directory unless {@link #DOCUMENTATION_FILE_OPTION} defines another one.
     *
     * @return the documentation file
     */
    @NonNull
    private Path getDocumentationFile() {
        final String configured = processingEnv.getOptions().get(DOCUMENTATION_FILE_OPTION);
        if (configured != null) {
            return Paths.get(configured);
        }
        return Paths.get(System.getProperty("user.dir"), "build/docs/config.md");
    }

    private void handleTypeElement(
            @NonNull final TypeElement typeElement, @NonNull final Path configDocumentationFile) {
        final String simpleClassName = typeElement.getSimpleName().toString();
        final String fileName = simpleClassName + ConfigProcessorConstants.JAVA_FILE_EXTENSION;

        final String packageName = processingEnv
                .getElementUtils()
                .getPackageOf(typeElement)
                .getQualifiedName()
                .toString();
        log("handling: " + fileName + " in " + packageName);
        try {
            final FileObject recordSource = getSource(fileName, packageName);
            final List<ConfigDataRecordDefinition> recordDefinitions = AntlrConfigRecordParser.parse(
                    recordSource.getCharContent(true).toString());

            // one source file can declare several config data records, so the definition of the record that is being
            // handled has to be picked by name rather than by position
            final Optional<ConfigDataRecordDefinition> parsedDefinition = recordDefinitions.stream()
                    .filter(candidate -> Objects.equals(simpleClassName, candidate.simpleClassName()))
                    .findFirst();

            if (parsedDefinition.isPresent()) {
                // the source of the record is parsed on its own, so a component that holds a nested config data object
                // declared elsewhere has to be resolved through the element model of the compiler
                final ConfigDataRecordDefinition recordDefinition = new NestedRecordExpander(
                                processingEnv.getElementUtils(), processingEnv.getTypeUtils())
                        .expand(parsedDefinition.get(), typeElement);

                final JavaFileObject constantsSourceFile =
                        getConstantSourceFile(packageName, simpleClassName, typeElement);
                log("generating config constants file: " + constantsSourceFile.getName());
                ConstantClassFactory.doWork(recordDefinition, constantsSourceFile);
                log("generating config doc file: " + configDocumentationFile.getFileName());
                DocumentationFactory.doWork(recordDefinition, configDocumentationFile);
            }
        } catch (final Exception e) {
            throw new RuntimeException("Error handling " + typeElement, e);
        }
    }

    @NonNull
    private JavaFileObject getConstantSourceFile(
            @NonNull final String packageName,
            @NonNull final String simpleClassName,
            @NonNull final TypeElement originatingElement)
            throws IOException {
        Objects.requireNonNull(packageName, "packageName must not be null");
        Objects.requireNonNull(simpleClassName, "simpleClassName must not be null");
        Objects.requireNonNull(originatingElement, "originatingElement must not be null");

        final String constantsClassName = packageName + "." + simpleClassName + CONSTANTS_CLASS_SUFFIX;
        return processingEnv.getFiler().createSourceFile(constantsClassName, originatingElement);
    }

    @NonNull
    private FileObject getSource(@NonNull final String fileName, @NonNull final String packageName) throws IOException {
        Objects.requireNonNull(fileName, "fileName must not be null");
        Objects.requireNonNull(packageName, "packageName must not be null");

        return processingEnv.getFiler().getResource(StandardLocation.SOURCE_PATH, packageName, fileName);
    }

    private void log(@NonNull final String message) {
        log(Kind.OTHER, message);
    }

    private void log(@NonNull final Kind kind, @NonNull final String message) {
        Objects.requireNonNull(message, "message must not be null");

        processingEnv
                .getMessager()
                .printMessage(kind, ConfigDataAnnotationProcessor.class.getSimpleName() + ": " + message);
    }
}
