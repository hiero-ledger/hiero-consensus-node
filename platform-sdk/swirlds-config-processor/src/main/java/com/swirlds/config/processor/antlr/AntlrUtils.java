// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.processor.antlr;

import com.swirlds.config.processor.ConfigDataRecordDefinition;
import com.swirlds.config.processor.antlr.generated.JavaLexer;
import com.swirlds.config.processor.antlr.generated.JavaParser;
import com.swirlds.config.processor.antlr.generated.JavaParser.AnnotationContext;
import com.swirlds.config.processor.antlr.generated.JavaParser.ClassOrInterfaceModifierContext;
import com.swirlds.config.processor.antlr.generated.JavaParser.CompilationUnitContext;
import com.swirlds.config.processor.antlr.generated.JavaParser.ElementValueContext;
import com.swirlds.config.processor.antlr.generated.JavaParser.ImportDeclarationContext;
import com.swirlds.config.processor.antlr.generated.JavaParser.RecordComponentContext;
import com.swirlds.config.processor.antlr.generated.JavaParser.RecordDeclarationContext;
import com.swirlds.config.processor.antlr.generated.JavaParser.TypeDeclarationContext;
import com.swirlds.config.processor.antlr.generated.JavadocLexer;
import com.swirlds.config.processor.antlr.generated.JavadocParser;
import com.swirlds.config.processor.antlr.generated.JavadocParser.BlockTagContext;
import com.swirlds.config.processor.antlr.generated.JavadocParser.DocumentationContentContext;
import com.swirlds.config.processor.antlr.generated.JavadocParser.DocumentationContext;
import com.swirlds.config.processor.antlr.generated.JavadocParser.TagSectionContext;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.lang.annotation.Annotation;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Lexer;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.RuleContext;
import org.antlr.v4.runtime.TokenStream;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;

/**
 * Utils for antlr4 parsing of Java source code
 */
public final class AntlrUtils {

    public static final String JAVADOC_PARAM = "param";
    public static final String ANNOTATION_VALUE_PROPERTY_NAME = "value";

    private AntlrUtils() {}

    /**
     * Get all annotations for a given {@code record} declaration context
     *
     * @param ctx the antlr context of the {@code record}
     * @return all annotations as antlr context instances
     */
    @NonNull
    public static List<AnnotationContext> getAllAnnotations(@NonNull final RecordDeclarationContext ctx) {
        Objects.requireNonNull(ctx, "ctx must not be null");
        return ctx.getParent().children.stream()
                .filter(child -> child instanceof ClassOrInterfaceModifierContext)
                .map(child -> (ClassOrInterfaceModifierContext) child)
                .flatMap(modifierContext -> modifierContext.children.stream())
                .filter(child -> child instanceof AnnotationContext)
                .map(child -> (AnnotationContext) child)
                .collect(Collectors.toList());
    }

    /**
     * Get all annotations for a given {@code record} component context
     *
     * @param ctx the antlr context of the {@code record} component
     * @return all annotations as antlr context instances
     */
    @NonNull
    public static List<AnnotationContext> getAllAnnotations(@NonNull RecordComponentContext ctx) {
        return Collections.unmodifiableList(ctx.typeType().annotation());
    }

    /**
     * Search in the given annotation context for an optional annotation context for a given {@code Annotation}.
     *
     * @param <A>         the annotation type
     * @param annotation  the annotation class
     * @param annotations all possible annotations
     * @param packageName the package name of the context in that all contexts life in
     * @param imports     the imports of the context in that all contexts life in
     * @return the optional annotation
     */
    @NonNull
    public static <A extends Annotation> Optional<AnnotationContext> findAnnotationOfType(
            @NonNull final Class<A> annotation,
            @NonNull final List<AnnotationContext> annotations,
            @NonNull final String packageName,
            @NonNull final List<String> imports) {
        Objects.requireNonNull(annotation, "annotation must not be null");
        Objects.requireNonNull(annotations, "annotations must not be null");
        return annotations.stream()
                .filter(c -> c.qualifiedName().getText().endsWith(annotation.getSimpleName()))
                .filter(c -> isValid(c, annotation, packageName, imports))
                .findAny();
    }

