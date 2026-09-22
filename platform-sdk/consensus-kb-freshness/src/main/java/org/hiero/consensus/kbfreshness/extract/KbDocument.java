// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.kbfreshness.extract;

import java.util.List;
import org.hiero.consensus.kbfreshness.model.Entry;

/**
 * A scanned KB document: its {@link Entry} identity plus the raw content and parsed frontmatter the
 * anchor extractor reads.
 *
 * @param entry       the entry identity.
 * @param lines       all file lines, 0-indexed (line N of the file is {@code lines.get(N-1)}).
 * @param frontmatter the parsed frontmatter.
 */
public record KbDocument(Entry entry, List<String> lines, Frontmatter frontmatter) {}
