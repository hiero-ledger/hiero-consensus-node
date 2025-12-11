---

title: Gradle Conventions for AI Agents
status: Draft
diataxis: reference
audience: [Human contributors, AI agents]
last-validated: 2025-10-27
--------------------------

# Gradle Conventions for AI Agents

This document outlines key conventions and commands for interacting with the Gradle build system in this repository.

## 1. Faster Compilation for Debugging

When you encounter compilation errors after modifying code, you can compile the entire project without running any tests to get faster feedback. Use the `assemble` task for this purpose:

```bash
./gradlew assemble
```

This is significantly faster than running a full `./gradlew build`, which also executes all unit and integration tests.

## 2. Running Targeted Unit Tests

When modifying a specific module, it is often sufficient to run only the unit tests for that module. Running the entire test suite is unnecessary unless the changes have downstream dependencies. To run tests for a specific project, use its full path.

For example:

```bash
./gradlew :hiero-clpr-interledger-service-impl:test
```

## 2.a. Run a single test (class / method / pattern)

After creating or modifying a unit test, always run that test immediately to verify it compiles and passes before making further changes or opening a PR. Running a single test is also useful to avoid running unrelated integration tests that may be flaky or require environment setup.

- Run a single test class (example):

```bash
# Run the named test class in the current project (root-level test selection)
./gradlew test --tests com.example.MyTestClass

# Or target a specific project/module (recommended in this mono-repo):
./gradlew :app:test --tests com.example.MyTestClass
```

- Run a single test method in a class (example):

```bash
# Class#method syntax (root-level)
./gradlew test --tests com.example.MyTestClass#testMethodName

# Module-scoped version (recommended):
./gradlew :app:test --tests com.example.MyTestClass#testMethodName
```

- Run tests matching a pattern (example):

```bash
# Glob-style pattern - note the quotes to avoid shell expansion
./gradlew test --tests "*SomeSpecificTest"

# Module-scoped pattern
./gradlew :app:test --tests "*SomeSpecificTest"
```

Notes and tips
- Prefer the module-scoped form (`:module:test`) in this repository to limit execution to the module you're working on and avoid long runs or unrelated integration tests.
- When you add a new test class or method, run the test immediately (compile + execute) to catch missing imports, incorrect mocks, or environment dependencies early:
- Compile-only (fast check): `./gradlew :app:compileTestJava`
- Run the focused test: `./gradlew :app:test --tests com.hedera.path.ToYourTestClass#yourTestMethod`
- If a focused test still fails while the rest of the module builds, keep iterating locally until it passes. Only open PRs when the local focused test(s) you added pass reliably.
- Use quoting for patterns to avoid shell expansion; on zsh/bash prefer double quotes.

## 5. Always run the unit test you just created

A mandatory local convention: after creating any new unit test, run the test immediately (module-scoped) before committing. This reduces cycles caused by trivial mistakes and flaky integration test interference. Example workflow for adding `MyNewTest` in `:app`:

```bash
# 1) compile the test code
./gradlew :app:compileTestJava

# 2) run only the new test class (or specific method)
./gradlew :app:test --tests com.hedera.node.app.path.MyNewTest
# or for a method
./gradlew :app:test --tests com.hedera.node.app.path.MyNewTest#myNewTestMethod
```

If the focused test fails but `:app:compileTestJava` succeeds, inspect the test for runtime mock/setup errors rather than re-running the whole repository's test suite.

## 3. Project Names

Below is a list of all projects in this repository. Use these exact names when targeting Gradle tasks.

