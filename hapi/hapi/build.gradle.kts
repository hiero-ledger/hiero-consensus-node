// SPDX-License-Identifier: Apache-2.0
plugins {
    id("org.hiero.gradle.module.library")
    id("org.hiero.gradle.feature.protobuf")
    id("org.hiero.gradle.feature.test-fixtures")
    id("com.hedera.pbj.pbj-compiler")
}

description = "Hedera API"

pbj {
    // Derive roots from the state catalog, so adding a state does not require a second model list.
    diffMessageContainers = setOf("com.hedera.hapi.platform.state.StateValue")
    generateCopyBuilderTracking = true
}

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
    protobuf("org.hiero.block-node:protobuf-sources")
}

sourceSets {
    val protoApiSrc = layout.projectDirectory.dir("../hedera-protobuf-java-api/src/main/proto")
    main {
        pbj {
            srcDir(protoApiSrc)
            srcDir(tasks.extractProto) // see comment on the 'dependencies' block
            // (FUTURE) Remove provision for proof_service.proto when it no longer conflicts with
            // the block node protos (v0.22)
            exclude("mirror", "sdk", "internal", "block-node/api/proof_service.proto")
        }
        // The below should be replaced with a 'requires com.hedera.protobuf.java.api'
        // in testFixtures scope - #14026
        proto {
            srcDir(protoApiSrc)
            // (FUTURE) Remove provision for proof_service.proto when it no longer conflicts with
            // the block node protos (v0.22)
            exclude("mirror", "sdk", "internal", "block-node/api/proof_service.proto")
        }
    }
}

// Tests depend on the protoc-compiled HAPI (com.google.protobuf.util) because they check the
// PBJ-generated code against it for compatibility.
testModuleInfo {
    requires("com.hedera.node.hapi")
    requires("com.google.protobuf.util")
    requires("org.assertj.core")
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
