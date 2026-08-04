// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.kbfreshness.resolve;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LineMap;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ModifiersTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import javax.lang.model.element.Modifier;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * Parse-only Java source analysis via the JDK Compiler Tree API. It parses ({@code JavacTask.parse()})
 * without compiling or attributing, so it needs no classpath, no dependencies, and no build — it just
 * reads what a file declares. That is enough for symbol-existence and as-written signature checks and
 * keeps the engine deterministic and offline.
 */
public final class JavaParsing {

    /** The declaration flavor of a type, used to scope interface method-set checks. */
    public enum Kind {
        /** A {@code class} declaration. */
        CLASS,
        /** An {@code interface} declaration. */
        INTERFACE,
        /** An {@code enum} declaration. */
        ENUM,
        /** A {@code record} declaration. */
        RECORD,
        /** An {@code @interface} (annotation) declaration. */
        ANNOTATION,
        /** Any other or unrecognized declaration flavor. */
        OTHER
    }

    /**
     * One declared method (or constructor).
     *
     * @param name       the method's simple name ({@code <init>} for a constructor).
     * @param paramTypes the parameter types exactly as written in source, in declaration order.
     * @param returnType the return type exactly as written, or {@code null} for a constructor.
     * @param line       the 1-based line of the method declaration.
     */
    public record MethodSig(String name, List<String> paramTypes, String returnType, int line) {}

    /**
     * One declared non-method member — a field, an enum constant, or a record component — as a nameable
     * symbol for {@code File.java#member} references.
     *
     * @param name the member's simple name.
     * @param line the 1-based line of the member declaration.
     */
    public record MemberDecl(String name, int line) {}

    /**
     * The {@code @ConfigProperty(defaultValue = …)} of a record component, modeled by how it can be
     * compared: a plain string {@link Literal} (a compile-time fact), a non-literal {@link Expr} constant
     * reference (compared only when whitelisted by the caller), or {@link None} when no {@code defaultValue}
     * attribute is written. The three states are mutually exclusive by construction.
     */
    public sealed interface Default {

        /** A plain string-literal default, comparable as a fact. */
        record Literal(String value) implements Default {}

        /** A non-literal default written as a constant reference (e.g. {@code Configuration.EMPTY_LIST}). */
        record Expr(String expression) implements Default {}

        /** No {@code defaultValue} attribute was written. */
        record None() implements Default {}
    }

    /**
     * One record component read as a config property, for {@code @ConfigData} record checks.
     *
     * @param keyName       the property name the config system binds — the {@code @ConfigProperty}
     *                      {@code value} attribute when written, else the component name.
     * @param componentName the record component's declared name.
     * @param type          the component's type exactly as written.
     * @param defaultSpec   the {@code @ConfigProperty} {@code defaultValue}, modeled as a literal, a
     *                      constant-reference expression, or none.
     * @param line          the 1-based line of the component declaration.
     */
    public record ConfigComponent(String keyName, String componentName, String type, Default defaultSpec, int line) {}

