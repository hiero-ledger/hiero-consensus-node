// SPDX-License-Identifier: Apache-2.0

module.exports = async ({ github, context, core }) => {
  // Get labels associated to the pull request
  const labels = context.payload.pull_request.labels || [];

  // "Run EVM Tests" forces the EVM functional tests (870) regardless of which files changed
  const forceEvmTests = labels.some(l => l.name === 'Run EVM Tests');

  // Check for Run Full CI label first — forces full CI regardless
  if (labels.some(l => l.name === 'Run Full CI')) {
    core.info('Label "Run Full CI" detected — forcing full CI');
    core.setOutput('docs-only', 'false');
    core.setOutput('enable-tests', 'true');
    core.setOutput('workflow-files-changed', 'true');
    core.setOutput('smart-contracts-changed', 'true');
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
    core.setOutput('workflow-files-changed','true');
    core.setOutput('smart-contracts-changed', 'true');
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

  // Any file under .github/workflows is considered a workflow file
  const isWorkflowFile = (filename) => /^\.github\/workflows\//.test(filename);

  // Smart-contract service modules whose changes run the EVM functional tests (870). Only non-doc files count,
  // so a README change under these modules does not deploy 18 solo networks. Other modules that influence EVM
  // behaviour (ContractsConfig in hedera-config, the HTS system-contract handlers in hedera-token-service-impl,
  // hedera-node/configuration, hapi) rely on the "Run EVM Tests" label.
  const isSmartContractFile = (filename) =>
      /^hedera-node\/hedera-smart-contract-service(-impl)?\//.test(filename);

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

  // check for non-doc files under the smart-contract service modules
  const smartContractFiles = nonDocFiles.filter(f => isSmartContractFile(f.filename));
  if (forceEvmTests) {
    core.info('Label "Run EVM Tests" detected — enabling EVM functional tests');
    core.setOutput('smart-contracts-changed', 'true');
  } else if (smartContractFiles.length > 0) {
    core.info(`Found ${smartContractFiles.length} smart-contract service file(s) — enabling EVM functional tests`);
    smartContractFiles.slice(0, 10).forEach(f => core.info(`  - ${f.filename}`));
    core.setOutput('smart-contracts-changed', 'true');
  } else {
    core.setOutput('smart-contracts-changed', 'false');
  }

  // check for all files that are workflow files
  const workflowFiles = files.filter(f => isWorkflowFile(f.filename));
  // if at least one workflow file
  if (workflowFiles.length > 0) {
    core.info(`Found ${workflowFiles.length} workflow file(s)`);
    // log the name of each file
    workflowFiles.slice(0, 10).forEach(f => core.info(`  - ${f.filename}`));
    // set the output var to true
    core.setOutput('workflow-files-changed', 'true');
  } else {
    // set the output var to false
    core.setOutput('workflow-files-changed', 'false');
  }
};
