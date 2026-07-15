// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.kbfreshness.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import org.hiero.consensus.kbfreshness.apply.AutoFixApplier;
import org.hiero.consensus.kbfreshness.apply.ReviewedMarker;
import org.hiero.consensus.kbfreshness.engine.Engine;
import org.hiero.consensus.kbfreshness.engine.RunConfig;
import org.hiero.consensus.kbfreshness.engine.RunResult;
import org.hiero.consensus.kbfreshness.findings.Baseline;
import org.hiero.consensus.kbfreshness.model.Lane;
import org.hiero.consensus.kbfreshness.render.AutoFixRenderer;
import org.hiero.consensus.kbfreshness.render.CoverageRenderer;
import org.hiero.consensus.kbfreshness.render.FindingsJson;
import org.hiero.consensus.kbfreshness.render.QuietLogRenderer;
import org.hiero.consensus.kbfreshness.render.ReportRenderer;
import org.hiero.consensus.kbfreshness.render.SuggestionsRenderer;
import org.hiero.consensus.kbfreshness.render.WorklistRenderer;
import org.hiero.consensus.kbfreshness.resolve.Allowlist;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Command-line entry point for the deterministic KB freshness checker. Scans the curated
 * consensus-layer KB, resolves its code anchors against the current checkout, and writes a report,
 * quiet log, auto-fix proposals, suggestions, a coverage lane, a machine artifact, a semantic
 * worklist, and a proposed baseline. No model, no network.
 */
@Command(
        name = "kb-freshness",
        mixinStandardHelpOptions = true,
        description = "Deterministic consensus-layer knowledge-base drift checker.")
public final class Main implements Callable<Integer> {

    /** KB root to scan, resolved against {@link #repo}. Required. */
    @Option(
            names = "--kb",
            required = true,
            paramLabel = "<path>",
            description = "KB root (e.g. platform-sdk/docs/consensus-layer). Resolved against --repo.")
    private Path kb;

    /** Repository root that all relative paths and source resolution are anchored to. */
    @Option(
            names = "--repo",
            paramLabel = "<path>",
            description = "Repo root for resolution. Default: current directory.")
    private Path repo = Path.of(".");

    /** Output directory for the artifacts; defaults to {@code <repo>/build/kb-freshness} when null. */
    @Option(
            names = "--out",
            paramLabel = "<dir>",
            description = "Output directory. Default: <repo>/build/kb-freshness.")
    private Path out;

    /** Baseline TSV to join the current findings against; null means no baseline. */
    @Option(
            names = "--baseline",
            paramLabel = "<file>",
            description = "Baseline TSV to join against (id/triage/first_seen).")
    private Path baseline;

    /** Repo-relative source roots to index for symbol resolution. */
    @Option(
            names = "--modules",
            split = ",",
            paramLabel = "<csv>",
            description = "Source roots to index. Default: platform-sdk,hedera-node.")
    private List<String> modules = List.of("platform-sdk", "hedera-node");

    /** Optional file with extra generated/external allowlist directives that extend the defaults. */
    @Option(
            names = "--allowlist",
            paramLabel = "<file>",
            description = "Extra generated/external allowlist directives.")
    private Path allowlist;

    /** Run date recorded as {@code first_seen} for newly-seen findings in the proposed baseline. */
    @Option(
            names = "--date",
            paramLabel = "<str>",
            description = "Run date recorded for new findings in the proposed baseline.")
    private String date = "";

    /** When set, overwrite the {@code --baseline} file with the proposed baseline. */
    @Option(names = "--write-baseline", description = "Overwrite --baseline with the proposed baseline.")
    private boolean writeBaseline;

    /** When set, exit with code 2 if any new (not-baselined, not-dismissed) assertion is found. */
    @Option(names = "--fail-on-drift", description = "Exit 2 if any new assertion is found.")
    private boolean failOnDrift;

    /** When set, write the certain auto-fix edits (moved lines and unique path moves) into the KB files. */
    @Option(
            names = "--fix",
            description = "Apply the certain auto-fix edits (moved lines and unique path moves) to the KB in place.")
    private boolean fix;

    /**
     * Entries to mark as reviewed: each spec is {@code <key>[=<yyyy-MM-dd>]}; a spec without a date uses
     * {@code --date}. Rewrites only an existing {@code last_reviewed:} frontmatter line.
     */
    @Option(
            names = "--mark-reviewed",
            paramLabel = "<key[=date]>",
            description = "Bump an entry's existing last_reviewed: frontmatter date (repeatable); "
                    + "a spec without =<date> uses --date.")
    private List<String> markReviewed = List.of();

    /**
     * Process entry point: parses arguments with picocli and exits with the command's return code.
     *
     * @param args the raw command-line arguments.
     */
    public static void main(final String[] args) {
        System.exit(new CommandLine(new Main()).execute(args));
    }