    /**
     * A declared type: its kind, declaration line, methods (overloads preserved), and — for
     * {@code @ConfigData} records — its config prefix and components.
     *
     * @param declLine         1-based line of the type declaration.
     * @param kind             the declaration flavor.
     * @param methods          every declared method, in source order (overloads share a name).
     * @param members          every declared field, enum constant, and record component (nameable
     *                         non-method symbols), in declaration order.
     * @param configPrefix     the {@code @ConfigData} value as written ({@code ""} for a bare
     *                         {@code @ConfigData}), or {@code null} when the type carries no
     *                         {@code @ConfigData} annotation.
     * @param configComponents the record components read as config properties, in declaration order
     *                         (empty for non-records and records without components).
     */
    public record TypeInfo(
            int declLine,
            Kind kind,
            List<MethodSig> methods,
            List<MemberDecl> members,
            String configPrefix,
            List<ConfigComponent> configComponents) {

        /**
         * Whether the type declares a method with the given name (any overload).
         *
         * @param name the method name.
         * @return true if at least one overload is declared.
         */
        public boolean hasMethod(final String name) {
            return methods.stream().anyMatch(m -> m.name().equals(name));
        }

        /**
         * The 1-based line of the first declared overload of a method.
         *
         * @param name the method name.
         * @return the first overload's line, or empty if the method is not declared.
         */
        public OptionalInt firstLine(final String name) {
            return methods.stream()
                    .filter(m -> m.name().equals(name))
                    .mapToInt(MethodSig::line)
                    .findFirst();
        }

        /**
         * Whether the type declares a field, enum constant, or record component with the given name.
         *
         * @param name the member name.
         * @return true if such a member is declared.
         */
        public boolean hasMember(final String name) {
            return members.stream().anyMatch(m -> m.name().equals(name));
        }

        /**
         * The 1-based declaration line of a named field, enum constant, or record component.
         *
         * @param name the member name.
         * @return the member's line, or empty if it is not declared.
         */
        public OptionalInt memberLine(final String name) {
            return members.stream()
                    .filter(m -> m.name().equals(name))
                    .mapToInt(MemberDecl::line)
                    .findFirst();
        }

        /**
         * Every declared overload of a method.
         *
         * @param name the method name.
         * @return the matching signatures, possibly empty.
         */
        public List<MethodSig> overloads(final String name) {
            return methods.stream().filter(m -> m.name().equals(name)).toList();
        }

        /**
         * The distinct names of all declared methods.
         *
         * @return the method names in first-seen order.
         */
        public List<String> methodNames() {
            final List<String> names = new ArrayList<>();
            for (final MethodSig m : methods) {
                if (!names.contains(m.name())) {
                    names.add(m.name());
                }
            }
            return names;
        }

        /**
         * The fully-qualified config key of a record component: the {@code @ConfigData} prefix joined to
         * the component's bound key name, or the bare name when the record carries a prefixless
         * {@code @ConfigData}. Callers must only use this on {@code @ConfigData} records (a non-config
         * type has a {@code null} prefix).
         *
         * @param keyName the component's bound key name.
         * @return the fully-qualified config key.
         */
        public String fullyQualifiedKey(final String keyName) {
            return configPrefix.isEmpty() ? keyName : configPrefix + "." + keyName;
        }
    }

    /** Declared types of a parsed file, keyed by simple type name (nested types included). */
    public record ParsedFile(Map<String, TypeInfo> types) {}

    /**
     * The name of the declaration whose start line is exactly {@code line} — a type, a method, or a
     * field/enum-constant/record component — or {@code null} when no declaration starts there. Drives the
     * {@code File.java:NN}→{@code File.java#symbol} migration: NN is a symbol only when it lands on a
     * declaration line.
     *
     * @param parsed the parsed source file.
     * @param line   the 1-based line to match.
     * @return the declared symbol's simple name, or {@code null} when {@code line} is not a declaration.
     */
    public static String symbolAtLine(final ParsedFile parsed, final int line) {
        for (final Map.Entry<String, TypeInfo> e : parsed.types().entrySet()) {
            final TypeInfo t = e.getValue();
            if (t.declLine() == line) {
                return e.getKey();
            }
            for (final MethodSig m : t.methods()) {
                if (m.line() == line) {
                    return m.name();
                }
            }
            for (final MemberDecl mem : t.members()) {
                if (mem.line() == line) {
                    return mem.name();
                }
            }
        }
        return null;
    }

    /**
     * The per-compilation-unit parse state threaded through the collectors: the unit, its source-position
     * lookup, its line-number map, and its source text. Bundled so a single value passes through
     * {@link #collectType}, {@link #configComponentOf}, {@link #signatureOf}, and {@link #declLine}
     * instead of the same four arguments.
     *
     * @param cu        the compilation unit being read.
     * @param positions source-position lookup for the unit.
     * @param lineMap   line-number lookup for the unit.
     * @param src       the unit's source text, for signature-line resolution.
     */
    private record ParseContext(CompilationUnitTree cu, SourcePositions positions, LineMap lineMap, CharSequence src) {}

    /** The system Java compiler, or {@code null} when running on a JRE without one. */
    private static final JavaCompiler COMPILER = ToolProvider.getSystemJavaCompiler();

    /** Non-instantiable utility holder. */
    private JavaParsing() {}

