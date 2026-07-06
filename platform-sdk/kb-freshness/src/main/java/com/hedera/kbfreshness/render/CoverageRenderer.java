// SPDX-License-Identifier: Apache-2.0
package com.hedera.kbfreshness.render;

import com.hedera.kbfreshness.engine.RunResult;
import com.hedera.kbfreshness.model.Finding;
import com.hedera.kbfreshness.model.Lane;

/**
 * Renders the coverage lane: code that exists but the KB does not document (e.g. an interface method
 * declared in source but absent from the interface entry). Coverage gaps are kept out of the drift
 * report by design — drift detection asserts only on documented-but-false claims — and surfaced here
 * separately for a curator who wants to close documentation gaps.
 */
public final class CoverageRenderer {

    /** Prevents instantiation of this static-only renderer. */
    private CoverageRenderer() {}

    /**
     * Renders the coverage lane as Markdown.
     *
     * @param result the run result.
     * @return the rendered Markdown coverage report.
     */
    public static String render(final RunResult result) {
        final StringBuilder sb = new StringBuilder();
        sb.append("# KB freshness — coverage lane (undocumented code)\n\n");
        sb.append("_Code that exists but the KB does not document. Not drift; never asserted._\n\n");
        boolean any = false;
        for (final Finding f : result.findings()) {
            if (f.lane() != Lane.COVERAGE_GAP) {
                continue;
            }
            any = true;
            sb.append("- `")
                    .append(f.entryKey())
                    .append("` — ")
                    .append(f.evidence())
                    .append('\n');
        }
        if (!any) {
            sb.append("_None._\n");
        }
        return sb.toString();
    }
}
