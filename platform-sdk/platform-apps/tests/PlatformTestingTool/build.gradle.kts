// SPDX-License-Identifier: Apache-2.0
plugins {
    id("org.hiero.gradle.module.application")
    id("org.hiero.gradle.feature.test-timing-sensitive")

    id("com.google.protobuf")
}

// Remove the following line to enable all 'javac' lint checks that we have turned on by default
// and then fix the reported issues.
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:-exports,-static,-cast")
}

application.mainClass = "com.swirlds.demo.platform.PlatformTestingToolMain"

testModuleInfo {
    requires("org.hiero.base.utility.test.fixtures")
    requires("org.apache.logging.log4j.core")
    requires("org.bouncycastle.provider")
    requires("org.junit.jupiter.params")
    requires("org.junit.jupiter.api")
    requires("org.mockito")
    requires("org.assertj.core")
    requires("com.swirlds.config.extensions.test.fixtures")
    requires("com.swirlds.merkledb.test.fixtures")
}

timingSensitiveModuleInfo {
    requires("com.hedera.node.hapi")
    requires("com.swirlds.common")
    requires("com.swirlds.common.test.fixtures")
    requires("com.swirlds.demo.platform")
    requires("com.swirlds.fcqueue")
    requires("com.swirlds.merkle")
    requires("com.swirlds.merkle.test.fixtures")
    requires("com.swirlds.platform.core")
    requires("com.swirlds.platform.core.test.fixtures")
    requires("org.hiero.base.crypto")
    requires("org.hiero.base.crypto.test.fixtures")
    requires("org.hiero.base.utility")
    requires("org.hiero.consensus.model")
    requires("org.junit.jupiter.api")
    requires("org.junit.jupiter.params")
    requires("org.mockito")
    requires("org.hiero.consensus.model")
}

protobuf { protoc { artifact = "com.google.protobuf:protoc" } }

configurations.configureEach {
    if (name.startsWith("protobufToolsLocator") || name.endsWith("ProtoPath")) {
        @Suppress("UnstableApiUsage")
        shouldResolveConsistentlyWith(configurations.getByName("mainRuntimeClasspath"))
        attributes { attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_API)) }
        exclude(group = project.group.toString(), module = project.name)
        withDependencies {
            isTransitive = true
            extendsFrom(configurations["internal"])
        }
    }
}
