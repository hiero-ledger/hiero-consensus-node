// SPDX-License-Identifier: Apache-2.0
plugins { id("org.hiero.gradle.module.library") }

description = "Default Hedera Network Admin Service Implementation"

val writeSemanticVersionProperties =
    tasks.register<WriteProperties>("writeSemanticVersionProperties") {
        property("hapi.proto.version", project.version)
        property("hedera.services.version", project.version)

        destinationFile.set(
            layout.buildDirectory.file("generated/version/semantic-version.properties")
        )
    }

tasks.processResources { from(writeSemanticVersionProperties) }

mainModuleInfo { annotationProcessor("dagger.compiler") }

testModuleInfo {
    requires("com.hedera.node.app")
    requires("com.hedera.node.app.service.addressbook.impl")
    requires("com.hedera.node.app.service.file.impl")
    requires("com.hedera.node.app.service.network.admin.impl")
    requires("com.hedera.node.app.service.token.impl")
    requires("com.hedera.node.app.spi.test.fixtures")
    requires("com.swirlds.state.api.test.fixtures")
    requires("com.hedera.node.app.test.fixtures")
    requires("com.hedera.node.config.test.fixtures")
    requires("org.assertj.core")
    requires("org.junit.jupiter.api")
    requires("org.mockito")
    requires("org.mockito.junit.jupiter")

    opensTo("com.hedera.node.app.spi.test.fixtures") // log captor injection
}
