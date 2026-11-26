# Directory Structure

The repository is one Gradle build of about 125 JPMS modules in three main groups (`hapi/`, `hedera-node/`, `platform-sdk/`), plus the build logic, CI and agent setup around them. This file is the index: what each top-level directory and module group is for. It does not list every module. For a `platform-sdk` module, read its own `CLAUDE.md` (most `consensus-*` modules have one) and `platform-sdk/CLAUDE.md`; for a `hedera-node` service, its `README.md` and `hedera-node/docs/`. Update this file in the same pull request that adds a top-level directory or a module group.

## Top level

- `CLAUDE.md`, `AGENTS.md` (a symlink to `CLAUDE.md`), `.claude/` — agent guidance: `instructions.md`, `build-commands.md`, `directory-structure.md`, `conventions.md`, `git-hooks.md`, `settings.json` (shared allowed and denied commands; personal overrides go in the gitignored `settings.local.json`), and `skills/`: `hcn-benchmark-work-package`, `hcn-chewie-context`, `hcn-citr-context`, `hcn-consensus-pr-review` and `gh-stack-workflow`. `.github/ai/instructions.md` and `.github/copilot-instructions.md` point GitHub Copilot at `.claude/instructions.md`.
- `settings.gradle.kts` — the build's module list: `javaModules { directory(...) }` discovers every module under `hapi/`, `platform-sdk/`, `hedera-node/` and `hiero-observability/`, and maps folder names to artifact names where they differ. `gradle.properties`, `gradlew`, `gradlew.bat` and `gradle/wrapper/` are the wrapper.
- `gradle/` — build logic: `toolchain-versions.properties` (the JDK), `modules.properties`, `aggregation/` (the coverage and aggregate-report project, `:aggregation`), `benchmark-tracking/` (an included build with `jmhSmoke` and benchmark tracking), `besu-native-patch/`, and `scripts/`.
- `hiero-dependency-versions/` — the platform project that declares every external library version, once.
- `config/` — static analysis rule sets: `checkstyle/`, `pmd/` and `detekt/`.
- `docs/` — repository-wide developer guides: `gradle-quickstart.md`, `intellij-quickstart.md`, `maintainers-guide.md` (GPG and DCO setup, development model), `branch-naming-conventions.md`, `hedera-java-style-guide.md`, `continuous-integration-testing-overview.md`, `continuous-release.md`, `glossary.md`, `clpr/`, and `superpowers/plans/` (design plans).
- `hedera-state-validator/` — a standalone tool that validates a saved state; built and published by `816-call-build-publish-state-validator.yaml`.
- `hiero-observability/` — `hiero-metrics`, `metrics-file-exporter` and `openmetrics-httpserver` (the metrics API and its exporters), and `local-stack/` (a Docker Compose Prometheus and Grafana stack for local runs).
- `startup-assets/` — network startup assets; `.archive/` holds a retired genesis network file.
- `version.txt` — the version being built on this branch (`./gradlew showVersion`), raised by `003-user-increment-next-main-release.yaml`.
- `README.md`, `LICENSE` (Apache 2.0), `MAINTAINERS.md`, `codecov.yml`, `.pre-commit-config.yaml` (end-of-file, merge-conflict, `spotlessApply` and Conventional Commits hooks), `.remarkrc` (Markdown lint), `.trunk/`.
- `.codeowners` and `.github/CODEOWNERS` — ownership, in two files that must change together (`CLAUDE.md`, CODEOWNERS).

## `hapi/` — the Hiero API

