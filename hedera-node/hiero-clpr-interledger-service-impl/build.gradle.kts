// SPDX-License-Identifier: Apache-2.0
plugins { id("org.hiero.gradle.module.library") }

description = "Hiero CLPR Interledger Service Implementation"

mainModuleInfo { annotationProcessor("dagger.compiler") }

testModuleInfo {
    requires("com.hedera.node.app")
    requires("com.hedera.node.app.spi.test.fixtures")
    requires("org.assertj.core")
    requires("org.junit.jupiter.api")
    requires("org.mockito")
    requires("org.mockito.junit.jupiter")

    opensTo("com.hedera.node.app.spi.test.fixtures") // log captor injection
}
