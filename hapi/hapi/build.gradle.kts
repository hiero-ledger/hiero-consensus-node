// SPDX-License-Identifier: Apache-2.0
plugins {
    id("org.hiero.gradle.module.library")
    id("org.hiero.gradle.feature.protobuf")
    id("org.hiero.gradle.feature.test-fixtures")
    id("com.hedera.pbj.pbj-compiler")
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

// Test-only: add a mock `block_proof` field to the external BlockAcknowledgement message so the
// simulated block node
// can send a proof with each ack and the consensus node can validate it. The proto comes from the
// org.hiero.block-node:protobuf-sources jar (not editable in-repo), so patch the extracted copy in
// place before PBJ
// compiles it. Idempotent; fails the build loudly if the message layout changes so it can never
// silently no-op.
val blockPublishProto =
    layout.buildDirectory.file(
        "extracted-protos/main/block-node/api/block_stream_publish_service.proto"
    )

tasks.named("extractProto") {
    notCompatibleWithConfigurationCache(
        "patches the external block-node proto to add a mock block_proof field"
    )
    doLast {
        val proto = blockPublishProto.get().asFile
        if (proto.exists()) {
            val text = proto.readText()
            if (!text.contains("block_proof")) {
                val patched =
                    text.replace(
                        Regex(
                            "(message BlockAcknowledgement \\{[\\s\\S]*?uint64 block_number = 1;)"
                        ),
                        "$1\n\n        // Mock block proof (test-only): \"ack-\" + block_number on a valid ack.\n        string block_proof = 2;",
                    )
                if (patched == text) {
                    throw GradleException(
                        "Failed to patch BlockAcknowledgement with a block_proof field"
                    )
                }
                proto.writeText(patched)
            }
        }
    }
}

testModuleInfo {
    requires("com.hedera.node.hapi")
    requires("com.google.protobuf.util")
    requires("org.assertj.core")
    requires("org.junit.jupiter.api")
    requires("org.junit.jupiter.params")

    // against it to make sure it is compatible
    // we depend on the protoc compiled hapi during test as we test our pbj generated code
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