- :aggregation
- :app (Hedera Application - Implementation)
- :app-hapi-fees (Hedera Services API Fees)
- :app-hapi-utils (Hedera Services API Utilities)
- :app-service-addressbook (Hedera AddressBook Service API)
- :app-service-addressbook-impl (Default Hedera AddressBook Service Implementation)
- :app-service-consensus (Hedera Consensus Service API)
- :app-service-consensus-impl (Default Hedera Consensus Service Implementation)
- :app-service-contract (Hedera Smart Contract Service API)
- :app-service-contract-impl (Default Hedera Smart Contract Service Implementation)
- :app-service-entity-id (Hedera Entity ID Service API)
- :app-service-entity-id-impl (Entity ID Service Implementation)
- :app-service-file (Hedera File Service API)
- :app-service-file-impl (Default Hedera File Service Implementation)
- :app-service-network-admin (Hedera NetworkAdmin Service API)
- :app-service-network-admin-impl (Default Hedera Network Admin Service Implementation)
- :app-service-roster (Hedera Roster Service API)
- :app-service-roster-impl (Hedera Roster Service Implementation)
- :app-service-schedule (Hedera Schedule Service API)
- :app-service-schedule-impl (Default Hedera Schedule Service Implementation)
- :app-service-token (Hedera Token Service API)
- :app-service-token-impl (Default Hedera Token Service Implementation)
- :app-service-util (Hedera Util Service API)
- :app-service-util-impl (Default Hedera Util Service Implementation)
- :app-spi (Hedera Application - SPI)
- :base-concurrent (Base Concurrent)
- :base-crypto (Base Crypto)
- :base-utility (Base Utility)
- :config (Hedera Configuration)
- :consensus-event-creator (Consensus Event Creator API)
- :consensus-event-creator-impl (Default Consensus Event Creator Implementation)
- :consensus-gossip (Consensus Gossip API)
- :consensus-gossip-impl (Default Consensus Gossip Implementation)
- :consensus-model (Consensus Model)
- :consensus-otter-docker-app (Otter Docker App)
- :consensus-otter-tests (Consensus Otter Test Framework)
- :consensus-utility (Consensus Utility)
- :ConsistencyTestingTool
- :hapi (Hedera API)
- :hedera-protobuf-java-api (Hedera Protobuf Java API)
- :hedera-state-validator
- :hiero-clpr-interledger-service (Hiero CLPR Interledger Service API)
- :hiero-clpr-interledger-service-impl (Hiero CLPR Interledger Service Implementation)
- :hiero-dependency-versions
- :ISSTestingTool
- :junit-extensions (Junit Extensions)
- :MigrationTestingTool
- :PlatformTestingTool
- :StatsDemo
- :swirlds
- :swirlds-base
- :swirlds-benchmarks
- :swirlds-cli
- :swirlds-common
- :swirlds-component-framework (Component Framework)
- :swirlds-config-api
- :swirlds-config-extensions
- :swirlds-config-impl
- :swirlds-config-processor
- :swirlds-fchashmap
- :swirlds-fcqueue
- :swirlds-logging
- :swirlds-logging-log4j-appender
- :swirlds-merkle
- :swirlds-merkledb
- :swirlds-metrics-api
- :swirlds-metrics-impl
- :swirlds-platform
- :swirlds-platform-base-example
- :swirlds-platform-core
- :swirlds-state-api
- :swirlds-state-impl
- :swirlds-virtualmap
- :test-clients (Hedera Services Test Clients for End to End Tests (EET))
- :yahcli (Hedera Execution YahCli Tool)

## 4. Code formatting

To apply automatic code formatting across the repository, run:

```bash
./gradlew spotlessApply
```

This will apply the project's configured formatting rules (Spotless). Running this before committing helps avoid style-related build failures.

## Codex Sandbox Java Setup

GPT-Codex runs in a filesystem sandbox that may not ship with a JDK. When Gradle reports `Unable to locate a Java Runtime`, install Temurin 21 and export `JAVA_HOME` before rerunning Spotless or other tasks.

```bash
# Install Java 21 (requires Homebrew access)
brew install --cask temurin21

# Point JAVA_HOME and PATH at the new runtime
export JAVA_HOME="/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"

# Verify
java -version
```

If Homebrew casks are unavailable in the sandbox, ask the user to install a JDK manually and provide the absolute path. Once installed, codex can run Gradle commands such as `./gradlew spotlessApply` using `with_escalated_permissions=true` when necessary.

## Common Commands

| Command | When to Use | Notes |
|---------|-------------|-------|
| `./gradlew assemble` | Fast compile without running tests | Ideal for checking compilation after code edits |
| `./gradlew :<module>:test` | Run all tests for a specific module | Replace `<module>` with entries from the project list above |
| `./gradlew :<module>:test --tests com.example.MyTest` | Run a single test class or method | Useful immediately after authoring new tests |
| `./gradlew spotlessApply` | Apply formatting fixes | Run before commits to avoid style build failures |
| `./gradlew projects` | List available modules | Helps confirm module names before targeted commands |
| `./gradlew :<module>:compileJava` | Quick compile for a module | Faster feedback than running the full test suite |
