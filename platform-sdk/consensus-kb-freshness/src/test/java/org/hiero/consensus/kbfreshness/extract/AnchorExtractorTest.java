// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.kbfreshness.extract;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import org.hiero.consensus.kbfreshness.model.Anchor;
import org.hiero.consensus.kbfreshness.model.AnchorKind;
import org.hiero.consensus.kbfreshness.model.Entry;
import org.hiero.consensus.kbfreshness.model.EntryType;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the body-scanning behaviours the drift checker relies on: wrapped markdown links,
 * the prose {@code Module:} label cross-check, and inline code-span source citations (full path and
 * bare basename), plus the guards against fenced-block and double-counting false positives.
 */
class AnchorExtractorTest {

    @Test
    void wrappedMarkdownLinkTextIsStillExtracted() {
        final List<Anchor> anchors = extract(List.of(
                "See [",
                "`RoundHashValidator.java`](../../consensus-hashgraph/src/main/java/org/hiero/RoundHashValidator.java)",
                "for details."));
        final Anchor a = require(anchors, AnchorKind.SOURCE_PATH, t -> t.endsWith("RoundHashValidator.java"));
        assertThat(a.target())
                .isEqualTo("platform-sdk/consensus-hashgraph/src/main/java/org/hiero/RoundHashValidator.java");
        // The URL (and any :NN) sits on the second body line — line 7 of the whole doc.
        assertThat(a.docLine()).isEqualTo(7);
    }

    @Test
    void moduleLabelIsCapturedOntoAdjacentSourceLink() {
        final List<Anchor> anchors = extract(
                List.of(
                        "Module: `swirlds-common`. Source: "
                                + "[StateCommonConfig.java](../../swirlds-common/src/main/java/com/swirlds/common/config/StateCommonConfig.java)."));
        final Anchor a = require(anchors, AnchorKind.SOURCE_PATH, t -> t.endsWith("StateCommonConfig.java"));
        assertThat(a.statedModule()).isEqualTo("swirlds-common");
    }

    @Test
    void fullPathCodeSpanBecomesSourcePathCarryingTheCitedLine() {
        final List<Anchor> anchors = extract(List.of(
                "As cited (`platform-sdk/consensus-model/src/main/java/org/hiero/PlatformStatus.java:38-41`)."));
        final Anchor a = require(anchors, AnchorKind.SOURCE_PATH, t -> t.endsWith("PlatformStatus.java"));
        assertThat(a.target()).isEqualTo("platform-sdk/consensus-model/src/main/java/org/hiero/PlatformStatus.java");
        // The line is carried (start of a range) to drive a `:NN`→`#symbol` migration — not asserted on.
        assertThat(a.citedLine()).isEqualTo(38);
        assertThat(a.citedModule()).isEqualTo("consensus-model");
    }

    @Test
    void bareFilenameCodeSpanBecomesSourceBasenameCarryingTheCitedLine() {
        final List<Anchor> anchors = extract(List.of("Handled in (`ObservingStatusLogic.java:176-187`)."));
        final Anchor a = require(anchors, AnchorKind.SOURCE_BASENAME, t -> t.equals("ObservingStatusLogic.java"));
        assertThat(a.citedLine()).isEqualTo(176);
        assertThat(a.citedModule()).isNull();
    }

    @Test
    void moduleRelativeCodeSpanBecomesSourcePathWithPlatformSdkPrefix() {
        final List<Anchor> anchors =
                extract(List.of("Code anchor: `swirlds-x/src/main/java/com/swirlds/Foo.java:42`."));
        final Anchor a = require(anchors, AnchorKind.SOURCE_PATH, t -> t.endsWith("Foo.java"));
        assertThat(a.target()).isEqualTo("platform-sdk/swirlds-x/src/main/java/com/swirlds/Foo.java");
        assertThat(a.citedModule()).isEqualTo("swirlds-x");
        assertThat(a.citedLine()).isEqualTo(42);
    }

    @Test
    void historicalFrontmatterMarksMatchingSourceAnchors() {
        final List<Anchor> anchors = extractWithFrontmatter(
                List.of("historical: [Zombie.java]"),
                List.of("Deleted `Zombie.java` and (`platform-sdk/m/src/main/java/x/Zombie.java`); "
                        + "`Alive.java` remains."));
        assertThat(require(anchors, AnchorKind.SOURCE_BASENAME, t -> t.equals("Zombie.java"))
                        .historical())
                .isTrue();
        assertThat(require(anchors, AnchorKind.SOURCE_PATH, t -> t.endsWith("Zombie.java"))
                        .historical())
                .isTrue();
        assertThat(require(anchors, AnchorKind.SOURCE_BASENAME, t -> t.equals("Alive.java"))
                        .historical())
                .isFalse();
    }

