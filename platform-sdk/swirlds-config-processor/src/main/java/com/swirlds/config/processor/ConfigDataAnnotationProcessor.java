// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.processor;

import static com.swirlds.config.processor.ConfigProcessorConstants.CONSTANTS_CLASS_SUFFIX;

import com.google.auto.service.AutoService;
import com.swirlds.config.processor.antlr.AntlrConfigRecordParser;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
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
@SupportedSourceVersion(SourceVersion.RELEASE_21)
@AutoService(Processor.class)
public final class ConfigDataAnnotationProcessor extends AbstractProcessor {

    @Override
    public boolean process(
            final @NonNull Set<? extends TypeElement> annotations, final @NonNull RoundEnvironment roundEnv) {
        Objects.requireNonNull(roundEnv, "annotations must not be null");
        Objects.requireNonNull(roundEnv, "roundEnv must not be null");

        if (annotations.isEmpty()) {
            return false;
        }
        final String executionPath = System.getProperty("user.dir");
        final Path configDocumentationFile = Paths.get(executionPath, "build/docs/config.md");
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
            log(Kind.ERROR, "Error while processing annotations: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
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

            if (!recordDefinitions.isEmpty()) {
                final JavaFileObject constantsSourceFile =
                        getConstantSourceFile(packageName, simpleClassName, typeElement);
                log("generating config constants file: " + constantsSourceFile.getName());
                ConstantClassFactory.doWork(recordDefinitions.getFirst(), constantsSourceFile);
                log("generating config doc file: " + configDocumentationFile.getFileName());
                DocumentationFactory.doWork(recordDefinitions.getFirst(), configDocumentationFile);
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