    /**
     * Parses a single Java source file (parse-only, no attribution) into its declared types.
     *
     * @param file the source file to parse.
     * @return the parsed file's declared types keyed by simple name.
     * @throws IllegalStateException if no system Java compiler is available.
     * @throws UncheckedIOException  if reading or parsing the file fails.
     */
    public static ParsedFile parse(final Path file) {
        if (COMPILER == null) {
            throw new IllegalStateException("No system Java compiler available (run on a JDK, not a JRE)");
        }
        try (StandardJavaFileManager fm = COMPILER.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            final Iterable<? extends JavaFileObject> units = fm.getJavaFileObjectsFromPaths(List.of(file));
            final JavacTask task =
                    (JavacTask) COMPILER.getTask(null, fm, diagnostic -> {}, List.of("-proc:none"), null, units);
            final Trees trees = Trees.instance(task);
            final SourcePositions positions = trees.getSourcePositions();
            final Map<String, TypeInfo> types = new LinkedHashMap<>();
            for (final CompilationUnitTree cu : task.parse()) {
                final ParseContext ctx = new ParseContext(
                        cu, positions, cu.getLineMap(), cu.getSourceFile().getCharContent(true));
                for (final Tree decl : cu.getTypeDecls()) {
                    collectType(ctx, decl, types);
                }
            }
            return new ParsedFile(types);
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to parse " + file, e);
        }
    }

    /**
     * Collects a type declaration (and its nested types) into the output map, recording each type's
     * kind, declaration line, and method signatures. Non-type declarations are ignored.
     *
     * @param ctx  the enclosing compilation unit's parse state.
     * @param decl the declaration to inspect.
     * @param out  the map to populate, keyed by simple type name.
     */
    private static void collectType(final ParseContext ctx, final Tree decl, final Map<String, TypeInfo> out) {
        // ClassTree covers class, interface, enum, record, and annotation declarations.
        if (!(decl instanceof ClassTree ct)) {
            return;
        }
        final String name = ct.getSimpleName().toString();
        final List<MethodSig> methods = new ArrayList<>();
        final List<MemberDecl> members = new ArrayList<>();
        final List<ConfigComponent> components = new ArrayList<>();
        final boolean isRecord = kindOf(ct) == Kind.RECORD;
        for (final Tree member : ct.getMembers()) {
            if (member instanceof MethodTree mt) {
                methods.add(signatureOf(ctx, mt));
            } else if (member instanceof ClassTree nested) {
                collectType(ctx, nested, out);
            } else if (member instanceof VariableTree vt) {
                // A field, enum constant, or record component — all nameable members for `#member` refs.
                members.add(new MemberDecl(vt.getName().toString(), declLine(ctx, vt, vt.getModifiers())));
                if (isRecord && !vt.getModifiers().getFlags().contains(Modifier.STATIC)) {
                    // A record's non-static variable members are exactly its components (the parser lowers
                    // the record header into member declarations; instance fields are illegal in records).
                    components.add(configComponentOf(ctx, vt));
                }
            }
        }
        if (!name.isEmpty()) {
            out.putIfAbsent(
                    name,
                    new TypeInfo(
                            declLine(ctx, ct, ct.getModifiers()),
                            kindOf(ct),
                            methods,
                            members,
                            annotationValue(ct.getModifiers(), "ConfigData"),
                            components));
        }
    }

    /**
     * Reads one record component as a config property: its bound key name ({@code @ConfigProperty}
     * {@code value} attribute or the component name), as-written type, and — when written as a plain
     * string literal — its {@code defaultValue}.
     *
     * @param ctx the enclosing compilation unit's parse state.
     * @param vt  the component declaration.
     * @return the component's config-property view.
     */
    private static ConfigComponent configComponentOf(final ParseContext ctx, final VariableTree vt) {
        final String componentName = vt.getName().toString();
        String keyName = componentName;
        Default defaultSpec = new Default.None();
        for (final AnnotationTree at : vt.getModifiers().getAnnotations()) {
            if (!simpleAnnotationName(at).equals("ConfigProperty")) {
                continue;
            }
            final String named = attributeLiteral(at, "value");
            if (named != null) {
                keyName = named;
            }
            final String literal = attributeLiteral(at, "defaultValue");
            if (literal != null) {
                defaultSpec = new Default.Literal(literal);
            } else {
                final String expr = attributeExpression(at, "defaultValue");
                defaultSpec = expr != null ? new Default.Expr(expr) : new Default.None();
            }
        }
        return new ConfigComponent(
                keyName,
                componentName,
                vt.getType() == null ? "" : vt.getType().toString().replaceAll("\\s+", ""),
                defaultSpec,
                declLine(ctx, vt, vt.getModifiers()));
    }

