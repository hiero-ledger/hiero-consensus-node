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
import org.hiero.consensus.kbfreshness.git.Git;
import org.hiero.consensus.kbfreshness.model.AnchorKind;
import org.hiero.consensus.kbfreshness.model.Finding;
import org.hiero.consensus.kbfreshness.model.Lane;
import org.hiero.consensus.kbfreshness.model.Outcome;
import org.hiero.consensus.kbfreshness.render.AutoFixRenderer;
import org.hiero.consensus.kbfreshness.render.FindingsJson;
import org.hiero.consensus.kbfreshness.render.SuggestionsRenderer;
import org.hiero.consensus.kbfreshness.render.WorklistRenderer;
import org.hiero.consensus.kbfreshness.resolve.Allowlist;
import org.hiero.consensus.kbfreshness.util.Hashing;
import org.hiero.consensus.kbfreshness.worklist.WorklistEntry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * End-to-end acceptance tests over a hermetic fixture mini-repo. Each test maps to a spec acceptance
 * criterion, including the false-positive guards (line-move, generated symbol, cross-module move).
 */
class EngineFixtureTest {

    private static List<Finding> findings;
    private static RunResult result;
    private static Path repo;

    @BeforeAll
    static void runEngine() throws URISyntaxException {
        repo = Path.of(EngineFixtureTest.class.getResource("/fixtures/repo").toURI());
        final Path kb = repo.resolve("platform-sdk/docs/consensus-layer");
        final RunConfig config = new RunConfig(repo, kb, null, List.of("platform-sdk"), Allowlist.withDefaults(), "");
        result = new Engine(config).run();
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
    void uniquePackageMoveCarriesResolvedPathIntoJsonAndAutoFix() {
        final Finding f = require(AnchorKind.SOURCE_PATH, t -> t.contains("module-a") && t.endsWith("MovedClass.java"));
        assertThat(f.resolvedPath()).isEqualTo("platform-sdk/module-b/src/main/java/com/y/MovedClass.java");
        assertThat(f.evidence()).contains("platform-sdk/module-b/src/main/java/com/y/MovedClass.java");
        assertThat(FindingsJson.render(findings))
                .contains("\"resolvedPath\": \"platform-sdk/module-b/src/main/java/com/y/MovedClass.java\"");
        // The auto-fix artifact proposes the components: entry rewritten to the resolved path.
        final String autoFix = AutoFixRenderer.render(result);
        assertThat(autoFix).contains("update path to `platform-sdk/module-b/src/main/java/com/y/MovedClass.java`");
        assertThat(autoFix).contains("+   - module-b/src/main/java/com/y/MovedClass.java");
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

    @Test
    void annotatedMethodLineResolvesToSignatureNotAnnotation() {
        // AnnotatedMethod.run() sits at line 9, below Javadoc (7) and @Deprecated (8); the auto-fix must
        // propose the signature line, not the annotation's.
        final Finding f = require(AnchorKind.METHOD_REF, t -> t.equals("run"));
        assertThat(f.lane()).isEqualTo(Lane.AUTO_FIX);
        assertThat(f.outcome()).isEqualTo(Outcome.PRESENT);
        assertThat(f.autoFixLine()).isEqualTo(9);
    }

    @Test
    void statedModuleLabelMismatchIsPresentAssert() {
        final Finding f = require(AnchorKind.SOURCE_PATH, t -> t.endsWith("LabeledClass.java"));
        assertThat(f.outcome()).isEqualTo(Outcome.PRESENT);
        assertThat(f.lane()).isEqualTo(Lane.ASSERT);
        assertThat(f.evidence()).contains("module-a").contains("wrong-module");
    }

    @Test
    void fullPathCodeSpanToGoneFileIsAbsentAssert() {
        final Finding f = require(AnchorKind.SOURCE_PATH, t -> t.endsWith("DeletedByAdr.java"));
        assertThat(f.outcome()).isEqualTo(Outcome.ABSENT);
        assertThat(f.lane()).isEqualTo(Lane.ASSERT);
    }

    @Test
    void bareFilenameCodeSpanGoneIsAbsentButPresentIsClean() {
        final Finding gone = require(AnchorKind.SOURCE_BASENAME, t -> t.equals("GhostBare.java"));
        assertThat(gone.outcome()).isEqualTo(Outcome.ABSENT);
        assertThat(gone.lane()).isEqualTo(Lane.ASSERT);
        assertThat(byKind(AnchorKind.SOURCE_BASENAME, t -> t.equals("PresentClass.java")))
                .isEmpty();
    }

    @Test
    void suggestionsProposeNearNameForGoneCrossDocLink() {
        // The typo'd link ../nope-style target `RUL-001-fixtur.md` should suggest the real `RUL-001-fixture.md`.
        final String md = SuggestionsRenderer.render(result, new Git(repo));
        assertThat(md).contains("RUL-001-fixture.md");
    }

    @Test
    void suggestionsForGoneTopicTagsConsiderOnlyTopicAndInterfaceDocs() {
        // The gone tag `rul-001-fixture` matches rules/RUL-001-fixture.md by name, but a topic tag can
        // only denote a topic/interface doc — so no suggestion (and hence no section) may be offered.
        final String md = SuggestionsRenderer.render(result, new Git(repo));
        assertThat(md).doesNotContain("architecture/topics/rul-001-fixture.md");
    }

    @Test
    void moduleRelativeCodeSpanIsExtractedAndChecked() {
        final Finding gone = require(AnchorKind.SOURCE_PATH, t -> t.endsWith("com/x/SpanGone.java"));
        assertThat(gone.target()).isEqualTo("platform-sdk/module-a/src/main/java/com/x/SpanGone.java");
        assertThat(gone.outcome()).isEqualTo(Outcome.ABSENT);
        assertThat(gone.lane()).isEqualTo(Lane.ASSERT);
    }

    @Test
    void topicSlugNamingAnInterfaceDocResolvesCleanly() {
        // topics: [my-api] has no topics/my-api.md, but architecture/interfaces/my-api.md exists.
        assertThat(byKind(AnchorKind.CROSS_DOC_LINK, t -> t.endsWith("topics/my-api.md")))
                .isEmpty();
    }

    @Test
    void topicSlugMissingFromTopicsAndInterfacesAsserts() {
        final Finding f = require(AnchorKind.CROSS_DOC_LINK, t -> t.endsWith("topics/missing-topic.md"));
        assertThat(f.outcome()).isEqualTo(Outcome.ABSENT);
        assertThat(f.lane()).isEqualTo(Lane.ASSERT);
        assertThat(f.evidence()).contains("architecture/interfaces/missing-topic.md");
    }

    @Test
    void historicalGoneSourcesAreQuietButHistoricalPresentAsserts() {
        // ADR-002 marks RemovedByPlan.java and MovedClass.java historical: the gone one is the
        // expected state (quiet), the still-existing one contradicts the documented deletion.
        final Finding goneBare = require(AnchorKind.SOURCE_BASENAME, t -> t.equals("RemovedByPlan.java"));
        assertThat(goneBare.outcome()).isEqualTo(Outcome.UNVERIFIABLE);
        assertThat(goneBare.lane()).isEqualTo(Lane.QUIET_LOG);
        assertThat(goneBare.evidence()).contains("historical");

        final Finding gonePath = require(AnchorKind.SOURCE_PATH, t -> t.endsWith("RemovedByPlan.java"));
        assertThat(gonePath.outcome()).isEqualTo(Outcome.UNVERIFIABLE);
        assertThat(gonePath.lane()).isEqualTo(Lane.QUIET_LOG);

        final Finding stillThere = findings.stream()
                .filter(f -> f.kind() == AnchorKind.SOURCE_BASENAME
                        && f.target().equals("MovedClass.java")
                        && f.entryKey().equals("ADR-002"))
                .findFirst()
                .orElseThrow();
        assertThat(stillThere.outcome()).isEqualTo(Outcome.PRESENT);
        assertThat(stillThere.lane()).isEqualTo(Lane.ASSERT);
        assertThat(stillThere.evidence()).contains("exists");
    }

    @Test
    void worklistEntryWithoutAnchoredSourcesIsUnknownWithReason() {
        final WorklistEntry myApi = result.worklist().stream()
                .filter(e -> e.entryPath().endsWith("my-api.md"))
                .findFirst()
                .orElseThrow();
        assertThat(myApi.status()).isEqualTo(WorklistEntry.Status.UNKNOWN);
        assertThat(myApi.note()).isEqualTo("no anchored sources");
        assertThat(WorklistRenderer.renderMarkdown(result)).contains("unknown (no anchored sources)");
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
