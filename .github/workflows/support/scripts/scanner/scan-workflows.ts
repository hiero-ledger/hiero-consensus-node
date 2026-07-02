#!/usr/bin/env npx ts-node

/**
 * scan-workflows.ts
 * Scans GitHub Actions workflow YAML files for script injection vulnerabilities —
 * places where attacker-controlled context variables are interpolated directly
 * into `run:` blocks instead of being passed through an env var.
 *
 * Usage:
 *   npx ts-node scan-workflows.ts [path-to-workflows-dir]
 *
 * Defaults to .github/workflows in the current directory.
 *
 * Requirements:
 *   npm install typescript ts-node js-yaml @types/js-yaml @types/node
 */

import * as fs from "fs";
import * as path from "path";
import * as yaml from "js-yaml";

// ─── Dangerous context variables per GitHub's own documentation ──────────────
// https://docs.github.com/en/actions/concepts/security/script-injections
const DANGEROUS_CONTEXTS: string[] = [
    "github.event.issue.title",
    "github.event.issue.body",
    "github.event.pull_request.title",
    "github.event.pull_request.body",
    "github.event.comment.body",
    "github.event.review.body",
    "github.event.pages.*.page_name",
    "github.event.commits.*.message",
    "github.event.head_commit.message",
    "github.event.head_commit.author.email",
    "github.event.head_commit.author.name",
    "github.event.commits.*.author.email",
    "github.event.commits.*.author.name",
    "github.event.pull_request.head.ref",
    "github.event.pull_request.head.label",
    "github.event.pull_request.head.repo.default_branch",
    "github.head_ref",
];

// Build regex patterns. Wildcard entries (*.foo) are converted to match any
// array index or key segment, e.g. commits.*.message → commits\.[^}]+\.message
function buildPattern(ctx: string): RegExp {
    const escaped = ctx
        .replace(/\./g, "\\.")       // escape dots
        .replace(/\\\.\*\\\./g, "\\.[^}]+\\."); // un-escape wildcard segments
    // Match ${{ ... <context> ... }} — allow operators/fallbacks around the context
    return new RegExp(`\\$\\{\\{[^}]*${escaped}[^}]*}}`, "gi");
}

const PATTERNS: Array<{ context: string; regex: RegExp }> = DANGEROUS_CONTEXTS.map(
    (ctx) => ({ context: ctx, regex: buildPattern(ctx) })
);

// ─── Additional dynamic patterns ─────────────────────────────────────────────

// Step outputs can carry attacker-controlled data (e.g. derived from PR metadata)
const STEP_OUTPUT_PATTERN: { context: string; regex: RegExp } = {
    context: "steps.<id>.outputs.<name>",
    regex: /\$\{\{[^}]*steps\.[a-zA-Z0-9_-]+\.outputs\.[a-zA-Z0-9_-][^}]*}}/gi,
};

// Build per-workflow patterns for string-typed inputs. Boolean/number/choice
// inputs are constrained values and cannot be shell-injected; skip them.
function buildInputPatterns(workflow: any): Array<{ context: string; regex: RegExp }> {
    const patterns: Array<{ context: string; regex: RegExp }> = [];
    const inputSources: Array<Record<string, any>> = [
        workflow?.on?.workflow_dispatch?.inputs ?? {},
        workflow?.on?.workflow_call?.inputs ?? {},
    ];
    for (const inputs of inputSources) {
        for (const [name, def] of Object.entries<any>(inputs)) {
            if (def?.type === "boolean" || def?.type === "number" || def?.type === "choice") continue;
            const escaped = name.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
            patterns.push({
                context: `inputs.${name}`,
                regex: new RegExp(`\\$\\{\\{[^}]*inputs\\.${escaped}[^}]*}}`, "gi"),
            });
        }
    }
    return patterns;
}

// ─── Types ────────────────────────────────────────────────────────────────────

interface Finding {
    file: string;
    jobName: string;
    stepName: string;
    stepIndex: number;
    context: string;
    lineNumber: number;
    lineText: string;
    inRunBlock: boolean;   // true = direct injection risk; false = in env: (safe)
    inEnvBlock: boolean;
}

