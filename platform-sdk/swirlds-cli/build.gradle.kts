// SPDX-License-Identifier: Apache-2.0
plugins {
    id("org.hiero.gradle.module.application")
    id("org.hiero.gradle.feature.publish-maven-repository")
    id("org.hiero.gradle.feature.publish-maven-central")
}

testModuleInfo {
    requires("com.swirlds.platform.core.test.fixtures")
    requires("org.hiero.consensus.pcli")
    requires("org.hiero.consensus.hashgraph.impl.test.fixtures")
    requires("org.hiero.consensus.roster.test.fixtures")
    requires("org.hiero.consensus.utility.test.fixtures")
    requires("org.junit.jupiter.api")
    requires("org.junit.jupiter.params")
    requires("org.mockito")
}

tasks.jar {
    // Gradle fails to track 'configurations.runtimeClasspath' as an input to the task if it is
    // only used in the 'manifest.attributes'. Hence, we explicitly add it as input.
    inputs.files(configurations.runtimeClasspath)

    archiveVersion.convention(null as String?)
    doFirst {
        manifest {
            attributes(
                "Class-Path" to
                    inputs.files
                        .filter { it.extension == "jar" }
                        .map { "data/lib/" + it.name }
                        .sorted()
                        .joinToString(separator = " ")
            )
        }
    }
}

val sdkDir = layout.projectDirectory.dir("../sdk")

tasks.register<Copy>("copyApps") {
    destinationDir = layout.projectDirectory.dir("../sdk").asFile
    from(tasks.jar)
    into("data/lib") { from(configurations.runtimeClasspath) }
}

application.mainClass = "org.hiero.consensus.pcli.Pcli"

val cleanRun =
    tasks.register<Delete>("cleanRun") {
        delete(
            sdkDir.asFileTree.matching {
                include("swirlds-cli.jar")
                include("swirlds.jar")
            }
        )

        val dataDir = sdkDir.dir("data")
        delete(dataDir.dir("lib"))
    }

tasks.clean { dependsOn(cleanRun) }

tasks.assemble { dependsOn(tasks.named("copyApps")) }
