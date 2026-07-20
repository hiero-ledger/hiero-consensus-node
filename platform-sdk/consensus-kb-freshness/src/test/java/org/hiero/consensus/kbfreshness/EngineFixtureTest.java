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
import org.hiero.consensus.kbfreshness.render.CoverageRenderer;
import org.hiero.consensus.kbfreshness.render.FindingsJson;
import org.hiero.consensus.kbfreshness.render.ReportRenderer;
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
    void reportSummaryCountsWhatIsFixableWithFix() {
        // Path moves: MovedClass cited by RUL-001 and topic:moved-anchored, the renamed config class
        // (CONFIG_PREFIX), the FQN citation of MovedClass, and RelocatedClass. Moved lines: foo, run.
        final String report = ReportRenderer.render(result, "");
        assertThat(report).contains("| Auto-fix — moved lines | 2 |");
        assertThat(report).contains("| Auto-fix — path moves (assert + ready rewrite) | 5 |");
        assertThat(report).contains("| Fixable now with `--fix` | 7 |");
    }

    @Test
    void reportShowsScanCoverageAndRootCauseRollup() {
        final String report = ReportRenderer.render(result, "");
        assertThat(report).contains("## Scan coverage");
        assertThat(report).contains("- Entries scanned: ");
        assertThat(report).contains("- Anchors extracted: ");
        assertThat(report).contains("## Root causes (rollup)");
        // MovedClass is cited by RUL-001 and topic:moved-anchored — one move, two docs.
        assertThat(report)
                .contains("`platform-sdk/module-a/src/main/java/com/y/MovedClass.java` → "
                        + "`platform-sdk/module-b/src/main/java/com/y/MovedClass.java` — 2 doc(s)");
    }

    @Test
    void movedSourceStillFeedsTheWorklist() {
        // moved-anchored.md cites MovedClass at its stale module-a path; the worklist must track it at
        // its unique new location instead of dropping the topic to "no anchored sources".
        final WorklistEntry e = result.worklist().stream()
                .filter(x -> x.entryPath().endsWith("moved-anchored.md"))
                .findFirst()
                .orElseThrow();
        assertThat(e.anchoredSourceCount()).isEqualTo(1);
        assertThat(e.note()).isNotEqualTo("no anchored sources");
    }

    @Test
    void externalCitedFileThatExistsResolvesCleanly() {
        assertThat(byKind(AnchorKind.SOURCE_PATH, t -> t.endsWith("present.proto")))
                .isEmpty();
    }

    @Test
    void externalCitedFileThatIsMissingStaysQuietWithAbsenceNote() {
        final Finding f = require(AnchorKind.SOURCE_PATH, t -> t.endsWith("missing.proto"));
        assertThat(f.outcome()).isEqualTo(Outcome.UNVERIFIABLE);
        assertThat(f.lane()).isEqualTo(Lane.QUIET_LOG);
        assertThat(f.evidence()).contains("Not found on disk");
    }

    @Test
    void documentedConfigKeyGoneFromRecordAsserts() {
        final Finding f = require(AnchorKind.CONFIG_KEY, t -> t.equals("fix.a.goneKey"));
        assertThat(f.outcome()).isEqualTo(Outcome.ABSENT);
        assertThat(f.lane()).isEqualTo(Lane.ASSERT);
        assertThat(f.evidence()).contains("declares no property `goneKey`");
        // A key that matches produces nothing.
        assertThat(byKind(AnchorKind.CONFIG_KEY, t -> t.equals("fix.a.alpha"))).isEmpty();
    }

    @Test
    void changedConfigDefaultAssertsButMatchingDefaultIsClean() {
        final Finding f = require(AnchorKind.CONFIG_DEFAULT, t -> t.equals("fix.a.beta"));
        assertThat(f.outcome()).isEqualTo(Outcome.ABSENT);
        assertThat(f.lane()).isEqualTo(Lane.ASSERT);
        assertThat(f.evidence()).contains("10s").contains("20s");
        assertThat(byKind(AnchorKind.CONFIG_DEFAULT, t -> t.equals("fix.a.alpha")))
                .isEmpty();
    }

    @Test
    void semanticTypeDifferenceIsQuietNotAssert() {
        // Documented `Path` for a String-typed key: possibly stylistic, never asserted.
        final Finding f = require(AnchorKind.CONFIG_KEY, t -> t.equals("fix.a.gamma"));
        assertThat(f.outcome()).isEqualTo(Outcome.PRESENT);
        assertThat(f.lane()).isEqualTo(Lane.QUIET_LOG);
    }

    @Test
    void nonLiteralConfigDefaultIsUnverifiableAndQuiet() {
        final Finding f = require(AnchorKind.CONFIG_DEFAULT, t -> t.equals("fix.a.delta"));
        assertThat(f.outcome()).isEqualTo(Outcome.UNVERIFIABLE);
        assertThat(f.lane()).isEqualTo(Lane.QUIET_LOG);
    }

    @Test
    void catalogEscapedGenericsAndEmptySpellingAreNormalizedNotFindings() {
        // The catalog writes `List&lt;String&gt;` in table cells and `(empty)` for an empty default;
        // both are conventions, not drift.
        assertThat(byKind(AnchorKind.CONFIG_KEY, t -> t.equals("fix.a.listy"))).isEmpty();
        assertThat(byKind(AnchorKind.CONFIG_DEFAULT, t -> t.equals("fix.a.listy")))
                .isEmpty();
    }

    @Test
    void undocumentedConfigKeyIsCoverageGapNotDrift() {
        final Finding f = require(AnchorKind.CONFIG_KEY, t -> t.equals("fix.a.undocumented"));
        assertThat(f.lane()).isEqualTo(Lane.COVERAGE_GAP);
        assertThat(CoverageRenderer.render(result)).contains("fix.a.undocumented");
    }

    @Test
    void renamedConfigClassResolvesByPrefixAndRewritesHeadingSourceAndModule() {
        final Finding f = require(AnchorKind.CONFIG_PREFIX, t -> t.endsWith("OldNameConfig.java"));
        assertThat(f.outcome()).isEqualTo(Outcome.PRESENT);
        assertThat(f.lane()).isEqualTo(Lane.ASSERT);
        assertThat(f.resolvedPath()).isEqualTo("platform-sdk/module-b/src/main/java/com/y/NewNameConfig.java");
        final String autoFix = AutoFixRenderer.render(result);
        assertThat(autoFix).contains("+ ## `fix.b.*` — NewNameConfig");
        assertThat(autoFix)
                .contains("+ Module: `module-b`. Source: [NewNameConfig.java]"
                        + "(../../module-b/src/main/java/com/y/NewNameConfig.java).");
    }

    @Test
    void goneSlugWithDistinctiveTitleTokenIsSuggestedButNeverPromoted() {
        // `backpressure` shares no name similarity with flow-control.md, but the doc's title carries the
        // distinctive token; that yields a plain hint, never an actionable rename.
        final String md = SuggestionsRenderer.render(result, new Git(repo));
        assertThat(md).contains("architecture/topics/flow-control.md");
        assertThat(md).doesNotContain("rename `topics:` slug `backpressure`");
    }

    @Test
    void citedTopicSlugsWithNoDocumentSurfaceInCoverageLane() {
        final String coverage = CoverageRenderer.render(result);
        assertThat(coverage).contains("## Cited topic slugs with no document");
        assertThat(coverage).contains("- `backpressure` — cited by 1: ADR-001");
        assertThat(coverage).contains("- `missing-topic` — cited by 1: ADR-001");
    }

    @Test
    void topicSlugWithUniqueStrongMatchIsPromotedToActionableRename() {
        // topics: [... present] has no topics/present.md, but present-topic.md is the single strong match,
        // so the hint is promoted to an actionable slug rename.
        final String md = SuggestionsRenderer.render(result, new Git(repo));
        assertThat(md).contains("rename `topics:` slug `present` → `present-topic`");
        // missing-topic has only a weak (0.5) match, so it is offered as a plain near-name, never promoted.
        assertThat(md).doesNotContain("slug `missing-topic`");
    }

    @Test
    void adrCitedGoneSourceGetsHistoricalNudge() {
        // ADR-001 is a decision citing gone sources not marked historical: the suggestion nudges toward
        // marking them historical: rather than repointing to a live file.
        final String md = SuggestionsRenderer.render(result, new Git(repo));
        assertThat(md).contains("mark it `historical:`");
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

    @Test
    void abbreviatedInlineAnchorsCountAsAnchoredSources() {
        // abbrev-anchored.md cites its only source as `module-a/.../PresentClass.java` (the KB's inline
        // abbreviation). The worklist must resolve it through the index, not drop it as un-anchored.
        final WorklistEntry e = result.worklist().stream()
                .filter(x -> x.entryPath().endsWith("abbrev-anchored.md"))
                .findFirst()
                .orElseThrow();
        assertThat(e.anchoredSourceCount()).isGreaterThan(0);
        assertThat(e.note()).isNotEqualTo("no anchored sources");
    }

    @Test
    void topicAnchoringNoSourceSurfacesInCoverageLane() {
        // bare-prose.md cites no source at all; abbrev-anchored.md does (abbreviated) and must not appear.
        final String coverage = CoverageRenderer.render(result);
        assertThat(coverage).contains("## Architecture topics anchoring no source");
        assertThat(coverage).contains("architecture/topics/bare-prose.md");
        assertThat(coverage).doesNotContain("abbrev-anchored.md");
    }

    @Test
    void interfaceDocWithoutTier2FrontmatterSurfacesInCoverageLane() {
        // loose-api.md declares no interface:/methods: so the Tier-2 diff never runs; my-api.md opts in.
        final String coverage = CoverageRenderer.render(result);
        assertThat(coverage).contains("## Interface docs not checked at Tier-2");
        assertThat(coverage).contains("architecture/interfaces/loose-api.md");
        assertThat(coverage).doesNotContain("architecture/interfaces/my-api.md");
    }

    @Test
    void configPrefixMoveSubsumesTheSourcePathGoneFinding() {
        // The fix.b section's Source: link cites the gone OldNameConfig.java; the CONFIG_PREFIX finding
        // already asserts that citation as a class move with a ready rewrite, so the Tier-0 source-path
        // GONE finding for the same line must not double-report it.
        assertThat(byKind(AnchorKind.SOURCE_PATH, t -> t.endsWith("OldNameConfig.java")))
                .isEmpty();
        require(AnchorKind.CONFIG_PREFIX, t -> t.endsWith("OldNameConfig.java"));
    }

    @Test
    void emptyListConstantDefaultComparesAsLiteral() {
        // Configuration.EMPTY_LIST is a whitelisted compile-time constant (= "[]"): a documented
        // `(empty)` matches it cleanly instead of landing in the quiet log...
        assertThat(byKind(AnchorKind.CONFIG_DEFAULT, t -> t.equals("fix.a.emptyListy")))
                .isEmpty();
        // ...and a documented non-empty default contradicting it asserts.
        final Finding f = require(AnchorKind.CONFIG_DEFAULT, t -> t.equals("fix.a.emptyMismatch"));
        assertThat(f.outcome()).isEqualTo(Outcome.ABSENT);
        assertThat(f.lane()).isEqualTo(Lane.ASSERT);
        assertThat(f.evidence()).contains("EMPTY_LIST");
    }

    @Test
    void goneConfigKeyGetsMigrationHints() {
        // fix.a.goneKey is gone from FixtureConfig, but MigratedConfig declares a same-named component
        // (a key migration — actionable) and a similar-named one (a possible rename). Weak token overlap
        // (unrelatedThing) stays below the bar.
        final String md = SuggestionsRenderer.render(result, new Git(repo));
        assertThat(md).contains("key `goneKey` is now declared by `MigratedConfig` — full key `fix.c.goneKey`");
        assertThat(md).contains("similar key: `fix.c.legacyGoneKey` in `MigratedConfig`");
        assertThat(md).doesNotContain("unrelatedThing");
    }

    @Test
    void undocumentedConfigRecordSurfacesInCoverageLane() {
        // MigratedConfig lives in a module the catalog documents but has no section of its own; records
        // the catalog covers (even via a prefix-resolved rename) must not be listed.
        final String coverage = CoverageRenderer.render(result);
        assertThat(coverage).contains("## Config records with no tunables section");
        assertThat(coverage).contains("Config record `MigratedConfig` (`@ConfigData(\"fix.c\")`, 3 key(s))");
        assertThat(coverage).doesNotContain("`NewNameConfig` (`@ConfigData");
        assertThat(coverage).doesNotContain("`FixtureConfig` (`@ConfigData");
    }

    @Test
    void uniqueBasenameDocLinkGetsRewriteHint() {
        // concepts/link-rot.md links flow-control.md against the wrong directory; exactly one KB doc
        // carries that basename, so the hint is a ready link rewrite.
        final String md = SuggestionsRenderer.render(result, new Git(repo));
        assertThat(md).contains("rewrite the link to `../architecture/topics/flow-control.md`");
    }

    @Test
    void readmeIndexRowsAreDriftChecked() {
        // decisions/README.md is scanned as an index entry: its rotted row asserts both as a broken
        // entry link and as a gone catalog ID.
        final Finding link = findings.stream()
                .filter(f -> f.kind() == AnchorKind.CROSS_DOC_LINK
                        && f.target().endsWith("ADR-009-gone.md")
                        && f.entryKey().equals("index:decisions"))
                .findFirst()
                .orElseThrow();
        assertThat(link.outcome()).isEqualTo(Outcome.ABSENT);
        assertThat(link.lane()).isEqualTo(Lane.ASSERT);
        final Finding id = require(AnchorKind.CATALOG_ID, t -> t.equals("ADR-009"));
        assertThat(id.outcome()).isEqualTo(Outcome.ABSENT);
    }

    @Test
    void htmlCommentsAreNotClaims() {
        // The commented-out row-convention template in decisions/README.md cites a gone file (as a link
        // and as an abbreviated path) and a gone catalog ID; none of it may assert.
        assertThat(findings.stream().filter(f -> f.target().contains("GhostCommented")))
                .isEmpty();
        assertThat(findings.stream()
                        .filter(f -> f.entryKey().equals("index:decisions")
                                && f.target().equals("INV-999")))
                .isEmpty();
    }

    @Test
    void reportSummaryShowsPendingSemanticWorklist() {
        final String report = ReportRenderer.render(result, "");
        assertThat(report).contains("| Semantic worklist pending (run by the skill, not this engine) | ");
    }

    @Test
    void fqnCitationOfPresentTypeIsClean() {
        assertThat(byKind(AnchorKind.CLASS, t -> t.equals("com.x.PresentClass")))
                .isEmpty();
    }

    @Test
    void fqnCitationOfMovedTypeIsPackageMoveWithFqnRewrite() {
        final Finding f = require(AnchorKind.CLASS, t -> t.equals("com.x.MovedClass"));
        assertThat(f.outcome()).isEqualTo(Outcome.PRESENT);
        assertThat(f.lane()).isEqualTo(Lane.ASSERT);
        assertThat(f.resolvedPath()).isEqualTo("platform-sdk/module-b/src/main/java/com/y/MovedClass.java");
        final String autoFix = AutoFixRenderer.render(result);
        assertThat(autoFix).contains("update type reference to `com.y.MovedClass`");
        assertThat(autoFix).contains("cooperates with `com.y.MovedClass`");
    }

    @Test
    void fqnCitationOfGoneTypeInIndexedNamespaceAsserts() {
        final Finding f = require(AnchorKind.CLASS, t -> t.equals("com.x.NoSuchClass"));
        assertThat(f.outcome()).isEqualTo(Outcome.ABSENT);
        assertThat(f.lane()).isEqualTo(Lane.ASSERT);
    }

    @Test
    void fqnCitationOutsideIndexedNamespacesIsQuietNotAssert() {
        final Finding f = require(AnchorKind.CLASS, t -> t.equals("io.grpc.StreamObserver"));
        assertThat(f.outcome()).isEqualTo(Outcome.UNVERIFIABLE);
        assertThat(f.lane()).isEqualTo(Lane.QUIET_LOG);
    }

    @Test
    void packageCitationOfExistingPackageIsClean() {
        assertThat(byKind(AnchorKind.PACKAGE_REF, t -> t.equals("com.x.sub"))).isEmpty();
    }

    @Test
    void packageCitationOfGonePackageInIndexedNamespaceAsserts() {
        final Finding f = require(AnchorKind.PACKAGE_REF, t -> t.equals("com.x.gonepkg"));
        assertThat(f.outcome()).isEqualTo(Outcome.ABSENT);
        assertThat(f.lane()).isEqualTo(Lane.ASSERT);
    }

    @Test
    void packageCitationOutsideIndexedNamespacesIsQuietNotAssert() {
        final Finding f = require(AnchorKind.PACKAGE_REF, t -> t.equals("org.apache.logging"));
        assertThat(f.outcome()).isEqualTo(Outcome.UNVERIFIABLE);
        assertThat(f.lane()).isEqualTo(Lane.QUIET_LOG);
    }

    @Test
    void fqnAnchorsCountAsAnchoredSourcesForTheWorklist() {
        final WorklistEntry e = result.worklist().stream()
                .filter(x -> x.entryPath().endsWith("fqn-anchored.md"))
                .findFirst()
                .orElseThrow();
        // PresentClass in its cited package plus MovedClass tracked at its unique new location.
        assertThat(e.anchoredSourceCount()).isEqualTo(2);
        assertThat(e.note()).isNotEqualTo("no anchored sources");
    }

    @Test
    void strandedProseNamingAMovedPackageGetsAHint() {
        // stranded-prose.md's RelocatedClass citation rewrites com.x → com.z, but the prose line still
        // names `com.x`; the FQN citations in fqn-anchored.md continue into a type and must not count.
        final String md = SuggestionsRenderer.render(result, new Git(repo));
        assertThat(md).contains("## Prose naming moved packages");
        assertThat(md).contains("### `topic:stranded-prose` — `com.x`");
        assertThat(md).contains("still named on line 9");
        assertThat(md).contains("moved to: `com.z`");
        assertThat(md).doesNotContain("### `topic:fqn-anchored` — `com.x`");
    }

    @Test
    void impossibleLineHintOnAPathRewriteGetsAReVerifyNote() {
        // stranded-prose.md cites RelocatedClass.java:99; the moved file has 5 lines, so the ready
        // rewrite carries a note (the hint is navigation only — never asserted, never silently kept).
        final String autoFix = AutoFixRenderer.render(result);
        assertThat(autoFix).contains("the cited `:99` hint exceeds the moved file's 5 line(s)");
        // The rewrite itself still applies.
        assertThat(autoFix).contains("(../../../../module-b/src/main/java/com/z/RelocatedClass.java:99)");
    }

    @Test
    void goneKeyWithUniqueSameNamedOwnerRollsUpAsAKeyMigration() {
        final String report = ReportRenderer.render(result, "");
        assertThat(report).contains("### Config-key migrations");
        assertThat(report).contains("`fix.a.goneKey` → `fix.c.goneKey`");
        assertThat(report).contains("keys now declared by `MigratedConfig`");
    }

    @Test
    void keyMigrationHintNamesTheMissingTunablesSection() {
        // MigratedConfig has no catalog section (see the coverage test), so the migration hint says the
        // row move needs a new section — the cross-link between suggestions and coverage.
        final String md = SuggestionsRenderer.render(result, new Git(repo));
        assertThat(md).contains("this record has no tunables section yet (see `coverage.md`)");
    }

    @Test
    void scanCoverageDefinesTheDistinctCheckCountingRule() {
        final String report = ReportRenderer.render(result, "");
        assertThat(report).contains("Distinct anchor checks (one per entry × target × check kind):");
        assertThat(report).contains("A target cited by N entries counts as N checks");
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
