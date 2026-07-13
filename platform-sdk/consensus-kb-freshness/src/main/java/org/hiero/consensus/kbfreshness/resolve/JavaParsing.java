// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.kbfreshness.resolve;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.LineMap;
import com.sun.source.tree.MethodTree;
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
     * A declared type: its kind, declaration line, and methods (overloads preserved).
     *
     * @param declLine 1-based line of the type declaration.
     * @param kind     the declaration flavor.
     * @param methods  every declared method, in source order (overloads share a name).
     */
    public record TypeInfo(int declLine, Kind kind, List<MethodSig> methods) {

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
    }

    /** Declared types of a parsed file, keyed by simple type name (nested types included). */
    public record ParsedFile(Map<String, TypeInfo> types) {}

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
                final LineMap lineMap = cu.getLineMap();
                for (final Tree decl : cu.getTypeDecls()) {
                    collectType(cu, decl, positions, lineMap, types);
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
     * @param cu        the enclosing compilation unit.
     * @param decl      the declaration to inspect.
     * @param positions source-position lookup for the unit.
     * @param lineMap   line-number lookup for the unit.
     * @param out       the map to populate, keyed by simple type name.
     */
    private static void collectType(
            final CompilationUnitTree cu,
            final Tree decl,
            final SourcePositions positions,
            final LineMap lineMap,
            final Map<String, TypeInfo> out) {
        // ClassTree covers class, interface, enum, record, and annotation declarations.
        if (!(decl instanceof ClassTree ct)) {
            return;
        }
        final String name = ct.getSimpleName().toString();
        final List<MethodSig> methods = new ArrayList<>();
        for (final Tree member : ct.getMembers()) {
            if (member instanceof MethodTree mt) {
                methods.add(signatureOf(cu, mt, positions, lineMap));
            } else if (member instanceof ClassTree nested) {
                collectType(cu, nested, positions, lineMap, out);
            }
        }
        if (!name.isEmpty()) {
            out.putIfAbsent(name, new TypeInfo(lineOf(cu, ct, positions, lineMap), kindOf(ct), methods));
        }
    }

    /**
     * Builds the as-written signature of a method declaration.
     *
     * @param cu        the enclosing compilation unit.
     * @param mt        the method declaration.
     * @param positions source-position lookup for the unit.
     * @param lineMap   line-number lookup for the unit.
     * @return the method's signature.
     */
    private static MethodSig signatureOf(
            final CompilationUnitTree cu, final MethodTree mt, final SourcePositions positions, final LineMap lineMap) {
        final List<String> params = new ArrayList<>();
        for (final VariableTree p : mt.getParameters()) {
            params.add(p.getType().toString().replaceAll("\\s+", ""));
        }
        final String returnType = mt.getReturnType() == null
                ? null
                : mt.getReturnType().toString().replaceAll("\\s+", "");
        return new MethodSig(mt.getName().toString(), params, returnType, lineOf(cu, mt, positions, lineMap));
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
     * Resolves the 1-based start line of a tree node.
     *
     * @param cu        the enclosing compilation unit.
     * @param t         the tree node.
     * @param positions source-position lookup for the unit.
     * @param lineMap   line-number lookup for the unit.
     * @return the 1-based line number, or {@code -1} if the position is unknown.
     */
    private static int lineOf(
            final CompilationUnitTree cu, final Tree t, final SourcePositions positions, final LineMap lineMap) {
        final long pos = positions.getStartPosition(cu, t);
        return pos < 0 ? -1 : (int) lineMap.getLineNumber(pos);
    }
}
