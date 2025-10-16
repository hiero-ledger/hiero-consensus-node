// SPDX-License-Identifier: Apache-2.0
plugins { id("org.hiero.gradle.module.library") }

description = "Default Hedera File Service Implementation"

mainModuleInfo { annotationProcessor("dagger.compiler") }

testModuleInfo {
    requires("com.hedera.node.app")
    requires("com.hedera.node.app.service.file.impl")
    requires("com.hedera.node.app.service.token")
    requires("com.swirlds.state.api.test.fixtures")
    requires("com.hedera.node.app.spi.test.fixtures")
    requires("com.hedera.node.config.test.fixtures")
    requires("org.assertj.core")
    requires("org.junit.jupiter.api")
    requires("org.mockito")
    requires("org.mockito.junit.jupiter")
    requires("com.hedera.node.app.service.entityid.impl")
    runtimeOnly("com.swirlds.config.extensions.test.fixtures")
}
