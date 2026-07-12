// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.kbfreshness.extract;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class FrontmatterParserTest {

    @Test
    void parsesScalarsFlowListsBlockListsNestedMapsAndFoldedScalars() {
        final List<String> lines = List.of(
                "---",
                "type: rule",
                "id: RUL-002",
                "topics: [restart-and-pces, event-intake]",
                "components:",
                "  - swirlds-platform-core/src/main/java/com/swirlds/platform/wiring/PlatformCoordinator.java",
                "  - consensus-utility/src/main/java/org/hiero/consensus/orphan/DefaultOrphanBuffer.java",
                "related:",
                "  invariants: []",
                "  decisions: [ADR-005]",
                "source: >",
                "  Baird, first line",
                "  second line",
                "last_reviewed: 2026-05-15",
                "---",
                "",
                "# Body");

        final Frontmatter fm = FrontmatterParser.parse(lines);

        assertThat(fm.scalar("type")).isEqualTo("rule");
        assertThat(fm.scalar("id")).isEqualTo("RUL-002");
        assertThat(fm.scalar("last_reviewed")).isEqualTo("2026-05-15");
        assertThat(fm.list("topics")).containsExactly("restart-and-pces", "event-intake");
        assertThat(fm.list("components")).hasSize(2);
        assertThat(fm.list("components").get(0)).endsWith("PlatformCoordinator.java");
        assertThat(fm.nested("related", "decisions")).containsExactly("ADR-005");
        assertThat(fm.nested("related", "invariants")).isEmpty();
        assertThat(fm.scalar("source")).contains("Baird, first line").contains("second line");
        assertThat(fm.bodyLine()).isEqualTo(16);
    }

    @Test
    void handlesMissingFrontmatter() {
        final Frontmatter fm = FrontmatterParser.parse(List.of("# Just a body", "text"));
        assertThat(fm.values()).isEmpty();
        assertThat(fm.bodyLine()).isEqualTo(1);
    }
}
