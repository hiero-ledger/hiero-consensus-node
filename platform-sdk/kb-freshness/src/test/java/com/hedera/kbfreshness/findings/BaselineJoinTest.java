// SPDX-License-Identifier: Apache-2.0
package com.hedera.kbfreshness.findings;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.kbfreshness.model.AnchorKind;
import com.hedera.kbfreshness.model.EntryType;
import com.hedera.kbfreshness.model.Finding;
import com.hedera.kbfreshness.model.Lane;
import com.hedera.kbfreshness.model.Outcome;
import com.hedera.kbfreshness.model.Triage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BaselineJoinTest {

    @Test
    void classifiesNewCarriedResolvedAndCarriesTriage(@TempDir final Path dir) throws IOException {
        final Path baselineFile = dir.resolve("baseline.tsv");
        Files.writeString(
                baselineFile,
                Baseline.HEADER + "\n"
                        + "id2\taccepted\t2026-01-01\tok\n"
                        + "id3\tdismissed\t2026-01-01\tfalse positive\n"
                        + "id4\taccepted\t2026-01-01\tgone now\n");
        final Baseline baseline = Baseline.load(baselineFile);

        final List<Finding> current = List.of(f("id1"), f("id2"), f("id3"));
        final BaselineJoin.Result result = BaselineJoin.join(current, baseline, "2026-07-03");

        assertThat(result.joined()).hasSize(3);
        assertThat(joined(result, "id1").isNew()).isTrue();
        assertThat(joined(result, "id1").triage()).isEqualTo(Triage.NEW);
        assertThat(joined(result, "id1").firstSeen()).isEqualTo("2026-07-03");

        assertThat(joined(result, "id2").isNew()).isFalse();
        assertThat(joined(result, "id2").triage()).isEqualTo(Triage.ACCEPTED);
        assertThat(joined(result, "id2").firstSeen()).isEqualTo("2026-01-01");

        assertThat(joined(result, "id3").triage()).isEqualTo(Triage.DISMISSED);

        assertThat(result.resolvedIds()).containsExactly("id4");
        assertThat(result.proposedBaseline())
                .extracting(BaselineEntry::id)
                .containsExactlyInAnyOrder("id1", "id2", "id3");
    }

    private static BaselineJoin.Joined joined(final BaselineJoin.Result r, final String id) {
        return r.joined().stream()
                .filter(j -> j.finding().id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private static Finding f(final String id) {
        return new Finding(
                id,
                "E-1",
                "path.md",
                EntryType.RULE,
                AnchorKind.SOURCE_PATH,
                "target",
                null,
                null,
                Outcome.ABSENT,
                Lane.ASSERT,
                "question",
                "evidence",
                List.of(),
                null);
    }
}
