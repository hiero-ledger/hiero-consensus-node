/*
 * Copyright (C) 2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
    requires("org.apache.logging.log4j.core")
    requires("org.bouncycastle.provider")
    requires("org.junit.jupiter.params")
    requires("org.junit.jupiter.api")
    requires("org.mockito")
    requires("org.assertj.core")
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
    requires("com.swirlds.config.api")
    requires("org.junit.jupiter.api")
    requires("org.junit.jupiter.params")
    requires("org.mockito")
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
