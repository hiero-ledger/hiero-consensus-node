# Forked PRs README.md

In the `hiero-consensus-node` project there are several workflow jobs that must run
prior to merging a pull request. These jobs are defined in the `.github/workflows`
directory and have rulesets associated at the `hiero-consensus-repository` github
repository level.

Several of these jobs are required to run on pull requests that are opened against the
`hiero-consensus-node` repository. Some of these jobs must access repository or organization
github secrets and variables.

Github limits access to repository secrets in order to
[prevent pwn requests](https://securitylab.github.com/resources/github-actions-preventing-pwn-requests/).
The only secret that is passed to a forked PR is the GITHUB_TOKEN, which is the default token for the PR.
All other secrets are not passed to the forked PR which means that these jobs require either an override
from a known user or a different method of running the jobs.

By default, the `hiero-consensus-node` repository will not run any jobs that require access to secrets if
they are sourced from a forked PR.

## Workflows with forked PR checks

See the file [required_checks.md](required_checks.md) to see which jobs can run on forked PRs and which jobs cannot.