- `hapi/hedera-protobuf-java-api/src/main/proto/` — the `.proto` sources: `services/` (HAPI transactions, queries and state), `block/` (block streams), `streams/` (record streams), `platform/` (platform state and events), `fees/`, and `mirror/` and `sdk/` (excluded from the node's model).
- `hapi/hapi/` — the generated Java model (`:hapi`): PBJ compiles the sources above, plus the block-node protobufs extracted from the `org.hiero.block-node:protobuf-sources` dependency, during the build.

## `hedera-node/` — the services layer

- `hedera-app/` (`:app`) — the node application: startup, gRPC, workflows (ingest, pre-handle, handle, query), the state and block-stream machinery, throttling and fees. `hedera-app-spi/` (`:app-spi`) is the API services implement.
- `hedera-<service>-service/` and `hedera-<service>-service-impl/` — one API and one implementation module per service: `token`, `smart-contract` (the EVM, built on Besu), `consensus`, `file`, `schedule`, `network-admin`, `addressbook`, `roster`, `entity-id`, `util` and `clpr` (the Cross Ledger Protocol). Artifact names are `app-service-<name>[-impl]` (`settings.gradle.kts`).
- `hedera-config/` (`:config`) — the services' `@ConfigData` records (`com.hedera.node.config.data`).
- `hapi-fees/`, `hapi-utils/` — fee calculation and HAPI helpers shared by the services.
- `test-clients/` — the HAPI test framework and suites (`com.hedera.services.bdd`), and the tasks that run them (`.claude/build-commands.md`).
- `yahcli/` — "yet another Hedera CLI", the operator tool for administering a network; published as an image by `304-flow-publish-yahcli-image.yaml`.
- `configuration/` — per-environment node configuration (`dev/`, `mainnet/`, `testnet/`, `previewnet/`, `preprod/`, `compose/`, `update/`): properties, throttles, fee schedules and genesis files. Production configuration is owned separately in CODEOWNERS.
- `data/` — onboarding data and keys used by local runs; `infrastructure/` — Docker, Grafana, OpenTelemetry Collector and Prometheus assets; `tools/` — Python and shell helpers (CLPR identity and Besu QBFT payloads, fee schedules); `docs/` — services design documents.

## `platform-sdk/` — the consensus layer

`platform-sdk/CLAUDE.md` defines the module categories (base, supporting, functional-api, functional-impl, self-contained, structural-transitional) and the modularization rules that decide where a dependency may go. `platform-sdk/README.md` and `platform-sdk/docs/` (including the consensus-layer knowledge base in `docs/consensus-layer/`) hold the design documents.

- `base-*` — foundation utilities with no non-base dependencies: `base-concurrent`, `base-crypto`, `base-utility`.
- `consensus-*` — the modularized consensus layer: an API module and its `-impl` (and sometimes a `-noop-impl` or `-instrumented` variant) per function: event creation, intake, gossip, hashgraph, PCES (the pre-consensus event stream), reconnect, ISS detection, roster, state and platform state, status monitor, transaction handling, metrics, wiring framework and the rest. `consensus-otter-tests` and `consensus-otter-docker-app` are the Otter test framework; `consensus-fakes`, `consensus-network-simulation` and `consensus-benchmark-tools` support tests and benchmarks; `consensus-kb-freshness` checks the knowledge base against the code.
- `swirlds-*` — the original platform modules, being split into `consensus-*` ones: `swirlds-platform-core`, `swirlds-common`, `swirlds-base`, `swirlds-config-*`, `swirlds-logging*`, `swirlds-metrics-*`, `swirlds-component-framework`, `swirlds-cli`, `swirlds-benchmarks`, and the state and data-structure modules `swirlds-state-*`, `swirlds-virtualmap`, `swirlds-merkledb`, `swirlds-merkle`, `swirlds-fchashmap` and `swirlds-fcqueue`.
- `platform-apps/` — demo and test applications on the platform; `junit-extensions/` — shared JUnit extensions; `sdk/` — the files a standalone platform run uses.

## `.github/`

- `workflows/` — every workflow, named by `.github/workflows/docs/naming-standards.md` (`CLAUDE.md`, CI/CD), plus `docs/` (naming standard, contribution flow, required checks, CITR and Chewie notes, `workflow-manifest.md`), `support/` (`scripts/`, their `tests/`, and the `chewie/` and `citr/` configuration), `templates/` (Slack message templates) and `config/`.
- `chewie.yaml` — the Chewie configuration for the CITR workflows (`hcn-chewie-context`, `hcn-citr-context`).
- `dependabot.yml`, `ISSUE_TEMPLATE/`, `CODEOWNERS`, `copilot-instructions.md` and `ai/instructions.md`.
