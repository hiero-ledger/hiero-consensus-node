// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.kbfreshness;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import org.hiero.consensus.kbfreshness.engine.Engine;
import org.hiero.consensus.kbfreshness.engine.RunConfig;
import org.hiero.consensus.kbfreshness.engine.RunResult;
import org.hiero.consensus.kbfreshness.model.AnchorKind;
import org.hiero.consensus.kbfreshness.model.Finding;
import org.hiero.consensus.kbfreshness.model.Lane;
import org.hiero.consensus.kbfreshness.model.Outcome;
import org.hiero.consensus.kbfreshness.render.FindingsJson;
import org.hiero.consensus.kbfreshness.resolve.Allowlist;
import org.hiero.consensus.kbfreshness.util.Hashing;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * End-to-end acceptance tests over a hermetic fixture mini-repo. Each test maps to a spec acceptance
 * criterion, including the false-positive guards (line-move, generated symbol, cross-module move).
 */
class EngineFixtureTest {

    private static List<Finding> findings;

    @BeforeAll
    static void runEngine() throws URISyntaxException {
        final Path repo =
                Path.of(EngineFixtureTest.class.getResource("/fixtures/repo").toURI());
        final Path kb = repo.resolve("platform-sdk/docs/consensus-layer");
        final RunConfig config = new RunConfig(repo, kb, null, List.of("platform-sdk"), Allowlist.withDefaults(), "");
        final RunResult result = new Engine(config).run();
        findings = result.findings();
    }

    @Test
    void citedClassGoneInItsModuleIsAbsentAssertWithEvidence() {
        final Finding f = require(AnchorKind.SOURCE_PATH, t -> t.endsWith("com/x/GoneClass.java"));
        assertThat(f.outcome()).isEqualTo(Outcome.ABSENT);
        assertThat(f.lane()).isEqualTo(Lane.ASSERT);
        assertThat(f.evidence()).contains("GoneClass.java");
    }

    @Test
    void classInDifferentModuleIsPackageMoveNotAbsent() {
        final Finding f = require(AnchorKind.SOURCE_PATH, t -> t.contains("module-a") && t.endsWith("MovedClass.java"));
        assertThat(f.outcome()).isNotEqualTo(Outcome.ABSENT);
        assertThat(f.lane()).isEqualTo(Lane.ASSERT);
        assertThat(f.evidence()).contains("module-b");
    }

    @Test
    void presentClassProducesNoFinding() {
        assertThat(byKind(AnchorKind.SOURCE_PATH, t -> t.endsWith("PresentClass.java")))
                .isEmpty();
    }

    @Test
    void allowlistedGeneratedSymbolIsUnverifiableAndQuiet() {
        final Finding f = require(AnchorKind.SOURCE_PATH, t -> t.endsWith("com/x/Roster.java"));
        assertThat(f.outcome()).isEqualTo(Outcome.UNVERIFIABLE);
        assertThat(f.lane()).isEqualTo(Lane.QUIET_LOG);
    }

    @Test
    void brokenCatalogIdIsTier0Assert() {
        final Finding f = require(AnchorKind.CATALOG_ID, t -> t.equals("INV-999"));
        assertThat(f.outcome()).isEqualTo(Outcome.ABSENT);
        assertThat(f.lane()).isEqualTo(Lane.ASSERT);
    }

    @Test
    void brokenRelativeDocLinkIsTier0Assert() {
        final Finding f = require(AnchorKind.CROSS_DOC_LINK, t -> t.endsWith("nope.md"));
        assertThat(f.outcome()).isEqualTo(Outcome.ABSENT);
        assertThat(f.lane()).isEqualTo(Lane.ASSERT);
    }

    @Test
    void removedVerificationMethodIsAssertButPresentMethodIsNot() {
        final Finding ghost = require(AnchorKind.METHOD_ON_CLASS, t -> t.equals("ghost"));
        assertThat(ghost.outcome()).isEqualTo(Outcome.ABSENT);
        assertThat(ghost.lane()).isEqualTo(Lane.ASSERT);
        assertThat(byKind(AnchorKind.METHOD_ON_CLASS, t -> t.equals("foo"))).isEmpty();
    }

