// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.kbfreshness.model;

/**
 * Document class of a KB entry, derived from its path per the KB {@code LAYOUT.md} type vocabulary.
 * Drives which anchors are extracted and how the entry is keyed.
 */
public enum EntryType {
    /** An architecture topic document. */
    ARCHITECTURE_TOPIC,
    /** An architecture interface document. */
    ARCHITECTURE_INTERFACE,
    /** An architecture overview document. */
    ARCHITECTURE_OVERVIEW,
    /** A rule entry. */
    RULE,
    /** An invariant entry. */
    INVARIANT,
    /** An architecture decision entry. */
    DECISION,
    /** A scenario entry. */
    SCENARIO,
    /** A heuristic entry. */
    HEURISTIC,
    /** A delta-map document mapping code changes to KB updates. */
    DELTA_MAP,
    /** A concept document. */
    CONCEPT,
    /** A glossary document. */
    GLOSSARY,
    /** A symptom-catalog document. */
    SYMPTOM_CATALOG,
    /** A tunable-catalog document. */
    TUNABLE_CATALOG,
    /** README/FORMAT/LAYOUT scaffolding or anything else — not a drift-checked entry. */
    OTHER
}
