// SPDX-License-Identifier: Apache-2.0
plugins { id("org.hiero.gradle.module.application") }

application.mainClass = "com.swirlds.demo.stats.StatsDemoMain"

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:-dangling-doc-comments")
}
