// SPDX-License-Identifier: Apache-2.0
plugins { id("org.hiero.gradle.module.library") }

description = "Hedera Roster Service Implementation"

testModuleInfo {
    requires("com.hedera.node.app.test.fixtures")
    requires("com.swirlds.platform.core.test.fixtures")
    requires("org.assertj.core")
    requires("org.junit.jupiter.api")
    requires("org.mockito")
    requires("org.mockito.junit.jupiter")
}