    /**
     * Checks if the annotationContext is a valid usage of the annotation.
     *
     * @param annotationContext the annotation context
     * @param annotation        the annotation class
     * @param packageName       the package name of the context in that all contexts life in
     * @param imports           the imports of the context in that all contexts life in
     * @param <A>               the annotation type
     * @return true if the annotation is valid
     */
    private static <A extends Annotation> boolean isValid(
            @NonNull final AnnotationContext annotationContext,
            @NonNull final Class<A> annotation,
            @NonNull final String packageName,
            @NonNull final List<String> imports) {
        Objects.requireNonNull(annotationContext, "annotationContext must not be null");
        Objects.requireNonNull(annotation, "annotation must not be null");
        Objects.requireNonNull(packageName, "packageName must not be null");
        Objects.requireNonNull(imports, "imports must not be null");
        return imports.contains(annotation.getName())
                || annotationContext.qualifiedName().getText().equals(annotation.getName())
                || annotationContext.qualifiedName().getText().equals(packageName + "." + annotation.getSimpleName());
    }

    /**
     * Returns the compilation unit context for a given antlr context by doing a (recursive) search in the parent
     * contexts.
     *
     * @param ctx the antlr context
     * @return the compilation unit context
     */
    @NonNull
    public static CompilationUnitContext getCompilationUnit(@NonNull final ParserRuleContext ctx) {
        Objects.requireNonNull(ctx, "ctx must not be null");
        if (ctx instanceof CompilationUnitContext compilationUnitContext) {
            return compilationUnitContext;
        } else {
            return getCompilationUnit(ctx.getParent());
        }
    }

    /**
     * Returns all imports of a given declaration context (by going up to the compilation unit context)
     *
     * @param ctx the antlr contexts
     * @return all imports as strings
     */
    @NonNull
    public static List<String> getImports(@NonNull final ParserRuleContext ctx) {
        final CompilationUnitContext compilationUnitContext = getCompilationUnit(ctx);
        return compilationUnitContext.importDeclaration().stream()
                .map(ImportDeclarationContext::qualifiedName)
                .map(RuleContext::getText)
                .collect(Collectors.toList());
    }

    /**
     * Returns the package name of a given declaration context (by going up to the compilation unit context)
     *
     * @param ctx the antlr context
     * @return the package name
     */
    @NonNull
    public static String getPackage(@NonNull final ParserRuleContext ctx) {
        final CompilationUnitContext compilationUnitContext = getCompilationUnit(ctx);
        return compilationUnitContext.packageDeclaration().qualifiedName().getText();
    }

    /**
     * Returns the value of an annotation attribute
     *
     * @param annotationContext the annotation context
     * @param identifier        the identifier of the attribute
     * @return the value of the attribute
     */
    @NonNull
    public static Optional<String> getAnnotationValue(
            @NonNull final AnnotationContext annotationContext, @NonNull final String identifier) {
        if (ANNOTATION_VALUE_PROPERTY_NAME.equals(identifier)) {
            final ElementValueContext elementValueContext = annotationContext.elementValue();
            if (elementValueContext != null) {
                return Optional.of(elementValueContext.getText()).map(text -> {
                    if (text.startsWith("\"") && text.endsWith("\"")) {
                        return text.substring(1, text.length() - 1);
                    }
                    return text;
                });
            }
        }
        if (annotationContext.elementValuePairs() == null) {
            return Optional.empty();
        }
        return annotationContext.elementValuePairs().elementValuePair().stream()
                .filter(p -> Objects.equals(p.identifier().getText(), identifier))
                .map(p -> p.elementValue().getText())
                .map(text -> {
                    if (text.startsWith("\"") && text.endsWith("\"")) {
                        return text.substring(1, text.length() - 1);
                    }
                    return text;
                })
                .findAny();
    }