    @Test
    void movedMethodLineIsAutoFixNotAssert() {
        final Finding f = require(AnchorKind.METHOD_REF, t -> t.equals("foo"));
        assertThat(f.lane()).isEqualTo(Lane.AUTO_FIX);
        assertThat(f.outcome()).isEqualTo(Outcome.PRESENT);
        assertThat(f.autoFixLine()).isEqualTo(6); // WithMethod.foo() is declared at line 6
        assertThat(f.occurrences()).allSatisfy(o -> assertThat(o.citedLine()).isEqualTo(3));
    }

    @Test
    void changedMethodSignatureIsAssertButMatchingSignatureIsNot() {
        // baz(long) has no matching overload → assert; baz(int, String) matches → no finding.
        final Finding changed = require(AnchorKind.METHOD_SIGNATURE, t -> t.equals("baz(long)"));
        assertThat(changed.outcome()).isEqualTo(Outcome.ABSENT);
        assertThat(changed.lane()).isEqualTo(Lane.ASSERT);
        assertThat(byKind(AnchorKind.METHOD_SIGNATURE, t -> t.equals("baz(int, String)")))
                .isEmpty();
    }

    @Test
    void interfaceMethodRemovedIsAssertAndPresentMethodIsNot() {
        final Finding removed = require(AnchorKind.INTERFACE_METHOD, t -> t.equals("removed"));
        assertThat(removed.outcome()).isEqualTo(Outcome.ABSENT);
        assertThat(removed.lane()).isEqualTo(Lane.ASSERT);
        assertThat(byKind(AnchorKind.INTERFACE_METHOD, t -> t.equals("present")))
                .isEmpty();
    }

    @Test
    void undocumentedInterfaceMethodIsCoverageGapNotDrift() {
        final Finding coverage = require(AnchorKind.INTERFACE_METHOD, t -> t.equals("extra"));
        assertThat(coverage.lane()).isEqualTo(Lane.COVERAGE_GAP);
        assertThat(coverage.lane()).isNotEqualTo(Lane.ASSERT);
    }

    @Test
    void repeatedDeadSymbolCollapsesToOneFindingWithAllOccurrences() {
        final Finding f = require(AnchorKind.SOURCE_PATH, t -> t.endsWith("GhostFile.java"));
        assertThat(f.outcome()).isEqualTo(Outcome.ABSENT);
        assertThat(f.occurrenceCount()).isEqualTo(3);
        // Identity excludes line numbers and file path.
        assertThat(f.id()).isEqualTo(Hashing.id(f.entryKey(), f.target(), AnchorKind.SOURCE_PATH.name()));
    }

    @Test
    void validTopicSlugAndDocLinkAndHeadingAreNotFindings() {
        // topics: [present-topic] resolves, and the cross-doc link + heading resolve — no false positives.
        assertThat(byKind(AnchorKind.CROSS_DOC_LINK, t -> t.endsWith("present-topic.md")))
                .allSatisfy(f -> assertThat(f.lane()).isNotEqualTo(Lane.ASSERT));
        assertThat(byKind(AnchorKind.DOC_HEADING, t -> t.contains("fixture-rule")))
                .isEmpty();
    }

    @Test
    void findingsArtifactIsByteIdenticalAcrossRenders() {
        assertThat(FindingsJson.render(findings)).isEqualTo(FindingsJson.render(findings));
    }

    // ---- helpers ----

    private static List<Finding> byKind(final AnchorKind kind, final Predicate<String> target) {
        return findings.stream()
                .filter(f -> f.kind() == kind && target.test(f.target()))
                .toList();
    }

    private static Finding require(final AnchorKind kind, final Predicate<String> target) {
        final Optional<Finding> f = byKind(kind, target).stream().findFirst();
        assertThat(f).as("expected a %s finding matching the predicate", kind).isPresent();
        return f.orElseThrow();
    }
}
