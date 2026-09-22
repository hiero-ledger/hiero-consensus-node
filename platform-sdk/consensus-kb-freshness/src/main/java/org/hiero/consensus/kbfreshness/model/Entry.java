// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.kbfreshness.model;

/**
 * A drift-checkable KB document.
 *
 * @param key          the stable identity key for this entry — the catalog ID (e.g. {@code RUL-002})
 *                     for catalog entries, otherwise the topic/file slug. Never the file path, so
 *                     identity survives file moves.
 * @param relativePath the entry's path relative to the repo root, for display and link resolution.
 * @param type         the document class.
 * @param lastReviewed the raw {@code last_reviewed} frontmatter value, or {@code null} if the entry
 *                     carries none (catalog entries use {@code status} instead). May be non-date
 *                     (e.g. {@code TBD}).
 */
public record Entry(String key, String relativePath, EntryType type, String lastReviewed) {}
