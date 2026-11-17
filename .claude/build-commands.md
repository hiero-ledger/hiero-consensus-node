# Key Build Commands

One build: the Gradle wrapper at the root, with the [Hiero Gradle Conventions](https://github.com/hiero-ledger/hiero-gradle-conventions#build) providing the lifecycle tasks. `docs/gradle-quickstart.md` is the human guide; `./gradlew` with no arguments prints the task overview. The workflow tests are plain shell scripts.

Gradle project paths use the **artifact name**, not always the folder name: `hedera-node/hedera-app` is `:app`, `hedera-node/hedera-token-service-impl` is `:app-service-token-impl` (the `module(...)` mappings in `settings.gradle.kts`). Folders under `platform-sdk/` use their folder name (`:consensus-gossip`, `:swirlds-platform-core`). Prefer a project-scoped task (`:<project>:<task>`) to a root task, which runs on every module.

## Build and format

Needs only a JDK matching `gradle/toolchain-versions.properties` (Java 25); the wrapper downloads Gradle.

- `./gradlew assemble` — compile all code and build every jar. CI's compile check runs `./gradlew assemble :yahcli:fatModuleJar` (`802-call-compile-and-spotless-check.yaml`).
- `./gradlew qualityGate` — `assemble`, every quality check, and auto-fix of formatting where possible. Run it, or at least `spotlessApply`, before committing.
- `./gradlew spotlessApply` / `spotlessCheck` — format, or check the format of, Java (Palantir Java format), Kotlin, YAML under `.github/`, Markdown (flexmark) and `.properties` files, adding missing `SPDX-License-Identifier: Apache-2.0` headers. CI runs `spotlessCheck`. Files under dot-directories (such as `.claude/`) are not formatted.
- `./gradlew checkAllModuleInfo checkVersionConsistency validatePomFiles` — the dependency and module checks CI runs (`809-call-dependency-module-check.yaml`): every `requires` in `module-info.java` matches the build's dependencies, and every version comes from `hiero-dependency-versions`.
- `./gradlew clean` — remove `build/` outputs; not needed for a correct build.

## Tests

Each module can have several test source sets (`docs/gradle-quickstart.md#testing`):

- `./gradlew :<project>:test` — unit tests (`src/test`). Always run in PR builds. CI runs them all with coverage through `./gradlew :aggregation:testCodeCoverageReportPartitioned` (`803`).
- `./gradlew :<project>:testIntegration` — integration tests (`src/testIntegration`, in only a few modules); CI runs the root `testIntegration` (`804`).
- `./gradlew :<project>:timingSensitive` — tests that are sensitive to timing (`src/timingSensitive`), run apart from the unit tests (`806`).
- `./gradlew :<project>:hammer` — long-running stress tests (`src/hammer`, `807`).
- `./gradlew :<project>:jmh` — JMH micro-benchmarks (`src/jmh`); `jmhSmoke` runs every benchmark of a module once, briefly, and is the smoke run in MATS (`812`). The `hcn-benchmark-work-package` skill covers benchmark work.
- `./gradlew :<project>:test -PrunUntilFailure=50` — rerun a test until it fails, to find a flaky one.

### HAPI tests (`hedera-node/test-clients`)

The HAPI tests start a network and send it real transactions. `.github/workflows/docs/adding-hapi-tests.md` describes how to add one.

- `./gradlew :test-clients:testSubprocess --tests '<FQCN>'` — run tests against a local subprocess network; `testSubprocessConcurrent` runs them concurrently.
- `./gradlew :test-clients:testEmbedded --tests '<FQCN>'` — run tests marked `EMBEDDED` in-process; `testRepeatable` runs tests on an embedded network in a single thread, for repeatable results.
- `./gradlew :test-clients:hapiTest<Group>` — one CI group (`hapiTestCrypto`, `hapiTestToken`, `hapiTestMisc`, `hapiTestSmartContract`, `hapiTestRestart`, `hapiTestNDReconnect`, …), selected by the JUnit tag expressions in `prCheckTags` and its siblings in `hedera-node/test-clients/build.gradle.kts`.
- `./gradlew :test-clients:runTestClient -PtestClient=com.hedera.services.bdd.suites.crypto.HelloWorldSpec` — run one suite against a running node.

### Otter tests (`platform-sdk/consensus-otter-tests`)

- `./gradlew :consensus-otter-tests:testOtter` — the Otter consensus tests; `testTurtle` runs them in the in-process Turtle simulation and `testContainer` in containers, `testFalcon` the `falcon`-tagged set, and `testChaos` the chaos tests. The fast set runs in MATS (`811`, `847`).

## Run

- `./gradlew :app:run` — run one local services node (`-local 0`) from `hedera-node/hedera-app/build/node/`, with the configuration copied from `hedera-node/configuration/dev/` and block streams written to files. `./gradlew :app:cleanRun` removes its state. (`docs/gradle-quickstart.md` still says `modrun`; the task is `run`.)

## Workflow tests (`.github/workflows/support/tests/`)

Plain Bash (and one Node.js) tests for the workflow support scripts, run by `857-call-workflow-unit-tests.yaml`. Run one from the repository root:

```bash
bash .github/workflows/support/tests/test-resolve-ref.sh
```

Add a test here when you add or change a script under `.github/workflows/support/scripts/`, and add its step to `857`.
