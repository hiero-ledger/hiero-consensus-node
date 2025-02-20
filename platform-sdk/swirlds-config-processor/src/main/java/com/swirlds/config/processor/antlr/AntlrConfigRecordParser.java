// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.processor.antlr;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import com.swirlds.config.processor.ConfigDataPropertyDefinition;
import com.swirlds.config.processor.ConfigDataRecordDefinition;
import com.swirlds.config.processor.antlr.generated.JavaParser.AnnotationContext;
import com.swirlds.config.processor.antlr.generated.JavaParser.CompilationUnitContext;
import com.swirlds.config.processor.antlr.generated.JavaParser.RecordComponentContext;
import com.swirlds.config.processor.antlr.generated.JavaParser.RecordDeclarationContext;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.antlr.v4.runtime.RuleContext;
import org.antlr.v4.runtime.tree.ParseTree;

public final class AntlrConfigRecordParser {

    /**
     * Property name for:
     * {@link ConfigProperty#defaultValue()}
     */
    private static final String DEFAULT_VALUE = "defaultValue";
    /**
     * Property name for:
     * {@link ConfigProperty#value()}}
     */
    private static final String VALUE = "value";

    private static boolean isAnnotatedWith(
            @NonNull final RecordDeclarationContext ctx,
            @NonNull String packageName,
            @NonNull List<String> imports,
            @NonNull final Class<? extends Annotation> annotation) {
        final List<AnnotationContext> allAnnotations = AntlrUtils.getAllAnnotations(ctx);
        return AntlrUtils.findAnnotationOfType(annotation, allAnnotations, packageName, imports)
                .isPresent();
    }

    @NonNull
    private static Optional<AnnotationContext> getAnnotation(
            @NonNull final List<AnnotationContext> annotations,
            @NonNull final String packageName,
            @NonNull final List<String> imports,
            @NonNull final Class<? extends Annotation> annotation) {
        return AntlrUtils.findAnnotationOfType(annotation, annotations, packageName, imports);
    }

    @NonNull
    private static String getAnnotationValue(
            @NonNull final RecordDeclarationContext ctx,
            @NonNull final String packageName,
            @NonNull final List<String> imports,
            @NonNull final Class<? extends Annotation> annotation) {
        final List<AnnotationContext> annotations = AntlrUtils.getAllAnnotations(ctx);
        return getAnnotation(annotations, packageName, imports, annotation)
                .map(AnnotationContext::elementValue)
                .map(RuleContext::getText)
                .map(text -> text.substring(1, text.length() - 1)) // remove quotes
                .orElse("");
    }

    @NonNull
    private static String getAnnotationPropertyOrElse(
            @NonNull final RecordComponentContext ctx,
            @NonNull final String packageName,
            @NonNull final List<String> imports,
            @NonNull final Class<? extends Annotation> annotation,
            @NonNull final String property,
            @NonNull final String orElseValue) {
        final List<AnnotationContext> allAnnotations = AntlrUtils.getAllAnnotations(ctx);
        return getAnnotation(allAnnotations, packageName, imports, annotation)
                .flatMap(annotationContext -> AntlrUtils.getAnnotationValue(annotationContext, property))
                .orElse(orElseValue);
    }

