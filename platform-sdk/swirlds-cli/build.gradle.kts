// SPDX-License-Identifier: Apache-2.0
plugins {
    id("org.hiero.gradle.module.library")
    id("org.hiero.gradle.feature.publish-artifactregistry")
}

mainModuleInfo { runtimeOnly("com.swirlds.platform.core") }

testModuleInfo { requires("org.junit.jupiter.api") }

tasks.register<Sync>("copyLibraries") {
    into(layout.projectDirectory.dir("../sdk/data/lib"))
    from(configurations.runtimeClasspath)
}

tasks.assemble { dependsOn("copyLibraries") }

val cleanRun =
    tasks.register<Delete>("cleanRun") {
        val sdkDir = layout.projectDirectory.dir("../sdk/data/")
        delete(sdkDir.dir("lib"))
    }

tasks.clean { dependsOn(cleanRun) }
