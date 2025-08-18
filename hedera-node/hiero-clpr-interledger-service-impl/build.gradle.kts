// SPDX-License-Identifier: Apache-2.0
plugins { id("org.hiero.gradle.module.library") }

description = "Hiero CLPR Interledger Service Implementation"

mainModuleInfo {
    annotationProcessor("dagger.compiler")

    runtimeOnly("io.helidon.grpc.core")
    runtimeOnly("io.helidon.webclient")
    runtimeOnly("io.helidon.webclient.grpc")
    runtimeOnly("com.hedera.pbj.grpc.client.helidon")
}

testModuleInfo {
    requires("com.hedera.node.app")
    requires("com.swirlds.state.api.test.fixtures")
    requires("org.assertj.core")
    requires("org.junit.jupiter.api")
    requires("org.mockito")
    requires("org.mockito.junit.jupiter")

    opensTo("com.hedera.node.app.spi.test.fixtures") // log captor injection
}
