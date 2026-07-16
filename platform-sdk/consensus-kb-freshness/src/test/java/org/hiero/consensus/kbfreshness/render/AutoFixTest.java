// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.kbfreshness.render;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the shared path/label rewrite helpers that back both the auto-fix Markdown and the
 * {@code --fix} applier. They must rewrite exactly the citation styles the KB uses and nothing else.
 */
class AutoFixTest {

    @Test
    void rewritesFullRepoRelativePathInCodeSpan() {
        final String before = "See `platform-sdk/module-a/src/main/java/com/x/Moved.java:24`.";
        assertThat(AutoFix.rewritePath(
                        before,
                        "platform-sdk/module-a/src/main/java/com/x/Moved.java",
                        "platform-sdk/module-b/src/main/java/com/y/Moved.java"))
                .isEqualTo("See `platform-sdk/module-b/src/main/java/com/y/Moved.java:24`.");
    }

    @Test
    void rewritesModuleRelativeFormPreservingRelativeLinkPrefix() {
        final String before = "[`Moved`](../../../../module-a/src/main/java/com/x/Moved.java)";
        assertThat(AutoFix.rewritePath(
                        before,
                        "platform-sdk/module-a/src/main/java/com/x/Moved.java",
                        "platform-sdk/module-b/src/main/java/com/y/Moved.java"))
                .isEqualTo("[`Moved`](../../../../module-b/src/main/java/com/y/Moved.java)");
    }

    @Test
    void rewritesAbbreviatedModuleForm() {
        final String before = "held `consensus-model/.../Moved.java` (deleted)";
        assertThat(AutoFix.rewritePath(
                        before,
                        "consensus-model/.../Moved.java",
                        "platform-sdk/module-b/src/main/java/com/y/Moved.java"))
                .isEqualTo("held `module-b/.../Moved.java` (deleted)");
    }

    @Test
    void leavesLineUntouchedWhenNoCitationStyleMatches() {
        final String before = "unrelated prose mentioning Moved.java without the cited path";
        assertThat(AutoFix.rewritePath(
                        before,
                        "platform-sdk/module-a/src/main/java/com/x/Moved.java",
                        "platform-sdk/module-b/src/main/java/com/y/Moved.java"))
                .isEqualTo(before);
    }

    @Test
    void renamedClassRewritesBareClassNameMentions() {
        // A move that is also a rename (e.g. a config record renamed during a module merge) must fix
        // heading/prose mentions of the old class name, not just the path.
        final String heading = "## `fix.b.*` — OldNameConfig";
        assertThat(AutoFix.rewritePath(
                        heading,
                        "platform-sdk/module-a/src/main/java/com/x/OldNameConfig.java",
                        "platform-sdk/module-b/src/main/java/com/y/NewNameConfig.java"))
                .isEqualTo("## `fix.b.*` — NewNameConfig");
    }

    @Test
    void renamedClassRewritesLinkTextAlongWithThePath() {
        final String line = "Source: [OldNameConfig.java](../../module-a/src/main/java/com/x/OldNameConfig.java).";
        assertThat(AutoFix.rewritePath(
                        line,
                        "platform-sdk/module-a/src/main/java/com/x/OldNameConfig.java",
                        "platform-sdk/module-b/src/main/java/com/y/NewNameConfig.java"))
                .isEqualTo("Source: [NewNameConfig.java](../../module-b/src/main/java/com/y/NewNameConfig.java).");
    }

    @Test
    void sameNameMoveNeverTouchesUnrelatedClassNameMentions() {
        final String line = "prose naming Moved elsewhere: `platform-sdk/module-a/src/main/java/com/x/Moved.java`";
        assertThat(AutoFix.rewritePath(
                        line,
                        "platform-sdk/module-a/src/main/java/com/x/Moved.java",
                        "platform-sdk/module-b/src/main/java/com/y/Moved.java"))
                .isEqualTo("prose naming Moved elsewhere: `platform-sdk/module-b/src/main/java/com/y/Moved.java`");
    }

    @Test
    void rewritesStaleModuleLabelToNewModule() {
        final String before = "Module: `swirlds-platform-core`. Source: [Config.java](x).";
        assertThat(AutoFix.rewriteModuleLabel(before, "swirlds-platform-core", "consensus-utility"))
                .isEqualTo("Module: `consensus-utility`. Source: [Config.java](x).");
    }

    @Test
    void moduleLabelRewriteIsANoOpWhenNothingToChange() {
        final String line = "Module: `swirlds-platform-core`.";
        // No stated module, same module, or absent label → unchanged.
        assertThat(AutoFix.rewriteModuleLabel(line, null, "consensus-utility")).isEqualTo(line);
        assertThat(AutoFix.rewriteModuleLabel(line, "swirlds-platform-core", "swirlds-platform-core"))
                .isEqualTo(line);
        assertThat(AutoFix.rewriteModuleLabel("no label here", "swirlds-platform-core", "consensus-utility"))
                .isEqualTo("no label here");
    }
}