    public static boolean isJavaDocNode(@NonNull final ParseTree node) {
        if (node instanceof TerminalNode terminalNode) {
            return terminalNode.getSymbol().getType() == JavaParser.JAVADOC_COMMENT;
        }
        return false;
    }

    public static List<RecordDeclarationContext> getRecordDeclarationContext(
            @NonNull final CompilationUnitContext compilationUnitContext) {
        return compilationUnitContext.children.stream()
                .filter(child -> child instanceof TypeDeclarationContext)
                .map(child -> (TypeDeclarationContext) child)
                .flatMap(typeDeclarationContext -> typeDeclarationContext.children.stream())
                .filter(child -> child instanceof RecordDeclarationContext)
                .map(child -> (RecordDeclarationContext) child)
                .collect(Collectors.toList());
    }

    /**
     * Returns all {@code @param} tags of a given java doc. The key of the map is the name of the parameter and the
     * value is the description of the parameter.
     *
     * @param rawDocContent the javadoc
     * @return the params
     */
    @NonNull
    public static Map<String, String> getJavaDocParams(@NonNull String rawDocContent) {
        Objects.requireNonNull(rawDocContent, "rawDocContent must not be null");
        final Map<String, String> params = new HashMap<>();
        final Lexer lexer = new JavadocLexer(CharStreams.fromString(rawDocContent));
        final TokenStream tokens = new CommonTokenStream(lexer);
        final JavadocParser parser = new JavadocParser(tokens);
        final DocumentationContext documentationContext = parser.documentation();
        Optional.ofNullable(documentationContext.exception).ifPresent(e -> {
            throw new IllegalStateException("Error in ANTLR parsing", e);
        });
        documentationContext.children.stream()
                .filter(c -> c instanceof DocumentationContentContext)
                .map(c -> (DocumentationContentContext) c)
                .flatMap(context -> context.children.stream())
                .filter(c -> c instanceof TagSectionContext)
                .map(c -> (TagSectionContext) c)
                .flatMap(context -> context.children.stream())
                .filter(c -> c instanceof BlockTagContext)
                .map(c -> (BlockTagContext) c)
                .filter(c -> Objects.equals(c.blockTagName().NAME().getText(), JAVADOC_PARAM))
                .map(AntlrUtils::extractFullText)
                .filter(fullText -> !fullText.isBlank())
                .filter(fullText -> fullText.contains(" "))
                .forEach(fullText -> {
                    final String paramName = fullText.split(" ")[0].trim();
                    final String description =
                            fullText.substring(paramName.length()).trim();
                    params.put(paramName.trim(), description.trim());
                });
        return params;
    }

    private static String extractFullText(@NonNull final BlockTagContext c) {
        final String[] split = c.getText().trim().split("\n \\*");
        final String result = Arrays.stream(split)
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .reduce((a, b) -> a + " " + b)
                .orElse("");
        if (result.startsWith("@param")) {
            return result.substring("@param".length()).trim();
        }
        return result.trim();
    }

    /**
     * Parse the given file content and return a {@link ConfigDataRecordDefinition} object. The file must be a valid
     * Java file.
     *
     * @param fileContent the file content to parse
     * @return the {@link ConfigDataRecordDefinition} object
     */
    public static CompilationUnitContext parse(@NonNull final String fileContent) {
        final Lexer lexer = new JavaLexer(CharStreams.fromString(fileContent));
        final TokenStream tokens = new CommonTokenStream(lexer);
        final JavaParser parser = new JavaParser(tokens);
        final CompilationUnitContext context = parser.compilationUnit();
        Optional.ofNullable(context.exception).ifPresent(e -> {
            throw new IllegalStateException("Error in ANTLR parsing", e);
        });
        return context;
    }
}
