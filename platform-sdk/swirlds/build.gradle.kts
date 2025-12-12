// SPDX-License-Identifier: Apache-2.0
plugins { id("org.hiero.gradle.module.application") }

mainModuleInfo {
    runtimeOnly("com.swirlds.platform.core")
    runtimeOnly("com.swirlds.platform.core.test.fixtures")
    runtimeOnly("com.swirlds.merkle")
    runtimeOnly("com.swirlds.merkle.test.fixtures")
}

application.mainClass.set("com.swirlds.platform.Browser")

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

tasks.register<Copy>("copyApps") {
    destinationDir = layout.projectDirectory.dir("../sdk").asFile
    from(tasks.jar) // 'swirlds.jar' goes in directly into 'sdk'
    into("data/lib") {
        // Copy dependencies into `sdk/data/lib`
        from(project.configurations.runtimeClasspath)
    }
}

tasks.assemble { dependsOn(tasks.named("copyApps")) }
