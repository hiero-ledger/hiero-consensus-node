// SPDX-License-Identifier: Apache-2.0
plugins {
    id("org.hiero.gradle.module.application")
    id("org.hiero.gradle.feature.shadow")
}

dependencies {
    implementation("info.picocli:picocli:4.7.0")
    implementation("org.apache.logging.log4j:log4j-api:2.17.2")
    runtimeOnly("org.apache.logging.log4j:log4j-core:2.17.2")

    // used to write json report
    implementation("com.google.code.gson:gson:2.10")
}

mainModuleInfo {
    requires("com.hedera.node.app")
    requires("com.hedera.node.app.test.fixtures")

    requires("com.fasterxml.jackson.databind")
    requires("com.hedera.node.app.hapi.utils")
    requires("com.hedera.node.app.service.addressbook.impl")
    requires("com.hedera.node.app.service.consensus.impl")
    requires("com.hedera.node.app.service.contract.impl")
    requires("com.hedera.node.app.service.file.impl")
    requires("com.hedera.node.app.service.network.admin.impl")
    requires("com.hedera.node.app.service.schedule.impl")
    requires("com.hedera.node.app.service.token.impl")
    requires("com.hedera.node.app.service.util.impl")
    requires("com.hedera.node.app.spi")
    requires("com.hedera.node.app.spi.test.fixtures")
    requires("com.hedera.node.config")
    requires("com.hedera.node.hapi")
    requires("com.hedera.pbj.runtime")
    requires("com.swirlds.base")
    requires("com.swirlds.common")
    requires("com.swirlds.config.api")
    requires("com.swirlds.config.extensions")
    requires("com.swirlds.metrics.api")
    requires("com.swirlds.platform.core")
    requires("com.swirlds.state.api")
    requires("com.swirlds.state.impl")
    requires("com.swirlds.virtualmap")
    requires("com.swirlds.merkledb")

    // Define the individual libraries
    // JUnit Bundle
    requires("org.junit.jupiter.params")
    requires("org.junit.platform.commons")
    requires("org.junit.platform.engine")
    requires("org.junit.jupiter.api")
    runtimeOnly("org.junit.jupiter.engine")
    requires("org.junit.platform.launcher")
}

application.mainClass = "com.hedera.statevalidation.StateOperatorCommand"