    /**
     * The string-literal value of a named annotation on the given modifiers, or {@code null} when the
     * annotation is absent. A bare annotation (no arguments) yields {@code ""}; a non-literal value
     * yields {@code ""} as well — callers needing to distinguish literals use
     * {@link #attributeLiteral(AnnotationTree, String)} directly.
     *
     * @param mods           the modifiers to scan.
     * @param annotationName the annotation's simple name.
     * @return the annotation's {@code value} literal, {@code ""} when bare/non-literal, or {@code null}
     *     when the annotation is not present.
     */
    private static String annotationValue(final ModifiersTree mods, final String annotationName) {
        for (final AnnotationTree at : mods.getAnnotations()) {
            if (simpleAnnotationName(at).equals(annotationName)) {
                final String v = attributeLiteral(at, "value");
                return v == null ? "" : v;
            }
        }
        return null;
    }

    /**
     * The string literal assigned to a named attribute of an annotation, handling both the explicit
     * ({@code name = "x"}) and the single-element shorthand ({@code @A("x")}, an implicit {@code value})
     * forms. Returns {@code null} when the attribute is absent or its expression is not a plain string
     * literal (e.g. a constant reference) — a non-literal is never compared as a fact.
     *
     * @param at   the annotation.
     * @param name the attribute name to read.
     * @return the attribute's string literal, or {@code null}.
     */
    private static String attributeLiteral(final AnnotationTree at, final String name) {
        for (final ExpressionTree arg : at.getArguments()) {
            if (arg instanceof AssignmentTree assign) {
                if (assign.getVariable().toString().equals(name)
                        && assign.getExpression() instanceof LiteralTree lit
                        && lit.getValue() instanceof String s) {
                    return s;
                }
            } else if (name.equals("value") && arg instanceof LiteralTree lit && lit.getValue() instanceof String s) {
                return s;
            }
        }
        return null;
    }

    /**
     * The as-written expression assigned to a named attribute of an annotation when it is <em>not</em> a
     * plain string literal (e.g. a constant reference like {@code Configuration.EMPTY_LIST}). Returns
     * {@code null} when the attribute is absent or is a plain literal (which
     * {@link #attributeLiteral(AnnotationTree, String)} reads instead).
     *
     * @param at   the annotation.
     * @param name the attribute name to read.
     * @return the attribute's non-literal expression as written, or {@code null}.
     */
    private static String attributeExpression(final AnnotationTree at, final String name) {
        for (final ExpressionTree arg : at.getArguments()) {
            if (arg instanceof AssignmentTree assign
                    && assign.getVariable().toString().equals(name)
                    && !(assign.getExpression() instanceof LiteralTree)) {
                return assign.getExpression().toString();
            }
        }
        return null;
    }

    /**
     * The simple name of an annotation's type (the last dot-segment of its as-written type).
     *
     * @param at the annotation.
     * @return the annotation type's simple name.
     */
    private static String simpleAnnotationName(final AnnotationTree at) {
        final String t = at.getAnnotationType().toString();
        final int dot = t.lastIndexOf('.');
        return dot >= 0 ? t.substring(dot + 1) : t;
    }

    /**
     * Builds the as-written signature of a method declaration.
     *
     * @param ctx the enclosing compilation unit's parse state.
     * @param mt  the method declaration.
     * @return the method's signature.
     */
    private static MethodSig signatureOf(final ParseContext ctx, final MethodTree mt) {
        final List<String> params = new ArrayList<>();
        for (final VariableTree p : mt.getParameters()) {
            params.add(canonicalType(p.getType().toString()));
        }
        final String returnType = mt.getReturnType() == null
                ? null
                : canonicalType(mt.getReturnType().toString());
        return new MethodSig(mt.getName().toString(), params, returnType, declLine(ctx, mt, mt.getModifiers()));
    }

    /**
     * Maps a class-tree declaration to its {@link Kind}.
     *
     * @param ct the class-tree declaration.
     * @return the corresponding kind.
     */
    private static Kind kindOf(final ClassTree ct) {
        return switch (ct.getKind()) {
            case INTERFACE -> Kind.INTERFACE;
            case ENUM -> Kind.ENUM;
            case RECORD -> Kind.RECORD;
            case ANNOTATION_TYPE -> Kind.ANNOTATION;
            case CLASS -> Kind.CLASS;
            default -> Kind.OTHER;
        };
    }

