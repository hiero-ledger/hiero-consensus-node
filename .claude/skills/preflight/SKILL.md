---
name: preflight
description: Pre-commit/pre-push gate. Auto-fixes formatting, runs quality checks on changed modules, and reviews your diff for missing registrations, wrong annotations, and other completeness issues.
---

Run a two-phase preflight check on the current changes before committing/pushing.

## Phase 1: Quality Checks (auto-fix + validate)

1. Run `git diff --name-only HEAD` (and `git diff --name-only --cached` for staged files) to identify all changed files.
2. Determine which Gradle modules are affected by extracting the module path from each changed file path (e.g., `hedera-node/hedera-token-service-impl/src/...` → `:hedera-token-service-impl`).
3. Run `./gradlew spotlessApply` on the affected modules to auto-fix formatting. Report what files were modified by Spotless.
4. Run `./gradlew :<module>:checkstyleMain` for each affected module. If violations are found, read the violation report and explain each violation with how to fix it.
5. Run `./gradlew :<module>:pmdMain` for each affected module. Same — explain any violations.

If no Java files changed, skip Phase 1.

## Phase 2: Completeness Review

Analyze the full `git diff` to understand what was changed, then check for completeness based on what the diff contains:

### If a new TransactionHandler class was added:
Verify it is registered in ALL of these locations (check each file):
- `hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/dispatcher/TransactionDispatcher.java` — the `getHandler()` switch must include the new `HederaFunctionality` case
- `hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/dispatcher/TransactionDispatcher.java` — the `shouldUseSimpleFees()` switch must include the new case
- `hedera-node/hedera-app/src/main/java/com/hedera/node/app/workflows/handle/HandleWorkflowModule.java` — the handler must be wired into `TransactionHandlers`
- The service's `XxxHandlers` aggregation class (e.g., `TokenHandlers`) must include the new handler
- The service's `ServiceImpl` class must register a fee calculator in `serviceFeeCalculators()`
- Report any missing registrations with the exact file path and what needs to be added.

### If new state or a new store was added:
- Verify a schema version class exists. Read `version.txt` to check the current version. The schema class name follows the convention: version `0.72.0` → `V0720XxxSchema`.
- Verify `hedera-node/hedera-app/src/main/java/com/hedera/node/app/store/ReadableStoreFactoryImpl.java` includes the new readable store interface.
- If a writable store was added, verify it is registered in the writable store factory too.
- Verify the schema is registered in the service's `registerSchemas()` method.

### If a new config property was added:
- Verify `@ConfigProperty` annotation has correct `value` and `defaultValue` attributes.
- If the property should be dynamically changeable, verify `@NetworkProperty` is present.

### If new packages or modules were added:
- Verify `module-info.java` has the correct `exports` and `requires` declarations.
- Check that `testModuleInfo` in `build.gradle.kts` includes any new test dependencies.

### If protobuf files were changed:
- Verify that code generation has been run (generated Java files should reflect the proto changes).

### Always check for common mistakes in changed files:
- **Wrong annotations**: Flag any use of `javax.annotation.Nullable/NonNull` or `org.jetbrains.annotations.*` — this project uses `edu.umd.cs.findbugs.annotations.NonNull` and `edu.umd.cs.findbugs.annotations.Nullable` exclusively.
- **Missing `@Override`**: Flag any method that overrides a parent but lacks the annotation.
- **Missing license header**: Every source file must start with `// SPDX-License-Identifier: Apache-2.0`.
- **Wildcard imports**: All imports must be explicit, no `import foo.*`.
- **Line length**: Flag lines exceeding 120 characters (Checkstyle enforces this).

### Check branch naming convention:
- Run `git branch --show-current` and verify it matches `<5-digit-ticket>-<short-desc>` (e.g., `04647-eventflow-design`).
- If it does not match, warn but do not block.

## Output

Provide a clear summary with sections:
1. **Formatting**: What Spotless auto-fixed (if anything)
2. **Quality violations**: Checkstyle/PMD issues with explanations and fixes
3. **Completeness**: Missing registrations or wiring, with exact file paths
4. **Code issues**: Wrong annotations, missing headers, etc.
5. **Branch**: Whether naming convention is followed
6. **Verdict**: "Ready to commit" or "Issues to address" with a prioritized list

Argument: $ARGUMENTS (unused, no arguments needed)
