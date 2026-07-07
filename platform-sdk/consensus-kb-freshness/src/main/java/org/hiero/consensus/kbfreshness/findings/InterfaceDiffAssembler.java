// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.kbfreshness.findings;

import java.util.ArrayList;
import java.util.List;
import org.hiero.consensus.kbfreshness.extract.KbDocument;
import org.hiero.consensus.kbfreshness.model.Anchor;
import org.hiero.consensus.kbfreshness.model.AnchorKind;
import org.hiero.consensus.kbfreshness.model.EntryType;
import org.hiero.consensus.kbfreshness.model.Finding;
import org.hiero.consensus.kbfreshness.model.Lane;
import org.hiero.consensus.kbfreshness.model.Occurrence;
import org.hiero.consensus.kbfreshness.model.Outcome;
import org.hiero.consensus.kbfreshness.resolve.JavaParsing.TypeInfo;
import org.hiero.consensus.kbfreshness.resolve.SourceIndex;
import org.hiero.consensus.kbfreshness.util.Hashing;

/**
 * Tier-2 interface method-set diff. For an {@code architecture/interfaces/*} entry that declares its
 * subject unambiguously in frontmatter — {@code interface:} (a platform-sdk-relative source path) and
 * {@code methods:} (the documented method names) — it compares the documented set against the
 * interface's actually-declared methods:
 * <ul>
 *   <li>documented-but-absent → {@code assert} (a removed method still documented);</li>
 *   <li>declared-but-undocumented → {@code coverage-gap} lane, never the drift report.</li>
 * </ul>
 * Entries without the frontmatter are skipped, so loose interface prose never produces a false
 * positive — that surface is left to the semantic pass.
 */
public final class InterfaceDiffAssembler {

    /** Source index used to resolve and parse the documented interface. */
    private final SourceIndex index;

    /**
     * Creates an assembler over the given source index.
     *
     * @param index the source index for existence and parse lookups.
     */
    public InterfaceDiffAssembler(final SourceIndex index) {
        this.index = index;
    }

    /**
     * Produces interface method-set diff findings for every eligible interface entry.
     *
     * @param docs the scanned KB documents.
     * @return the diff findings (removed-method asserts and undocumented-method coverage gaps).
     */
    public List<Finding> assembleAll(final List<KbDocument> docs) {
        final List<Finding> findings = new ArrayList<>();
        for (final KbDocument doc : docs) {
            if (doc.entry().type() == EntryType.ARCHITECTURE_INTERFACE) {
                findings.addAll(diff(doc));
            }
        }
        return findings;
    }

    /**
     * Diffs one interface entry, or returns empty when it lacks the {@code interface:}/{@code methods:}
     * convention or the interface cannot be resolved.
     *
     * @param doc the interface entry.
     * @return the diff findings for the entry.
     */
    private List<Finding> diff(final KbDocument doc) {
        final String ifacePath = doc.frontmatter().scalar("interface");
        final List<String> documented = doc.frontmatter().list("methods");
        if (ifacePath == null || documented.isEmpty()) {
            return List.of();
        }
        final String repoRel = "platform-sdk/" + ifacePath.strip().replace('\\', '/');
        final String className = classNameOf(repoRel);
        final String module = moduleOf(repoRel);

        String resolvedPath = null;
        for (final String p : index.pathsForBasename(className + ".java")) {
            if (module == null || module.equals(moduleOf(p))) {
                resolvedPath = p;
                break;
            }
        }
        if (resolvedPath == null) {
            return List.of();
        }
        final TypeInfo type = index.parse(resolvedPath).types().get(className);
        if (type == null) {
            return List.of();
        }
        final List<String> declared = type.methodNames();
        final int line = doc.frontmatter().lineOf("methods");
        final List<Finding> findings = new ArrayList<>();

        // Documented-but-absent → assert.
        for (final String m : documented) {
            if (!declared.contains(m)) {
                findings.add(build(
                        doc,
                        className,
                        m,
                        Outcome.ABSENT,
                        Lane.ASSERT,
                        "Interface `" + className + "` in `" + resolvedPath + "` no longer declares documented method `"
                                + m + "`.",
                        line));
            }
        }
        // Declared-but-undocumented → coverage lane (never the drift report).
        for (final String d : declared) {
            if (!documented.contains(d)) {
                findings.add(build(
                        doc,
                        className,
                        d,
                        Outcome.PRESENT,
                        Lane.COVERAGE_GAP,
                        "Interface `" + className + "` declares method `" + d + "` not documented in this entry.",
                        line));
            }
        }
        return findings;
    }

    /**
     * Builds one interface-method finding.
     *
     * @param doc       the interface entry.
     * @param className the interface's simple name (the cited scope).
     * @param method    the method name (the target).
     * @param outcome   the outcome.
     * @param lane      the lane.
     * @param evidence  the one-look justification.
     * @param docLine   the frontmatter line to record as the occurrence.
     * @return the finding.
     */
    private static Finding build(
            final KbDocument doc,
            final String className,
            final String method,
            final Outcome outcome,
            final Lane lane,
            final String evidence,
            final int docLine) {
        final String id = Hashing.id(doc.entry().key(), method, AnchorKind.INTERFACE_METHOD.name());
        return new Finding(
                id,
                doc.entry().key(),
                doc.entry().relativePath(),
                doc.entry().type(),
                AnchorKind.INTERFACE_METHOD,
                method,
                null,
                className,
                outcome,
                lane,
                "interface `" + className + "` declares method `" + method + "`",
                evidence,
                List.of(new Occurrence(docLine, Anchor.NO_LINE, method)),
                null);
    }

    /**
     * The module segment of a repo-relative source path (the segment before {@code src}).
     *
     * @param repoRelPath a repo-relative source path.
     * @return the module name, or {@code null} if none.
     */
    private static String moduleOf(final String repoRelPath) {
        final String[] parts = repoRelPath.split("/");
        for (int i = 1; i < parts.length; i++) {
            if (parts[i].equals("src")) {
                return parts[i - 1];
            }
        }
        return null;
    }

    /**
     * The simple class name implied by a source path's file name.
     *
     * @param path a source path.
     * @return the class name (file name without extension).
     */
    private static String classNameOf(final String path) {
        final int slash = path.lastIndexOf('/');
        final String name = slash >= 0 ? path.substring(slash + 1) : path;
        final int dot = name.indexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