    @NonNull
    private static ConfigDataPropertyDefinition createDefinitionFromConfigProperty(
            @NonNull final RecordComponentContext ctx,
            @NonNull final String configPropertyNamePrefix,
            @NonNull final String packageName,
            @NonNull final List<String> imports,
            @NonNull final Map<String, String> javadocParams) {
        final String componentName = ctx.identifier().getText();
        String name = "not-yet-known";
        try {
            final String configPropertyNameSuffix =
                    getAnnotationPropertyOrElse(ctx, packageName, imports, ConfigProperty.class, VALUE, componentName);
            name = createPropertyName(configPropertyNamePrefix, configPropertyNameSuffix);
            final String defaultValue = getAnnotationPropertyOrElse(
                    ctx,
                    packageName,
                    imports,
                    ConfigProperty.class,
                    DEFAULT_VALUE,
                    ConfigProperty.UNDEFINED_DEFAULT_VALUE);
            final String type = Optional.ofNullable(ctx.typeType().classOrInterfaceType())
                    .map(RuleContext::getText)
                    .map(typeText -> imports.stream()
                            .filter(importText -> importText.endsWith(typeText))
                            .findAny()
                            .orElse(typeText))
                    .map(AntlrConfigRecordParser::getTypeForJavaLang)
                    .orElseGet(() -> ctx.typeType().primitiveType().getText());
            final String description =
                    Optional.ofNullable(javadocParams.get(componentName)).orElse("");

            return new ConfigDataPropertyDefinition(componentName, name, type, defaultValue, description);
        } catch (Exception e) {
            throw new IllegalArgumentException(ConfigProperty.class.getTypeName() + " is not correctly defined for "
                    + componentName + " property");
        }
    }

    @NonNull
    private static String createPropertyName(
            @NonNull final String configPropertyNamePrefix, @NonNull final String configPropertyNameSuffix) {
        if (configPropertyNamePrefix.isBlank()) {
            return configPropertyNameSuffix;
        } else {
            return configPropertyNamePrefix + "." + configPropertyNameSuffix;
        }
    }

    @NonNull
    private static String getTypeForJavaLang(@NonNull final String type) {
        if (!type.contains(".")) {
            return String.class.getPackageName() + "." + type;
        }
        return type;
    }

    @NonNull
    private static List<ConfigDataRecordDefinition> createDefinitions(
            @NonNull final CompilationUnitContext unitContext) {
        final String packageName = AntlrUtils.getPackage(unitContext);
        final List<String> imports = AntlrUtils.getImports(unitContext);
        return AntlrUtils.getRecordDeclarationContext(unitContext).stream()
                .filter(c -> isAnnotatedWith(c, packageName, imports, ConfigData.class))
                .map(recordContext -> createDefinition(unitContext, recordContext, packageName, imports))
                .collect(Collectors.toList());
    }

    @NonNull
    private static ConfigDataRecordDefinition createDefinition(
            @NonNull final CompilationUnitContext unitContext,
            @NonNull final RecordDeclarationContext recordContext,
            @NonNull final String packageName,
            @NonNull final List<String> imports) {
        final String recordName = recordContext.identifier().getText();

        try {
            final String configPropertyNamePrefix =
                    getAnnotationValue(recordContext, packageName, imports, ConfigData.class);
            final Map<String, String> javadocParams = unitContext.children.stream()
                    .filter(AntlrUtils::isJavaDocNode)
                    .map(ParseTree::getText)
                    .map(AntlrUtils::getJavaDocParams)
                    .reduce((m1, m2) -> {
                        m1.putAll(m2);
                        return m1;
                    })
                    .orElse(Map.of());
            final Set<ConfigDataPropertyDefinition> propertyDefinitions =
                    recordContext.recordHeader().recordComponentList().recordComponent().stream()
                            .map(c -> createDefinitionFromConfigProperty(
                                    c, configPropertyNamePrefix, packageName, imports, javadocParams))
                            .collect(Collectors.toSet());
            return new ConfigDataRecordDefinition(
                    packageName, recordName, configPropertyNamePrefix, propertyDefinitions);
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not process " + packageName + "." + recordName, e);
        }
    }

    /**
     * Creates a list of {@link ConfigDataRecordDefinition} from a given Java source file.
     *
     * @param fileContent the content of the Java source file
     */
    @NonNull
    public static List<ConfigDataRecordDefinition> parse(@NonNull final String fileContent) {
        final CompilationUnitContext parsedContext = AntlrUtils.parse(fileContent);
        return createDefinitions(parsedContext);
    }
}
