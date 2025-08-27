// SPDX-License-Identifier: Apache-2.0
plugins {
    id("org.hiero.gradle.module.library")
    id("com.hedera.pbj.pbj-compiler") version "0.11.13"
}

description = "Open Telemetry Converter"

testModuleInfo {
    requires("com.hedera.node.hapi")
    // we depend on the protoc compiled hapi during test as we test our pbj generated code
    // against it to make sure it is compatible
    requires("com.google.protobuf.util")
    requires("org.junit.jupiter.api")
    requires("org.junit.jupiter.params")
    requires("org.assertj.core")
}

pbj {
    generateTestClasses = false
}

tasks.register<Exec>("cleanAndRestartTempo") {
    commandLine("bash", "-c", "rm -rf docker/tmp/tempo-data && docker restart docker-tempo-1")
}