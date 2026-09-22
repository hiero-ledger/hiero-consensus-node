// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.kbfreshness.findings;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.hiero.consensus.kbfreshness.model.Triage;

/**
 * The human-owned, version-controlled record of prior findings and their triage. Stored as a simple,
 * diff-friendly TSV: {@code id<TAB>triage<TAB>first_seen<TAB>note}, one finding per line, {@code #}
 * comments and blank lines ignored. Kept separate from the machine findings artifact so the artifact
 * stays byte-reproducible while triage and dates live here.
 */
public final class Baseline {

    /** The comment header written as the first line of the canonical TSV. */
    public static final String HEADER = "# kb-freshness baseline v1 — id<TAB>triage<TAB>first_seen<TAB>note";

    /** Baseline entries keyed by finding id, in insertion order. */
    private final Map<String, BaselineEntry> byId;

    /**
     * Creates a baseline over the given entry map.
     *
     * @param byId the entries keyed by finding id.
     */
    private Baseline(final Map<String, BaselineEntry> byId) {
        this.byId = byId;
    }

    /**
     * Returns an empty baseline.
     *
     * @return a baseline with no entries.
     */
    public static Baseline empty() {
        return new Baseline(new LinkedHashMap<>());
    }

    /**
     * Loads a baseline from a TSV file, ignoring comments and blank lines. A {@code null} or missing file
     * yields an empty baseline.
     *
     * @param file the baseline TSV file, or {@code null}.
     * @return the loaded baseline.
     */
    public static Baseline load(final Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return empty();
        }
        final Map<String, BaselineEntry> map = new LinkedHashMap<>();
        try {
            for (final String raw : Files.readAllLines(file)) {
                final String line = raw.strip();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                final String[] parts = raw.split("\t", -1);
                final String id = parts[0].strip();
                if (id.isEmpty()) {
                    continue;
                }
                final Triage triage = Triage.fromString(parts.length > 1 ? parts[1] : "");
                final String firstSeen = parts.length > 2 ? parts[2].strip() : "";
                final String note = parts.length > 3 ? parts[3].strip() : "";
                map.put(id, new BaselineEntry(id, triage, firstSeen, note));
            }
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to read baseline " + file, e);
        }
        return new Baseline(map);
    }

    /**
     * Returns the baseline entry for a finding id.
     *
     * @param id the finding id.
     * @return the entry, or {@code null} if absent.
     */
    public BaselineEntry get(final String id) {
        return byId.get(id);
    }

    /**
     * Reports whether the baseline contains an entry for a finding id.
     *
     * @param id the finding id.
     * @return true if the id is present.
     */
    public boolean contains(final String id) {
        return byId.containsKey(id);
    }

    /**
     * Returns all baseline entries in insertion order.
     *
     * @return a copy of the entries.
     */
    public List<BaselineEntry> entries() {
        return new ArrayList<>(byId.values());
    }

    /**
     * Renders a baseline (sorted by id) to the canonical TSV text.
     *
     * @param entries the entries to render.
     * @return the TSV text, header line first.
     */
    public static String toTsv(final List<BaselineEntry> entries) {
        final List<BaselineEntry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparing(BaselineEntry::id));
        final StringBuilder sb = new StringBuilder();
        sb.append(HEADER).append('\n');
        for (final BaselineEntry e : sorted) {
            sb.append(e.id())
                    .append('\t')
                    .append(e.triage().wire())
                    .append('\t')
                    .append(e.firstSeen() == null ? "" : e.firstSeen())
                    .append('\t')
                    .append(e.note() == null ? "" : e.note())
                    .append('\n');
        }
        return sb.toString();
    }
}
