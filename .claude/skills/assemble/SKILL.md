---
name: assemble
description: Build the project or a specific module using Gradle. Use when the user wants to compile code.
---

Build the project or a specific module.

If the user specifies a module name, run `./gradlew :<module-name>:assemble`.
If no module is specified, run `./gradlew assemble` for the full project.

After the build completes, summarize the result: success/failure, any errors or warnings worth noting.

If the build fails, analyze the error output and suggest fixes. Common issues:
- Wrong JDK version (must be JDK 21 Temurin)
- Missing module-info.java declarations
- Checkstyle/formatting violations (suggest running `/quality` to auto-fix)

Argument: $ARGUMENTS (optional module name, e.g. "hedera-token-service-impl")
