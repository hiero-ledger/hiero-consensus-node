// SPDX-License-Identifier: Apache-2.0
plugins {
    id("org.hiero.gradle.module.application")
    id("org.hiero.gradle.feature.shadow")
}

dependencies {

    implementation("info.picocli:picocli:4.7.0")
    implementation("org.apache.logging.log4j:log4j-api:2.17.2")
    implementation("org.apache.logging.log4j:log4j-core:2.17.2")

    // used to write json report
    implementation("com.google.code.gson:gson:2.10")
}

mainModuleInfo {
    requires ("com.hedera.node.app")
    requires ("com.hedera.node.app.test.fixtures")

    requires ("com.swirlds.merkledb")


    // Define the individual libraries
    // JUnit Bundle
    requires("org.junit.jupiter.api")
    requires("org.junit.jupiter.engine")
    requires("org.junit.platform.launcher")

}

application.mainClass = "com.hedera.statevalidation.StateOperatorCommand"
