# Branch naming conventions

## Policy

Source Code Management (SCM) systems typically operate on the concept of branches. Although the
exact representation of branches may vary between ecosystems, most of the industry standard
solutions provide some form of branches.

Hedera Hashgraph has elected to adopt a branch naming standard as outlined in this policy.

## Purpose

The Git SCM system treats branches as one of the fundamental constructs for supporting concurrent
development and assisting with merge conflict resolution. The Git SCM system imposes very minimal
constraints on how a branch may be named. Due to the minimal constraints natively imposed by Git and
the lack of native mechanisms for linking branches to issues, it is critical to have a consistent
naming standard.

## Definition

### Permanent & Default Branches

The repository will contain one permanent branch, `main`, per the Hashgraph Continuous Integration
Test and Release workflow

**Default Branches**

The default branch for a repository will be `main` as per the Hashgraph Continuous Integration
Test and Release workflow.

### Branch to Issue Relationship

Aside from the permanent or release branches, no short-lived (feature, hotfix, bugfix) branch should
be created without being associated to an issue number. No short-lived branch should be merged into
`main` without an associated and approved pull request.

### Feature Branch Naming

All feature, hotfix, and bugfix branches regardless of the workflow must use the following naming
convention:

`<ticket_number>-<short_desc>`

The `<ticket_number>` should be the 5 digit left zero-padded Github issue number for which the
branch was created.

The `<short_desc>` should be a brief and informative description of the branch/issue not exceeding
20 alphanumeric characters and dashes. Back slashes and forward slashes are not permitted.

**Examples:**

`04647-eventflow-design`

`00034-refactor-incron`

### Long-Lived Feature Branches

Although long-lived feature branches should typically be avoided, there are some valid cases where a
long-lived feature branch might be necessary. If a long-lived feature branch is deemed necessary,
then a parent issue should be created and attached to the long-lived feature branch following the
naming guidelines for normal feature branches.

Short-lived feature branches related to a long-lived branch should be tied to a unique issue and the
associated branch name should follow the naming guidelines for normal feature branches.

### Release Branch Naming

Release branches are a special form of long-lived branches with a varying lifespan depending on the
adopted workflow and individual project requirements. All release branches regardless of the
workflow must use the following naming standard:

`release/<version_major>.<version_minor>`

The `<version_major>` and `<version_minor>` values should follow the
[Semantic Versioning Guidelines](https://www.semver.org/).

**Examples:**

`release/0.6`

`release/1.8`

### Tag Naming

Tags typically can be used for multiple purposes; however, due to using GitHub as the remote
repository tags are typically limited to an intrinsic one-to-one relationship to releases.

All repositories should only use tags for versioned releases. Tag names for releases must adopt the
following naming standard:

`v<sem_version>`

The `<sem_version>` values should follow the
[Semantic Versioning Guidelines](https://www.semver.org/). However, the prerelease specification
must be constrained to `alpha`, `beta`, or `rc` identifiers.

**Examples:**

`v0.6.0`

`v1.8.1`

`v0.24.0-alpha.1`

`v1.8.1-rc.0`

`v2.1.99-beta.8`
