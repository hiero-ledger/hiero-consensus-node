// SPDX-License-Identifier: Apache-2.0
plugins {
    id("org.hiero.gradle.module.library")
    id("org.hiero.gradle.feature.protobuf")
    id("org.hiero.gradle.feature.test-fixtures")
    // ATTENTION: keep pbj version in sync with 'hiero-dependency-versions/build.gradle.kts'
    id("com.hedera.pbj.pbj-compiler") version "0.12.1"
}

description = "Hedera API"

// Remove the following line to enable all 'javac' lint checks that we have turned on by default
// and then fix the reported issues.
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:-exports,-deprecation,-removal")
}

// If the 'block-node-protobuf-sources.jar' would also contain the generated Java classes, we could
// replace the 'dependencies' block with a 'requires org.hiero.block.protobuf.sources' entry in
// 'module-info.java'. Then, the 'srcDir(tasks.extractProto)' below inside the 'main { pbj {} }'
// block would not be needed.
dependencies {
    protobuf(platform(project(":hiero-dependency-versions")))
    protobuf("org.hiero.block:block-node-protobuf-sources")
}

sourceSets {
    val protoApiSrc = layout.projectDirectory.dir("../hedera-protobuf-java-api/src/main/proto")
    main {
        pbj {
            srcDir(protoApiSrc)
            srcDir(tasks.extractProto) // see comment on the 'dependencies' block
            exclude("mirror", "sdk", "internal")
        }
        // The below should be replaced with a 'requires com.hedera.protobuf.java.api'
        // in testFixtures scope - #14026
        proto {
            srcDir(protoApiSrc)
            exclude("mirror", "sdk", "internal")
        }
    }
}

testModuleInfo {
    requires("com.hedera.node.hapi")
    // we depend on the protoc compiled hapi during test as we test our pbj generated code
    // against it to make sure it is compatible
    requires("com.google.protobuf.util")
    requires("org.junit.jupiter.api")
    requires("org.junit.jupiter.params")
    requires("org.assertj.core")
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
