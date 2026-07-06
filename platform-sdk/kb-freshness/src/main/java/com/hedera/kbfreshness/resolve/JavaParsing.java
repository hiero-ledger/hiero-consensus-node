// SPDX-License-Identifier: Apache-2.0
package com.hedera.kbfreshness.resolve;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.LineMap;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * Parse-only Java source analysis via the JDK Compiler Tree API. It parses ({@code JavacTask.parse()})
 * without compiling or attributing, so it needs no classpath, no dependencies, and no build — it just
 * reads what a file declares. That is enough for symbol-existence and as-written checks and keeps the
 * engine deterministic and offline.
 */
public final class JavaParsing {

    /** Declared types of a parsed file, keyed by simple type name (nested types included). */
    public record ParsedFile(Map<String, TypeInfo> types) {}

    /**
     * A declared type.
     *
     * @param declLine    1-based line of the type declaration.
     * @param methodLines method name to the 1-based line of its first declaration.
     */
    public record TypeInfo(int declLine, Map<String, Integer> methodLines) {}

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
     * declaration line and its methods' first-declaration lines. Non-type declarations are ignored.
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
        final Map<String, Integer> methods = new LinkedHashMap<>();
        for (final Tree member : ct.getMembers()) {
            if (member instanceof MethodTree mt) {
                methods.putIfAbsent(mt.getName().toString(), lineOf(cu, mt, positions, lineMap));
            } else if (member instanceof ClassTree nested) {
                collectType(cu, nested, positions, lineMap, out);
            }
        }
        if (!name.isEmpty()) {
            out.putIfAbsent(name, new TypeInfo(lineOf(cu, ct, positions, lineMap), methods));
        }
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
