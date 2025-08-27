// SPDX-License-Identifier: Apache-2.0
plugins { id("org.hiero.gradle.module.library") }

description = "Default Hedera Schedule Service Implementation"

mainModuleInfo { annotationProcessor("dagger.compiler") }

testModuleInfo {
    requires("com.hedera.node.app.service.token.impl")
    requires("com.hedera.node.app.spi.test.fixtures")
    requires("com.hedera.node.config.test.fixtures")
    requires("com.swirlds.state.api.test.fixtures")
    requires("com.hedera.node.app")
    requires("com.swirlds.base")
    requires("org.assertj.core")
    requires("org.eclipse.collections.api")
    requires("org.junit.jupiter.api")
    requires("org.mockito")
    requires("org.mockito.junit.jupiter")

    opensTo("com.hedera.node.app.spi.test.fixtures") // log captor injection
}
