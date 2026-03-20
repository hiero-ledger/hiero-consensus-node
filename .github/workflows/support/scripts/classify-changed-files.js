module.exports = async ({ github, context, core }) => {
  const labels = context.payload.pull_request.labels || [];
  // Check for run-full-ci label first — forces full CI regardless
  if (labels.some(l => l.name === 'run-full-ci')) {
    core.info('Label "run-full-ci" detected — forcing full CI');
    core.setOutput('docs-only', 'false');
    core.setOutput('enable-tests', 'true');
    return;
  }

  // Fetch changed files (first page only, max 100)
  const { data: files } = await github.rest.pulls.listFiles({
    owner: context.repo.owner,
    repo: context.repo.repo,
    pull_number: context.issue.number,
    per_page: 100
  });

  // Conservative: if no files or >=100 files, run full CI
  if (files.length === 0 || files.length >= 100) {
    core.info(`File count (${files.length}) triggers full CI (conservative)`);
    core.setOutput('docs-only', 'false');
    core.setOutput('enable-tests', 'true');
    return;
  }

  // Conservative allowlist: only these patterns are considered documentation
  const docPatterns = [
    /\.md$/i,                // Markdown files
    /^LICENSE(\..*)?$/i,     // LICENSE, LICENSE.txt, etc.
    /^NOTICE(\..*)?$/i,      // NOTICE, NOTICE.txt, etc.
    /(^|\/)docs\//,          // Files in docs/ directories
    /(^|\/)doc\//,           // Files in doc/ directories
  ];

  const isDocFile = (filename) =>
      docPatterns.some(pattern => pattern.test(filename));

  const nonDocFiles = files.filter(f => !isDocFile(f.filename));

  if (nonDocFiles.length > 0) {
    core.info(`Found ${nonDocFiles.length} non-doc file(s) — full CI required`);
    nonDocFiles.slice(0, 10).forEach(f => core.info(`  - ${f.filename}`));
    core.setOutput('docs-only', 'false');
    core.setOutput('enable-tests', 'true');
  } else {
    core.info(`All ${files.length} changed file(s) are documentation — docs-only mode`);
    core.setOutput('docs-only', 'true');
    core.setOutput('enable-tests', 'false');
  }
};