    @Test
    void fqnCodeSpanBecomesClassAnchorWithPrimaryTypeScope() {
        final List<Anchor> anchors =
                extract(List.of("Detection lives in `com.swirlds.component.framework.monitor.HealthMonitor`."));
        final Anchor a = require(anchors, AnchorKind.CLASS, t -> t.endsWith("HealthMonitor"));
        assertThat(a.target()).isEqualTo("com.swirlds.component.framework.monitor.HealthMonitor");
        assertThat(a.citedScope()).isEqualTo("HealthMonitor");
    }

    @Test
    void nestedTypeFqnCodeSpanScopesToTheFileDefiningType() {
        final List<Anchor> anchors = extract(List.of("See `com.x.Outer.Inner` for the state machine."));
        final Anchor a = require(anchors, AnchorKind.CLASS, t -> t.equals("com.x.Outer.Inner"));
        assertThat(a.citedScope()).isEqualTo("Outer");
    }

    @Test
    void reverseDomainPackageCodeSpanBecomesPackageRefAnchor() {
        final List<Anchor> anchors = extract(List.of("Everything in `org.hiero.consensus.state.nexus` holds this."));
        require(anchors, AnchorKind.PACKAGE_REF, t -> t.equals("org.hiero.consensus.state.nexus"));
    }

    @Test
    void dottedNamesThatAreNotPackagesAreNotExtracted() {
        // A config prefix, a two-segment name, and a method call must never be read as a package or FQN.
        final List<Anchor> anchors =
                extract(List.of("The prefix `state.management.wiring` and the pair `com.x` interact via "
                        + "`HealthMonitor.checkSystemHealth(Instant)`."));
        assertThat(byKind(anchors, AnchorKind.PACKAGE_REF, t -> true)).isEmpty();
        assertThat(byKind(anchors, AnchorKind.CLASS, t -> true)).isEmpty();
    }

    @Test
    void codeSpanInsideFencedBlockIsIgnored() {
        final List<Anchor> anchors =
                extract(List.of("```", "`platform-sdk/consensus-model/src/main/java/org/hiero/Fenced.java`", "```"));
        assertThat(byKind(anchors, AnchorKind.SOURCE_PATH, t -> t.endsWith("Fenced.java")))
                .isEmpty();
    }

    @Test
    void backtickedFilenameInLinkTextIsNotDoubleCounted() {
        final List<Anchor> anchors = extract(
                List.of(
                        "[`EventIntakeModule.java`](../../consensus-event-intake/src/main/java/org/hiero/EventIntakeModule.java)"));
        // Exactly one source anchor from the link; no extra SOURCE_BASENAME from the backticked link text.
        assertThat(byKind(anchors, AnchorKind.SOURCE_PATH, t -> t.endsWith("EventIntakeModule.java")))
                .hasSize(1);
        assertThat(byKind(anchors, AnchorKind.SOURCE_BASENAME, t -> true)).isEmpty();
    }

    // ---- helpers ----

    private static List<Anchor> extract(final List<String> body) {
        return extractWithFrontmatter(List.of(), body);
    }

    private static List<Anchor> extractWithFrontmatter(final List<String> frontmatterExtra, final List<String> body) {
        final List<String> lines = new ArrayList<>(List.of("---", "type: decision", "id: ADR-999"));
        lines.addAll(frontmatterExtra);
        lines.addAll(List.of("---", ""));
        lines.addAll(body);
        final Frontmatter fm = FrontmatterParser.parse(lines);
        final Entry entry =
                new Entry("ADR-999", "platform-sdk/docs/consensus-layer/tunables.md", EntryType.TUNABLE_CATALOG, null);
        final KbDocument doc = new KbDocument(entry, lines, fm);
        final AnchorExtractor extractor =
                new AnchorExtractor(Path.of("/repo"), Path.of("/repo/platform-sdk/docs/consensus-layer"));
        return extractor.extract(doc);
    }

    private static List<Anchor> byKind(final List<Anchor> anchors, final AnchorKind kind, final Predicate<String> t) {
        return anchors.stream()
                .filter(a -> a.kind() == kind && t.test(a.target()))
                .toList();
    }

    private static Anchor require(final List<Anchor> anchors, final AnchorKind kind, final Predicate<String> t) {
        return byKind(anchors, kind, t).stream()
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected a " + kind + " anchor matching the predicate"));
    }
}
