// SPDX-License-Identifier: Apache-2.0
plugins {
    id("java-library")
    id("jacoco")
    id("org.hiero.gradle.base.jpms-modules")
    id("org.hiero.gradle.base.lifecycle")
    id("org.hiero.gradle.base.version")
    id("org.hiero.gradle.check.dependencies")
    id("org.hiero.gradle.check.javac-lint")
    id("org.hiero.gradle.check.spotless")
    id("org.hiero.gradle.check.spotless-java")
    id("org.hiero.gradle.check.spotless-kotlin")
    id("org.hiero.gradle.feature.git-properties-file")
    id("org.hiero.gradle.feature.java-compile")
    id("org.hiero.gradle.feature.java-execute")
    id("org.hiero.gradle.feature.test")
    id("org.hiero.gradle.report.test-logger")
    id("org.hiero.gradle.feature.test-fixtures")
    id("org.hiero.gradle.feature.protobuf")
}

description = "Otter Docker App"

testFixturesModuleInfo {
    runtimeOnly("io.netty.transport.epoll.linux.x86_64")
    runtimeOnly("io.netty.transport.epoll.linux.aarch_64")
    runtimeOnly("io.helidon.grpc.core")
    runtimeOnly("io.helidon.webclient")
    runtimeOnly("io.helidon.webclient.grpc")
}

tasks.testFixturesJar {
    inputs.files(configurations.testFixturesRuntimeClasspath)
    manifest { attributes("Main-Class" to "org.hiero.consensus.otter.docker.app.DockerInit") }
    doFirst {
        manifest.attributes(
            "Class-Path" to
                inputs.files
                    .filter { it.extension == "jar" }
                    .map { "../lib/" + it.name }
                    .sorted()
                    .joinToString(separator = " ")
        )
    }
}

tasks.register<Sync>("copyTestFixturesLib") {
    from(configurations.testFixturesRuntimeClasspath)
    into(layout.buildDirectory.dir("data/lib"))
}

tasks.register<Sync>("copyTestFixturesApp") {
    from(tasks.testFixturesJar)
    into(layout.buildDirectory.dir("data/apps"))
    rename { "DockerApp.jar" }
}

tasks.assemble {
    dependsOn("copyTestFixturesLib")
    dependsOn("copyTestFixturesApp")
}
