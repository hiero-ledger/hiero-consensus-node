// SPDX-License-Identifier: Apache-2.0
plugins {
    id("org.hiero.gradle.module.library")
    id("org.hiero.gradle.feature.protobuf")
    id("org.hiero.gradle.feature.test-fixtures")
    id("com.hedera.pbj.pbj-compiler") version "0.10.3"
}

description = "Hedera API"

sourceSets {
    val protoApiSrc = "../hedera-protobuf-java-api/src/main/proto"
    main {
        pbj {
            srcDir(layout.projectDirectory.dir(protoApiSrc))
            exclude("mirror", "sdk")
        }
    }
}

testModuleInfo {
    requires("com.hedera.node.hapi")
    requires("com.hedera.node.hapi.test.fixtures")
    // we depend on the protoc compiled hapi during test as we test our pbj generated code
    // against it to make sure it is compatible
    requires("com.hedera.protobuf.java.api")
    requires("com.google.protobuf")
    requires("com.google.protobuf.util")
    requires("org.junit.jupiter.api")
    requires("org.junit.jupiter.params")
}

tasks.test {
    // We are running a lot of tests (10s of thousands), so they need to run in parallel. Make each
    // class run in parallel.
    systemProperties["junit.jupiter.execution.parallel.enabled"] = true
    systemProperties["junit.jupiter.execution.parallel.mode.default"] = "concurrent"
    // limit amount of threads, so we do not use all CPU
    systemProperties["junit.jupiter.execution.parallel.config.dynamic.factor"] = "0.9"
    // us parallel GC to keep up with high temporary garbage creation,
    // and allow GC to use 40% of CPU if needed
    jvmArgs("-XX:+UseParallelGC", "-XX:GCTimeRatio=90")
}
