// SPDX-License-Identifier: Apache-2.0
plugins { id("org.hiero.gradle.module.library") }

description = "Hedera Services API Utilities"

mainModuleInfo { annotationProcessor("dagger.compiler") }

tasks.withType<JavaCompile>().configureEach { options.compilerArgs.remove("-Werror") }

testModuleInfo {
    requires("org.junit.jupiter.api")
    requires("org.junit.jupiter.params")
    requires("org.mockito")
    requires("org.assertj.core")
}
