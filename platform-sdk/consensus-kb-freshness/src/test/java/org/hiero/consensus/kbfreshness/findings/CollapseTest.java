// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.kbfreshness.findings;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.hiero.consensus.kbfreshness.extract.AnchorExtractor;
import org.hiero.consensus.kbfreshness.extract.Frontmatter;
import org.hiero.consensus.kbfreshness.extract.FrontmatterParser;
import org.hiero.consensus.kbfreshness.extract.KbDocument;
import org.hiero.consensus.kbfreshness.model.Entry;
import org.hiero.consensus.kbfreshness.model.EntryType;
import org.hiero.consensus.kbfreshness.model.Finding;
import org.hiero.consensus.kbfreshness.resolve.Allowlist;
import org.hiero.consensus.kbfreshness.resolve.AnchorResolver;
import org.hiero.consensus.kbfreshness.resolve.SourceIndex;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Verifies collapse-grain: repeated mentions of one dead symbol form a single finding whose count
 * tracks the surviving occurrences, and the finding closes only when the last mention is removed.
 */
class CollapseTest {

    private static FindingAssembler assembler;

    @BeforeAll
    static void setUp() throws URISyntaxException {
        final Path repo =
                Path.of(CollapseTest.class.getResource("/fixtures/repo").toURI());
        final Path kb = repo.resolve("platform-sdk/docs/consensus-layer");
        final SourceIndex index = SourceIndex.build(repo, List.of("platform-sdk"));
        assembler = new FindingAssembler(
                new AnchorExtractor(repo, kb), new AnchorResolver(repo, kb, index, Allowlist.withDefaults()));
    }

    @Test
    void threeMentionsCollapseToOneFindingWithThreeHints() {
        final Optional<Finding> ghost = ghostFinding(3);
        assertThat(ghost).isPresent();
        assertThat(ghost.orElseThrow().occurrenceCount()).isEqualTo(3);
    }

    @Test
    void fixingTwoLeavesTheFindingOpenWithOneHint() {
        final Optional<Finding> ghost = ghostFinding(1);
        assertThat(ghost).isPresent();
        assertThat(ghost.orElseThrow().occurrenceCount()).isEqualTo(1);
    }

    @Test
    void fixingAllMentionsClosesTheFinding() {
        assertThat(ghostFinding(0)).isEmpty();
    }

    private Optional<Finding> ghostFinding(final int mentions) {
        return assembler.assemble(syntheticTopic(mentions)).stream()
                .filter(f -> f.target().endsWith("GhostFile.java"))
                .findFirst();
    }

    private static KbDocument syntheticTopic(final int mentions) {
        final List<String> lines = new ArrayList<>();
        lines.add("# Synthetic topic");
        for (int i = 0; i < mentions; i++) {
            lines.add("mention [GhostFile.java](../../../../module-a/src/main/java/com/x/GhostFile.java)");
        }
        final Frontmatter fm = FrontmatterParser.parse(lines);
        final Entry entry = new Entry(
                "topic:synthetic",
                "platform-sdk/docs/consensus-layer/architecture/topics/synthetic.md",
                EntryType.ARCHITECTURE_TOPIC,
                null);
        return new KbDocument(entry, lines, fm);
    }
}
