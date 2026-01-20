// SPDX-License-Identifier: Apache-2.0
plugins {
    id("org.hiero.gradle.module.application")
    id("org.hiero.gradle.feature.shadow")
}

mainModuleInfo { runtimeOnly("org.junit.jupiter.engine") }

application.mainClass = "com.hedera.statevalidation.StateOperatorCommand"

tasks.shadowJar {
    isZip64 = true

    // Prevent duplicate log4j2.xml files from dependencies (which disable logging) from overwriting
    // this module's configuration.
    mergeServiceFiles()
    filesNotMatching("META-INF/services/**") { duplicatesStrategy = DuplicatesStrategy.EXCLUDE }
}
