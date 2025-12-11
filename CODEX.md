# Codex Agent Prerequisites

- **Java 21** – Use the Temurin distribution. Install with `brew install --cask temurin@21` and export `JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home` (add `JAVA_HOME/bin` to `PATH`) before running Gradle.
- **Gradle commands** – Execute from the repository root. Target modules directly (e.g., `./gradlew :hiero-clpr-interledger-service-impl:test`) without prefixing with `:hedera-node`.
- **AI Kernel Layout** – Guardrails and workflows now live under `docs/ai/system/`, tutorials and how-tos under `docs/ai/diataxis/`, and resumable artifacts under `docs/ai/tasks/`. Always consult these locations before editing or creating AI-related files.
- **Sandbox limits** – Long-running Gradle builds may need elevated permissions; rerun with `with_escalated_permissions=true` if the initial attempt fails.
