// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.kbfreshness.engine;

import java.nio.file.Path;
import java.util.List;
import org.hiero.consensus.kbfreshness.resolve.Allowlist;

/**
 * Inputs for one deterministic run.
 *
 * @param repoRoot    repository root.
 * @param kbRoot      the consensus-layer KB root.
 * @param baselineFile the baseline TSV to join against, or {@code null} for none.
 * @param moduleRoots repo-relative roots to index for source resolution (e.g. {@code platform-sdk}).
 * @param allowlist   generated/external source allowlist.
 * @param runDate     free-form run date recorded for newly-seen findings in the proposed baseline; may
 *                    be empty to keep the proposed baseline stable.
 */
public record RunConfig(
        Path repoRoot, Path kbRoot, Path baselineFile, List<String> moduleRoots, Allowlist allowlist, String runDate) {}
