// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.kbfreshness.render;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.hiero.consensus.kbfreshness.model.Finding;
import org.hiero.consensus.kbfreshness.model.Occurrence;
import org.hiero.consensus.kbfreshness.util.Json;

/**
 * Renders the machine-readable findings artifact. It contains only reproducible fields — no dates, no
 * triage — so the same checkout run twice yields byte-identical output, which the baseline diff and any
 * future automation depend on.
 */
public final class FindingsJson {

    /** The schema identifier written into the artifact. */
    public static final String SCHEMA = "kb-freshness/findings/v1";

    /** Prevents instantiation of this static-only renderer. */
    private FindingsJson() {}

    /**
     * Renders the findings to the machine-readable JSON artifact.
     *
     * @param findings the findings to serialize.
     * @return the JSON artifact text.
     */
    public static String render(final List<Finding> findings) {
        final List<Object> items = new ArrayList<>();
        for (final Finding f : findings) {
            items.add(toMap(f));
        }
        final Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema", SCHEMA);
        root.put("findings", items);
        return Json.write(root);
    }

    /**
     * Converts a finding to its ordered map representation for serialization.
     *
     * @param f the finding.
     * @return the map of reproducible fields.
     */
    private static Map<String, Object> toMap(final Finding f) {
        final Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("key", f.entryKey());
        entry.put("path", f.entryPath());
        entry.put("type", wire(f.entryType().name()));

        final Map<String, Object> anchor = new LinkedHashMap<>();
        anchor.put("kind", wire(f.kind().name()));
        anchor.put("target", f.target());
        anchor.put("citedModule", f.citedModule());
        anchor.put("citedScope", f.citedScope());

        final List<Object> occurrences = new ArrayList<>();
        for (final Occurrence o : f.occurrences()) {
            final Map<String, Object> om = new LinkedHashMap<>();
            om.put("docLine", o.docLine());
            om.put("citedLine", o.citedLine());
            om.put("rawText", o.rawText());
            occurrences.add(om);
        }

        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", f.id());
        m.put("entry", entry);
        m.put("anchor", anchor);
        m.put("outcome", wire(f.outcome().name()));
        m.put("lane", wire(f.lane().name()));
        m.put("question", f.question());
        m.put("evidence", f.evidence());
        m.put("occurrences", occurrences);
        if (f.autoFixLine() != null) {
            m.put("autoFixLine", f.autoFixLine());
        }
        if (f.autoFixSymbol() != null) {
            m.put("autoFixSymbol", f.autoFixSymbol());
        }
        if (f.resolvedPath() != null) {
            m.put("resolvedPath", f.resolvedPath());
        }
        return m;
    }

    /**
     * Converts an enum constant name to its lowercase, hyphenated wire form.
     *
     * @param enumName the enum constant name.
     * @return the wire form (lowercased, underscores replaced with hyphens).
     */
    private static String wire(final String enumName) {
        return enumName.toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
