// SPDX-License-Identifier: Apache-2.0
plugins { id("org.hiero.gradle.module.library") }

description = "Hedera Services API Utilities"

dependencies {
    testImplementation(testFixtures(project(":swirlds-state-impl")))
    testImplementation(testFixtures(project(":swirlds-state-api")))
    testImplementation(testFixtures(project(":swirlds-merkledb")))
    testImplementation(project(":hapi"))
    testImplementation(project(":consensus-metrics"))
}

mainModuleInfo { annotationProcessor("dagger.compiler") }

testModuleInfo {
    requires("org.junit.jupiter.api")
    requires("org.junit.jupiter.params")
    requires("org.mockito")
    requires("org.assertj.core")
    requires("com.hedera.node.hapi")
    requires("org.hiero.consensus.metrics")
    requires("com.swirlds.state.impl.test.fixtures")
    requires("com.swirlds.state.api.test.fixtures")
    requires("com.swirlds.merkledb.test.fixtures")
    requires("com.swirlds.virtualmap")
}