    /**
     * Runs one freshness check and writes all artifacts.
     *
     * @return {@code 0} on success, {@code 1} on a usage/IO error, {@code 2} when
     *     {@code --fail-on-drift} is set and new drift was found.
     * @throws IOException if reading the allowlist or writing artifacts fails.
     */
    @Override
    public Integer call() throws IOException {
        final Path repoRoot = repo.toAbsolutePath().normalize();
        final Path kbRoot = resolveAgainst(repoRoot, kb);
        if (!Files.isDirectory(kbRoot)) {
            System.err.println("error: KB root not found: " + kbRoot);
            return 1;
        }
        final Path outDir = out != null ? resolveAgainst(repoRoot, out) : repoRoot.resolve("build/kb-freshness");
        final Path baselineFile = baseline != null ? resolveAgainst(repoRoot, baseline) : null;

        Allowlist allowlistConfig = Allowlist.withDefaults();
        if (allowlist != null) {
            allowlistConfig = allowlistConfig.extendedWith(Files.readAllLines(resolveAgainst(repoRoot, allowlist)));
        }

        final RunConfig config = new RunConfig(repoRoot, kbRoot, baselineFile, modules, allowlistConfig, date);
        final RunResult result = new Engine(config).run();

        writeArtifacts(outDir, result, baselineFile);

        if (fix) {
            final AutoFixApplier.Result applied = AutoFixApplier.apply(result, repoRoot);
            System.out.printf(
                    "kb-freshness --fix: applied %d edit(s) across %d file(s)%s. Re-run to refresh artifacts.%n",
                    applied.applied(),
                    applied.filesChanged().size(),
                    applied.skipped() > 0 ? " (" + applied.skipped() + " skipped: line diverged)" : "");
        }

        if (!markReviewed.isEmpty()) {
            final ReviewedMarker.Result marked = ReviewedMarker.apply(result, repoRoot, markReviewed, date);
            System.out.printf(
                    "kb-freshness --mark-reviewed: updated %d doc(s). Re-run to refresh the worklist.%n",
                    marked.updated());
            for (final String problem : marked.problems()) {
                System.err.println("error: --mark-reviewed " + problem);
            }
            if (!marked.problems().isEmpty()) {
                return 1;
            }
        }

        final long newDrift = result.newDriftCount();
        System.out.printf(
                "kb-freshness: %d findings (%d new assert, %d quiet, %d auto-fix). Artifacts in %s%n",
                result.findings().size(),
                newDrift,
                result.stats().findingsByLane().getOrDefault(Lane.QUIET_LOG, 0),
                result.stats().findingsByLane().getOrDefault(Lane.AUTO_FIX, 0),
                outDir);

        return failOnDrift && newDrift > 0 ? 2 : 0;
    }

    /**
     * Writes every run artifact (findings JSON, report, quiet log, auto-fix proposals, suggestions,
     * coverage, worklist, and proposed baseline) into {@code outDir}, optionally overwriting the
     * baseline file in place.
     *
     * @param outDir       the directory to write artifacts into (created if absent).
     * @param result       the run result to render.
     * @param baselineFile the baseline file to overwrite when {@code --write-baseline} is set, or null.
     * @throws IOException if any artifact cannot be written.
     */
    private void writeArtifacts(final Path outDir, final RunResult result, final Path baselineFile) throws IOException {
        Files.createDirectories(outDir);
        write(outDir.resolve("findings.json"), FindingsJson.render(result.findings()));
        write(outDir.resolve("report.md"), ReportRenderer.render(result, date));
        write(outDir.resolve("quiet-log.md"), QuietLogRenderer.render(result));
        write(outDir.resolve("auto-fix.md"), AutoFixRenderer.render(result));
        write(outDir.resolve("suggestions.md"), SuggestionsRenderer.render(result, result.git()));
        write(outDir.resolve("coverage.md"), CoverageRenderer.render(result));
        write(outDir.resolve("worklist.md"), WorklistRenderer.renderMarkdown(result));
        write(outDir.resolve("worklist.json"), WorklistRenderer.renderJson(result));

        final String proposed = Baseline.toTsv(result.join().proposedBaseline());
        write(outDir.resolve("baseline.proposed.tsv"), proposed);
        if (writeBaseline && baselineFile != null) {
            write(baselineFile, proposed);
        }
    }

    /**
     * Writes UTF-8 text to a file, overwriting any existing content.
     *
     * @param path    the file to write.
     * @param content the content to write.
     * @throws IOException if the write fails.
     */
    private static void write(final Path path, final String content) throws IOException {
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    /**
     * Resolves a possibly-relative path against a base directory and normalizes it.
     *
     * @param base the base directory for relative paths.
     * @param p    the path to resolve; returned as-is (normalized) when absolute.
     * @return the normalized, absolute-or-base-relative path.
     */
    private static Path resolveAgainst(final Path base, final Path p) {
        return (p.isAbsolute() ? p : base.resolve(p)).normalize();
    }
}