// ─── YAML traversal ──────────────────────────────────────────────────────────

function findInjections(filePath: string, raw: string): Finding[] {
    const findings: Finding[] = [];
    const lines = raw.split("\n");

    let workflow: any;
    try {
        workflow = yaml.load(raw);
    } catch {
        console.error(`  ⚠ Could not parse YAML: ${filePath}`);
        return [];
    }

    if (!workflow?.jobs || typeof workflow.jobs !== "object") return [];

    const allPatterns = [
        ...PATTERNS,
        STEP_OUTPUT_PATTERN,
        ...buildInputPatterns(workflow),
    ];

    for (const [jobName, job] of Object.entries<any>(workflow.jobs)) {
        const steps: any[] = Array.isArray(job?.steps) ? job.steps : [];

        steps.forEach((step, stepIdx) => {
            const stepName = step?.name ?? `(step ${stepIdx + 1})`;

            // Check the `run:` block — direct injection risk
            if (typeof step?.run === "string") {
                checkText(step.run, filePath, raw, lines, jobName, stepName, stepIdx, true, false, allPatterns, findings);
            }

            // Check the `env:` block — still logs it but marks as safe indirection
            if (step?.env && typeof step.env === "object") {
                for (const envVal of Object.values<any>(step.env)) {
                    if (typeof envVal === "string") {
                        checkText(envVal, filePath, raw, lines, jobName, stepName, stepIdx, false, true, allPatterns, findings);
                    }
                }
            }
        });
    }

    return findings;
}

function checkText(
    text: string,
    filePath: string,
    raw: string,
    lines: string[],
    jobName: string,
    stepName: string,
    stepIdx: number,
    inRunBlock: boolean,
    inEnvBlock: boolean,
    patterns: Array<{ context: string; regex: RegExp }>,
    findings: Finding[]
) {
    for (const { context, regex } of patterns) {
        let match: RegExpExecArray | null;
        regex.lastIndex = 0;
        while ((match = regex.exec(text)) !== null) {
            // Find the actual line in the raw file that contains this match
            const matchStr = match[0];
            const lineNumber = findLineNumber(raw, lines, matchStr);
            const lineText = lineNumber > 0 ? lines[lineNumber - 1].trim() : text.trim();

            findings.push({
                file: filePath,
                jobName,
                stepName,
                stepIndex: stepIdx + 1,
                context,
                lineNumber,
                lineText,
                inRunBlock,
                inEnvBlock,
            });
        }
    }
}

function findLineNumber(raw: string, lines: string[], matchStr: string): number {
    // Find the first occurrence of this match string in the raw file
    const idx = raw.indexOf(matchStr);
    if (idx === -1) return 0;
    return raw.substring(0, idx).split("\n").length;
}

// ─── File discovery ───────────────────────────────────────────────────────────

function collectWorkflowFiles(dir: string): string[] {
    if (!fs.existsSync(dir)) return [];
    const stat = fs.statSync(dir);
    if (stat.isFile()) return [dir];
    return fs
        .readdirSync(dir)
        .filter((f) => f.endsWith(".yml") || f.endsWith(".yaml"))
        .map((f) => path.join(dir, f));
}

// ─── Reporting ────────────────────────────────────────────────────────────────

const RESET  = "\x1b[0m";
const RED    = "\x1b[31m";
const YELLOW = "\x1b[33m";
const GREEN  = "\x1b[32m";
const BOLD   = "\x1b[1m";
const DIM    = "\x1b[2m";
const CYAN   = "\x1b[36m";

