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
    runtimeOnly("io.grpc.netty.shaded")
}

// This should probably not be necessary (Log4j issue?)
// https://github.com/apache/logging-log4j2/pull/3053
tasks.compileTestFixturesJava {
    options.compilerArgs.add("-Alog4j.graalvm.groupId=${project.group}")
    options.compilerArgs.add("-Alog4j.graalvm.artifactId=${project.name}")
}

tasks.testFixturesJar {
    inputs.files(configurations.testFixturesRuntimeClasspath)
    manifest { attributes("Main-Class" to "org.hiero.consensus.otter.docker.app.DockerMain") }
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

tasks.register<Sync>("copyDockerizedApp") {
    into(layout.buildDirectory.dir("data"))
    from(layout.projectDirectory.file("src/testFixtures/docker/Dockerfile"))
    into("apps") {
        from(tasks.testFixturesJar)
        rename { "DockerApp.jar" }
    }
    into("lib") { from(configurations.testFixturesRuntimeClasspath) }
}

tasks.assemble { dependsOn("copyDockerizedApp") }