    /**
     * Resolves the 1-based line of a type or method declaration's signature — the line a reader would
     * cite as "the declaration". This is the first non-whitespace token after any leading annotations,
     * so an annotation on its own line above the signature (e.g. {@code @Override}) does not shift the
     * result. Leading Javadoc never counts: it precedes the tree's start position. When the node has no
     * annotations, this is just the node's start line.
     *
     * @param ctx  the enclosing compilation unit's parse state.
     * @param node the type or method declaration.
     * @param mods the declaration's modifiers (its annotations).
     * @return the 1-based signature line, or {@code -1} if the position is unknown.
     */
    private static int declLine(final ParseContext ctx, final Tree node, final ModifiersTree mods) {
        final long start = ctx.positions().getStartPosition(ctx.cu(), node);
        if (start < 0) {
            return -1;
        }
        long from = start;
        final List<? extends AnnotationTree> annotations = mods.getAnnotations();
        if (!annotations.isEmpty()) {
            final long lastAnnotationEnd =
                    ctx.positions().getEndPosition(ctx.cu(), annotations.get(annotations.size() - 1));
            if (lastAnnotationEnd > from) {
                from = lastAnnotationEnd;
            }
        }
        int pos = (int) from;
        final CharSequence src = ctx.src();
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
            pos++;
        }
        return (int) ctx.lineMap().getLineNumber(pos >= src.length() ? start : pos);
    }

    /**
     * Normalizes a type to its canonical as-written form for comparison: removes all whitespace and
     * strips package qualifiers from every identifier (e.g. {@code java.util.List<com.x.Foo>} to
     * {@code List<Foo>}). The single canonical form is applied both when a {@link MethodSig} is built
     * (so a stored parameter type is already canonical) and when a documented signature is compared, so
     * a doc's simple names compare equal to source's possibly-qualified ones.
     *
     * @param type the type string.
     * @return the canonical type.
     */
    public static String canonicalType(final String type) {
        final String noSpace = type.replaceAll("\\s+", "");
        return noSpace.replaceAll("(?:[A-Za-z_$][A-Za-z0-9_$]*\\.)+([A-Za-z_$][A-Za-z0-9_$]*)", "$1");
    }

    /**
     * Splits a documented parameter list on top-level commas, ignoring commas nested in generics,
     * arrays, or parentheses.
     *
     * @param paramStr the raw parameter list (without the surrounding parentheses).
     * @return the trimmed parameter pieces, or an empty list when there are none.
     */
    public static List<String> splitParams(final String paramStr) {
        final List<String> parts = new ArrayList<>();
        if (paramStr.isBlank()) {
            return parts;
        }
        int depth = 0;
        final StringBuilder cur = new StringBuilder();
        for (int i = 0; i < paramStr.length(); i++) {
            final char c = paramStr.charAt(i);
            if (c == '<' || c == '(' || c == '[') {
                depth++;
            } else if (c == '>' || c == ')' || c == ']') {
                depth--;
            }
            if (c == ',' && depth == 0) {
                parts.add(cur.toString().strip());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (!cur.isEmpty()) {
            parts.add(cur.toString().strip());
        }
        return parts;
    }

    /**
     * Drops a trailing parameter name from a documented parameter piece, keeping just the type. A piece
     * with a top-level space (e.g. {@code List<Foo> bar}) is treated as {@code type name}; a piece
     * without one (e.g. {@code byte[]}) is the type itself.
     *
     * @param piece one parameter piece.
     * @return the parameter's type portion.
     */
    public static String dropParamName(final String piece) {
        int depth = 0;
        int lastSpace = -1;
        for (int i = 0; i < piece.length(); i++) {
            final char c = piece.charAt(i);
            if (c == '<' || c == '(' || c == '[') {
                depth++;
            } else if (c == '>' || c == ')' || c == ']') {
                depth--;
            } else if (c == ' ' && depth == 0) {
                lastSpace = i;
            }
        }
        return (lastSpace >= 0 ? piece.substring(0, lastSpace) : piece).strip();
    }
}