function report(allFindings: Finding[], scannedFiles: string[]) {
    const injections = allFindings.filter((f) => f.inRunBlock);
    const safe       = allFindings.filter((f) => f.inEnvBlock);

    console.log(`\n${BOLD}═══════════════════════════════════════════════════${RESET}`);
    console.log(`${BOLD}  GitHub Actions Injection Scanner${RESET}`);
    console.log(`${BOLD}═══════════════════════════════════════════════════${RESET}\n`);
    console.log(`${DIM}Scanned ${scannedFiles.length} workflow file(s)${RESET}\n`);

    if (injections.length === 0 && safe.length === 0) {
        console.log(`${GREEN}✔ No dangerous context interpolations found.${RESET}\n`);
        return;
    }

    // ── Direct injection risks ──
    if (injections.length > 0) {
        console.log(`${RED}${BOLD}🚨 INJECTION RISKS  (${injections.length} found)${RESET}`);
        console.log(`${DIM}These use attacker-controlled variables directly in run: blocks.${RESET}\n`);

        const byFile = groupBy(injections, (f) => f.file);
        for (const [file, findings] of Object.entries(byFile)) {
            console.log(`${BOLD}${CYAN}${path.basename(file)}${RESET}  ${DIM}${file}${RESET}`);
            for (const f of findings) {
                console.log(
                    `  ${RED}✗${RESET}  job: ${BOLD}${f.jobName}${RESET}  step ${f.stepIndex}: ${BOLD}${f.stepName}${RESET}`
                );
                if (f.lineNumber > 0) {
                    console.log(`     ${DIM}line ${f.lineNumber}:${RESET}  ${f.lineText}`);
                }
                console.log(`     ${RED}context:${RESET} $\{{ ${f.context} }}`);
                console.log(`     ${YELLOW}fix:${RESET}     move to env: block and reference via $ENV_VAR_NAME\n`);
            }
        }
    }

    // ── Env-block usages (safe, but listed for completeness) ──
    if (safe.length > 0) {
        console.log(`${GREEN}${BOLD}✔ SAFE (env: block)  (${safe.length} found)${RESET}`);
        console.log(`${DIM}These pass the context through an env var — this is the correct pattern.${RESET}\n`);

        const byFile = groupBy(safe, (f) => f.file);
        for (const [file, findings] of Object.entries(byFile)) {
            console.log(`${BOLD}${CYAN}${path.basename(file)}${RESET}  ${DIM}${file}${RESET}`);
            for (const f of findings) {
                console.log(
                    `  ${GREEN}✓${RESET}  job: ${BOLD}${f.jobName}${RESET}  step ${f.stepIndex}: ${BOLD}${f.stepName}${RESET}`
                );
                console.log(`     ${DIM}context:${RESET} $\{{ ${f.context} }}\n`);
            }
        }
    }

    // ── Summary ──
    console.log(`${BOLD}═══════════════════════════════════════════════════${RESET}`);
    const status = injections.length > 0
        ? `${RED}${BOLD}${injections.length} injection risk(s) found — fix before merging${RESET}`
        : `${GREEN}${BOLD}No direct injection risks found${RESET}`;
    console.log(`  ${status}`);
    if (safe.length > 0) {
        console.log(`  ${GREEN}${safe.length} safe env-block usage(s) detected${RESET}`);
    }
    console.log(`${BOLD}═══════════════════════════════════════════════════${RESET}\n`);
}

function groupBy<T>(arr: T[], key: (item: T) => string): Record<string, T[]> {
    return arr.reduce((acc, item) => {
        const k = key(item);
        (acc[k] = acc[k] ?? []).push(item);
        return acc;
    }, {} as Record<string, T[]>);
}

// ─── Main ─────────────────────────────────────────────────────────────────────

function main() {
    const arg = process.argv[2];
    const workflowsDir = arg
        ? path.resolve(arg)
        : path.join(process.cwd(), ".github", "workflows");

    console.log(`\nScanning: ${workflowsDir}`);

    const files = collectWorkflowFiles(workflowsDir);
    if (files.length === 0) {
        console.error(`No .yml/.yaml files found in: ${workflowsDir}`);
        process.exit(1);
    }

    const allFindings: Finding[] = [];
    for (const file of files) {
        const raw = fs.readFileSync(file, "utf8");
        const findings = findInjections(file, raw);
        allFindings.push(...findings);
    }

    report(allFindings, files);

    // Exit with non-zero if injection risks found (useful in CI)
    const hasRisks = allFindings.some((f) => f.inRunBlock);
    process.exit(hasRisks ? 1 : 0);
}

main();