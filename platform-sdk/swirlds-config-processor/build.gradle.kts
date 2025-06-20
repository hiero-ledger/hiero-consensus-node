// SPDX-License-Identifier: Apache-2.0
plugins {
    id("org.hiero.gradle.module.library")
    id("org.hiero.gradle.feature.publish-artifactregistry")
}

mainModuleInfo { annotationProcessor("com.google.auto.service.processor") }

testModuleInfo { requires("org.junit.jupiter.api") }

tasks.register<AntlrTask>("generateParser") {
    conventionMapping.map("antlrClasspath") {
        configurations.detachedConfiguration(dependencies.create("org.antlr:antlr4:4.13.1"))
    }
    source(layout.projectDirectory.dir("src/main/antlr"))
    outputDirectory =
        File(
            sourceSets.main.get().java.sourceDirectories.singleFile,
            "com/swirlds/config/processor/antlr/generated"
        )
}
